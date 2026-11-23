package app.fernreader.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Highlight palette ids (F-060: at least 6). The colours themselves are the UI's. */
object HighlightColors {
    const val YELLOW = "yellow"
    const val GREEN = "green"
    const val BLUE = "blue"
    const val PINK = "pink"
    const val ORANGE = "orange"
    const val PURPLE = "purple"
    val ALL = listOf(YELLOW, GREEN, BLUE, PINK, ORANGE, PURPLE)
}

/** How an annotation is drawn (F-061); an annotation with a note is still one of these. */
object AnnotationStyle {
    const val HIGHLIGHT = "highlight"
    const val UNDERLINE = "underline"
}

/** A highlight, underline or note on a text range (F-061), synced row (F-115). `cfi` is an original-document range CFI. */
@kotlinx.serialization.Serializable
@Entity(tableName = "annotation", indices = [Index(value = ["bookId"])])
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val cfi: String,
    val spineIndex: Int,
    val text: String,
    val chapter: String,
    val color: String,
    val style: String,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
)

@Dao
abstract class AnnotationDao {
    @Query("SELECT * FROM annotation WHERE bookId = :bookId AND deleted = 0")
    abstract fun live(bookId: String): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotation WHERE id = :id")
    abstract suspend fun anyRow(id: String): AnnotationEntity?

    @Upsert abstract suspend fun upsert(row: AnnotationEntity)

    /** One transaction (R-009): read-modify-write of the given fields; unknown id does nothing. */
    @Transaction
    open suspend fun change(id: String, at: Long, color: String?, style: String?, note: String?, setNote: Boolean) {
        val cur = anyRow(id) ?: return
        upsert(cur.copy(
            color = color ?: cur.color,
            style = style ?: cur.style,
            note = if (setNote) note else cur.note,
            updatedAt = at,
        ))
    }

    @Transaction
    open suspend fun tombstone(id: String, at: Long) {
        val cur = anyRow(id) ?: return
        upsert(cur.copy(deleted = true, updatedAt = at))
    }

    /** One transaction (R-009): the row is live again with [note]; null if there is no such row. */
    @Transaction
    open suspend fun restore(id: String, at: Long, note: String?): AnnotationEntity? {
        val cur = anyRow(id) ?: return null
        return cur.copy(deleted = false, note = note, updatedAt = at).also { upsert(it) }
    }
}

data class Annotation(
    val id: String,
    val bookId: String,
    val cfi: String,
    val spineIndex: Int,
    val text: String,
    val chapter: String,
    val color: String,
    val style: String,
    /** Plain text; null = no note. */
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Annotations as the UI sees them (a seam for tests). */
interface AnnotationPort {
    /** Live annotations of a book (not removed), in book order. */
    fun annotations(bookId: String): Flow<List<Annotation>>
    suspend fun add(bookId: String, cfi: String, spineIndex: Int, text: String, chapter: String, color: String, style: String): Annotation
    /** Sets the fields given (null = leave). A blank [note] clears the note. */
    suspend fun update(id: String, color: String? = null, style: String? = null, note: String? = null)
    /** Tombstones the row (the row stays for sync). */
    suspend fun remove(id: String)
    /**
     * Undo of [remove]: the same row (same id, F-115) is live again with [note] (blank = none), in one write. Null if
     * there is no such row.
     */
    suspend fun restore(id: String, note: String?): Annotation?
}

class AnnotationStore(private val db: AppDatabase, private val clock: HybridClock) : AnnotationPort {
    private val comparator = compareBy<Annotation> { it.spineIndex }.thenBy(CfiOrder) { it.cfi }.thenBy { it.createdAt }

    override fun annotations(bookId: String): Flow<List<Annotation>> =
        db.annotationDao().live(bookId).map { rows -> rows.map { it.toDomain() }.sortedWith(comparator) }

    override suspend fun add(bookId: String, cfi: String, spineIndex: Int, text: String, chapter: String, color: String, style: String): Annotation {
        val now = clock.now()
        val row = AnnotationEntity(newId(), bookId, cfi, spineIndex, text, chapter, color, style, note = null, now, now, deleted = false)
        db.annotationDao().upsert(row)
        return row.toDomain()
    }

    override suspend fun update(id: String, color: String?, style: String?, note: String?) =
        db.annotationDao().change(id, clock.now(), color, style, note?.takeIf { it.isNotBlank() }, setNote = note != null)

    override suspend fun remove(id: String) = db.annotationDao().tombstone(id, clock.now())

    override suspend fun restore(id: String, note: String?): Annotation? =
        db.annotationDao().restore(id, clock.now(), note?.takeIf { it.isNotBlank() })?.toDomain()

    private fun AnnotationEntity.toDomain() = Annotation(id, bookId, cfi, spineIndex, text, chapter, color, style, note, createdAt, updatedAt)
}
