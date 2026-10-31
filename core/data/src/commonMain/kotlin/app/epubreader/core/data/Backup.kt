package app.epubreader.core.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Why a file could not be restored. */
sealed class BackupException(message: String) : Exception(message) {
    class NotABackup(detail: String) : BackupException(detail)
    /** Written by a newer app version than this one understands. */
    class TooNew(val version: Int) : BackupException("backup version $version")
}

/** What a restore did (shown to the user). Books "waiting" are not on this device yet; they attach when found. */
data class RestoreResult(val booksMatched: Int, val booksWaiting: Int, val positions: Int, val collections: Int, val settings: Int)

/**
 * F-090 / R-009 / C-011: the backup file. Library metadata, reading positions, collections and settings (and, from M6,
 * bookmarks and highlights) — never the books themselves or where their files are (device-local). Every entry keeps its
 * stable id, change timestamp and tombstone (F-115).
 *
 * Restore merges: books are matched by identity key; for the same item the newer `updatedAt` wins, so neither an
 * older backup nor an older local state undoes a later change, and a newer tombstone deletes. A book that is not on
 * this device is kept as a pending identity (a book row with no file), which a later scan or open attaches to.
 * Older file versions are upgraded by [migrations] first, so every backup ever written stays restorable.
 */
class Backup(
    private val db: AppDatabase,
    private val clock: HybridClock,
    /** Whether a folder exists here: another device's discovery folder is not restored (every scan would fail). */
    private val folderExists: (String) -> Boolean = { true },
) {
    suspend fun export(appVersion: String): String {
        val d = db.backupDao()
        val file = BackupFileV1(
            createdAt = clock.now(), app = appVersion,
            books = d.books().map { BackupBook(it.id, it.identityKey, it.title, it.authors.split('\n').filter(String::isNotBlank), it.addedAt, it.lastOpenedAt, it.hidden, it.hiddenUpdatedAt, it.updatedAt, it.deleted) },
            positions = d.positions(),
            collections = db.collectionDao().all(),
            collectionBooks = db.collectionDao().memberships(),
            settings = d.settings(),
        )
        return JSON.encodeToString(BackupFileV1.serializer(), file)
    }

    suspend fun restore(text: String): RestoreResult {
        val file = parse(text)
        val newest = (file.books.map { maxOf(it.updatedAt, it.hiddenUpdatedAt) } + file.positions.map { it.updatedAt } + file.collections.map { it.updatedAt } +
            file.collectionBooks.map { it.updatedAt } + file.settings.map { it.updatedAt }).maxOrNull() ?: 0L
        clock.observe(newest) // later local changes get later stamps than anything restored
        return db.useWriterConnection { t -> t.immediateTransaction { merge(file) } }
    }

    private suspend fun merge(f: BackupFileV1): RestoreResult {
        val d = db.backupDao(); val lib = db.libraryDao(); val cd = db.collectionDao()
        // Books: backup id → local id.
        val bookIds = HashMap<String, String>()
        var matched = 0; var waiting = 0
        for (b in f.books) {
            val local = lib.byIdentity(b.identityKey)
            if (local != null) {
                bookIds[b.id] = local.id
                if (!local.deleted || lib.filesOf(local.id).isNotEmpty()) matched++ else waiting++
                val hiddenNewer = b.hiddenUpdatedAt > local.hiddenUpdatedAt
                d.mergeBook(
                    local.id,
                    hidden = if (hiddenNewer) b.hidden else local.hidden,
                    hiddenUpdatedAt = maxOf(b.hiddenUpdatedAt, local.hiddenUpdatedAt),
                    addedAt = minOf(b.addedAt, local.addedAt),
                    lastOpenedAt = listOfNotNull(b.lastOpenedAt, local.lastOpenedAt).maxOrNull(),
                    updatedAt = maxOf(b.updatedAt, local.updatedAt),
                )
            } else {
                val id = if (lib.byId(b.id) == null) b.id else newId()
                bookIds[b.id] = id
                waiting++
                // No file here yet: a tombstoned row that keeps the identity. A scan that finds the file revives it.
                val authors = b.authors.joinToString("\n")
                lib.insertBook(
                    BookEntity(
                        id = id, identityKey = b.identityKey, title = b.title, sortTitle = app.epubreader.core.epub.meta.MetadataReader.sortKey(b.title),
                        authors = authors, sortAuthor = b.authors.firstOrNull()?.lowercase() ?: "", language = null, titleDirection = "auto",
                        coverThumb = null, addedAt = b.addedAt, lastOpenedAt = b.lastOpenedAt, hidden = b.hidden, updatedAt = b.updatedAt,
                        deleted = true, hiddenUpdatedAt = b.hiddenUpdatedAt,
                    ),
                )
            }
        }
        // Positions: one per book; the newer one wins.
        var positions = 0
        for (p in f.positions) {
            val bookId = bookIds[p.bookId] ?: continue
            val cur = db.positionDao().anyRow(bookId)
            if (cur == null || p.updatedAt > cur.updatedAt) {
                db.positionDao().upsert(p.copy(id = cur?.id ?: p.id, bookId = bookId)); positions++
            }
        }
        // Collections: by id, else by name among this device's live collections (no doubled "Classics").
        val collectionIds = HashMap<String, String>()
        var collections = 0
        val byName = cd.all().filter { !it.deleted }.associateBy { it.name.trim().lowercase() }
        for (c in f.collections) {
            val cur = cd.byId(c.id) ?: if (!c.deleted) byName[c.name.trim().lowercase()] else null
            collectionIds[c.id] = cur?.id ?: c.id
            if (cur == null) { cd.upsert(c); collections++ }
            else if (cur.id == c.id && c.updatedAt > cur.updatedAt) { cd.upsert(c.copy(createdAt = minOf(c.createdAt, cur.createdAt))); collections++ }
        }
        for (m in f.collectionBooks) {
            val cid = collectionIds[m.collectionId] ?: continue
            val bid = bookIds[m.bookId] ?: continue
            val cur = cd.membership(cid, bid)
            if (cur == null) cd.upsertMembership(m.copy(id = if (d.membershipIdTaken(m.id)) newId() else m.id, collectionId = cid, bookId = bid))
            else if (m.updatedAt > cur.updatedAt) cd.upsertMembership(cur.copy(updatedAt = m.updatedAt, deleted = m.deleted))
        }
        // Settings: by key, newer wins.
        var settings = 0
        for (s in f.settings) {
            if (s.key == SettingsStore.LIBRARY_SCOPE && !s.deleted && !scopeUsable(s.json)) continue
            val cur = d.setting(s.key)
            if (cur == null || s.updatedAt > cur.updatedAt) { d.upsertSetting(s.copy(id = cur?.id ?: s.id)); settings++ }
        }
        return RestoreResult(matched, waiting, positions, collections, settings)
    }

    private fun scopeUsable(json: String): Boolean {
        val v = runCatching { Json.parseToJsonElement(json).jsonPrimitive.content }.getOrNull() ?: return false
        return v == "everywhere" || folderExists(v)
    }

    companion object {
        const val FORMAT = "app.epubreader.backup"
        const val VERSION = 1
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

        /**
         * Upgrades an older file to the next version: `migrations[n]` turns version n into n + 1. Add an entry whenever
         * [VERSION] goes up; never change an old one (R-009: old backups stay restorable).
         */
        val migrations: Map<Int, (JsonObject) -> JsonObject> = emptyMap()

        /** "epub-reader-backup-2026-10-31.json" for the system save dialog. */
        fun fileName(year: Int, month: Int, day: Int) = "epub-reader-backup-%04d-%02d-%02d.json".format(year, month, day)

        fun parse(text: String): BackupFileV1 {
            var o = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: throw BackupException.NotABackup("not JSON")
            if ((o["format"] as? kotlinx.serialization.json.JsonPrimitive)?.content != FORMAT) throw BackupException.NotABackup("format")
            var v = (o["version"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: throw BackupException.NotABackup("version")
            if (v > VERSION) throw BackupException.TooNew(v)
            if (v < 1) throw BackupException.NotABackup("version $v")
            while (v < VERSION) { o = migrations.getValue(v)(o); v++ }
            return try { JSON.decodeFromJsonElement(BackupFileV1.serializer(), o) } catch (e: Exception) { throw BackupException.NotABackup("damaged: ${e.message}") }
        }
    }
}

private fun String.format(vararg a: Any): String {
    // commonMain has no String.format: only %0Nd is used here.
    var i = 0
    return Regex("%0(\\d)d").replace(this) { m -> a[i++].toString().padStart(m.groupValues[1].toInt(), '0') }
}

@Serializable
data class BackupBook(
    val id: String, val identityKey: String, val title: String, val authors: List<String>, val addedAt: Long, val lastOpenedAt: Long?,
    val hidden: Boolean, val hiddenUpdatedAt: Long = 0, val updatedAt: Long, val deleted: Boolean,
)

/** Version 1 of the file. `bookmarks` and `highlights` are written empty until M6 adds them (entries defined there). */
@Serializable
data class BackupFileV1(
    val format: String = Backup.FORMAT,
    val version: Int = Backup.VERSION,
    val createdAt: Long,
    val app: String = "",
    val books: List<BackupBook> = emptyList(),
    val positions: List<ReadingPositionEntity> = emptyList(),
    val bookmarks: JsonArray = JsonArray(emptyList()),
    val highlights: JsonArray = JsonArray(emptyList()),
    val collections: List<CollectionEntity> = emptyList(),
    @SerialName("collectionBooks") val collectionBooks: List<CollectionBookEntity> = emptyList(),
    val settings: List<SettingEntity> = emptyList(),
)

@Dao
interface BackupDao {
    @Query("SELECT * FROM book") suspend fun books(): List<BookEntity>
    @Query("SELECT * FROM reading_position") suspend fun positions(): List<ReadingPositionEntity>
    @Query("SELECT * FROM setting") suspend fun settings(): List<SettingEntity>
    @Query("SELECT * FROM setting WHERE `key` = :key") suspend fun setting(key: String): SettingEntity?
    @Upsert suspend fun upsertSetting(s: SettingEntity)
    @Query("SELECT EXISTS(SELECT 1 FROM collection_book WHERE id = :id)") suspend fun membershipIdTaken(id: String): Boolean
    @Query("UPDATE book SET hidden = :hidden, hiddenUpdatedAt = :hiddenUpdatedAt, addedAt = :addedAt, lastOpenedAt = :lastOpenedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun mergeBook(id: String, hidden: Boolean, hiddenUpdatedAt: Long, addedAt: Long, lastOpenedAt: Long?, updatedAt: Long)
}
