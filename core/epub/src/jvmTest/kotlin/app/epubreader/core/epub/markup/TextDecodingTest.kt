package app.epubreader.core.epub.markup

import kotlin.test.Test
import kotlin.test.assertEquals

class TextDecodingTest {
    @Test fun utf8_with_bom() = assertEquals("<a>é</a>", TextDecoding.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<a>é</a>".encodeToByteArray()))
    @Test fun utf16le_with_bom() = assertEquals("<a>é</a>", TextDecoding.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<a>é</a>".toByteArray(Charsets.UTF_16LE)))
    @Test fun latin1_declared() = assertEquals("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>é</a>",
        TextDecoding.decode("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>é</a>".toByteArray(Charsets.ISO_8859_1)))
    @Test fun cp1252_quotes() = assertEquals("<meta charset=\"windows-1252\">“x”",
        TextDecoding.decode("<meta charset=\"windows-1252\">“x”".toByteArray(charset("windows-1252"))))
    @Test fun default_utf8() = assertEquals("Borodinó", TextDecoding.decode("Borodinó".encodeToByteArray()))
}
