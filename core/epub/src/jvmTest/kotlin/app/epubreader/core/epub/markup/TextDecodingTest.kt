package app.epubreader.core.epub.markup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextDecodingTest {
    @Test fun utf8_with_bom() = assertEquals("<a>é</a>", TextDecoding.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<a>é</a>".encodeToByteArray()))
    @Test fun utf16le_with_bom() = assertEquals("<a>é</a>", TextDecoding.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<a>é</a>".toByteArray(Charsets.UTF_16LE)))
    @Test fun latin1_declared() = assertEquals("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>é</a>",
        TextDecoding.decode("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>é</a>".toByteArray(Charsets.ISO_8859_1)))
    @Test fun cp1252_quotes() = assertEquals("<meta charset=\"windows-1252\">“x”",
        TextDecoding.decode("<meta charset=\"windows-1252\">“x”".toByteArray(charset("windows-1252"))))
    @Test fun default_utf8() = assertEquals("Borodinó", TextDecoding.decode("Borodinó".encodeToByteArray()))
    @Test fun exact_decoding_refuses_what_decode_would_garble() {
        assertEquals("Borodinó", TextDecoding.decodeExact("Borodinó".encodeToByteArray()))
        assertEquals("<?xml version=\"1.0\" encoding=\"windows-1252\"?>“x”",
            TextDecoding.decodeExact("<?xml version=\"1.0\" encoding=\"windows-1252\"?>“x”".toByteArray(charset("windows-1252"))))
        assertNull(TextDecoding.decodeExact("<?xml version=\"1.0\" encoding=\"Shift_JIS\"?>日本".toByteArray(charset("Shift_JIS"))))
        assertNull(TextDecoding.decodeExact("Caf\u00e9".toByteArray(Charsets.ISO_8859_1)))   // undeclared, not UTF-8
    }
}
