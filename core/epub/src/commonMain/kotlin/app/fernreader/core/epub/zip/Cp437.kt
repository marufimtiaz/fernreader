package app.fernreader.core.epub.zip

/** IBM code page 437, bytes 0x80..0xFF (zip's legacy default encoding for entry names). */
internal const val CP437_HIGH =
    "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀" +
    "αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ "

internal fun decodeCp437(b: ByteArray, off: Int, len: Int): String = buildString(len) {
    for (i in off until off + len) {
        val v = b[i].toInt() and 0xff
        append(if (v < 0x80) v.toChar() else CP437_HIGH[v - 0x80])
    }
}
