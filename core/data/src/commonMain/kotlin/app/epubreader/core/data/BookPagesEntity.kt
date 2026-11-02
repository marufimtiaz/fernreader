package app.epubreader.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import app.epubreader.core.epub.pages.BookPages

/**
 * C-083 book pages, stored with the book (schema version 8): computed once per book file ([digest], its
 * central-directory digest) and algorithm [version] ([BookPages.VERSION]); another file or version computes again.
 * Derived from the file, so neither synced nor backed up. Columns are [BookPages]' stored form: [source] a
 * PageSource name, [labels] print labels joined by newlines (null for virtual pages), [starts] "spine.offset" per
 * page, [lengths] the spine items' text lengths.
 */
@Entity(tableName = "book_pages")
data class BookPagesEntity(
    @PrimaryKey val bookId: String,
    val digest: String,
    val version: Int,
    val source: String,
    val pageCount: Int,
    val labels: String?,
    val starts: String,
    val lengths: String,
) {
    fun pages(): BookPages? = BookPages.decode(source, labels, starts, lengths)?.takeIf { it.count == pageCount }

    companion object {
        fun of(bookId: String, digest: String, p: BookPages) =
            BookPagesEntity(bookId, digest, BookPages.VERSION, p.source.name, p.count, p.encodeLabels(), p.encodeStarts(), p.encodeLengths())
    }
}

@Dao
interface BookPagesDao {
    @Query("SELECT * FROM book_pages WHERE bookId = :bookId AND digest = :digest AND version = :version")
    suspend fun get(bookId: String, digest: String, version: Int = BookPages.VERSION): BookPagesEntity?

    @Upsert suspend fun put(row: BookPagesEntity)
}
