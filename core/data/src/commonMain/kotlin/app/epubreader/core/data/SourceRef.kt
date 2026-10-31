package app.epubreader.core.data

/** Where to read a book from. [key] is what `book_file.path` stores. */
sealed interface SourceRef {
    val key: String

    data class File(val path: String) : SourceRef { override val key: String get() = path }
    data class Content(val uri: String) : SourceRef { override val key: String get() = uri }

    companion object {
        fun fromKey(key: String): SourceRef = if (key.startsWith("content://")) Content(key) else File(key)
    }
}
