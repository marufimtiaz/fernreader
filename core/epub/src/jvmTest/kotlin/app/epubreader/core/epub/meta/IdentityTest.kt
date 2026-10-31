package app.epubreader.core.epub.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IdentityTest {
    private val d1 = "a".repeat(64); private val d2 = "b".repeat(64)

    @Test fun format() = assertEquals(49, BookIdentity.of("urn:uuid:1", d1).length) // 16 + '-' + 32
    @Test fun same_inputs_same_identity() = assertEquals(BookIdentity.of("x", d1), BookIdentity.of("x", d1))
    @Test fun identifier_or_digest_change_identity() {
        assertNotEquals(BookIdentity.of("x", d1), BookIdentity.of("y", d1))
        assertNotEquals(BookIdentity.of("x", d1), BookIdentity.of("x", d2))
    }
    /** foliate reports a missing identifier as "" or null; both must give the same identity. */
    @Test fun null_and_empty_identifier_are_equal() = assertEquals(BookIdentity.of(null, d1), BookIdentity.of("", d1))
}
