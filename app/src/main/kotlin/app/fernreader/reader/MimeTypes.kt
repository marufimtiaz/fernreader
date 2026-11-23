package app.fernreader.reader

object MimeTypes {
    private val byExt = mapOf(
        "xhtml" to "application/xhtml+xml", "xht" to "application/xhtml+xml", "html" to "text/html", "htm" to "text/html",
        "css" to "text/css", "svg" to "image/svg+xml", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "webp" to "image/webp", "ttf" to "font/ttf", "otf" to "font/otf", "woff" to "font/woff",
        "woff2" to "font/woff2", "opf" to "application/oebps-package+xml", "ncx" to "application/x-dtbncx+xml",
        "xml" to "application/xml", "smil" to "application/smil+xml", "js" to "text/javascript", "mp3" to "audio/mpeg",
        "mp4" to "video/mp4", "m4a" to "audio/mp4",
    )

    fun of(name: String): String = byExt[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
}
