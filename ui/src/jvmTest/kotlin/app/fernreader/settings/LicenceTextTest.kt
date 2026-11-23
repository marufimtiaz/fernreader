package app.fernreader.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class LicenceTextTest {
    @Test fun headings_paragraphs_and_table_rows() {
        val md = """
            # Third-party licences

            All runtime dependencies are permissive
            (Apache-2.0 / MIT). `fdroid` is clean.

            ## Gradle catalog artifacts

            | Artifact | Version | Licence | Used in |
            |---|---|---|---|
            | androidx.room:room-runtime | 2.8.4 | Apache-2.0 | core:data |
        """.trimIndent()
        assertEquals(
            listOf(
                LicenceBlock.Heading("Third-party licences", 1),
                LicenceBlock.Paragraph("All runtime dependencies are permissive (Apache-2.0 / MIT). fdroid is clean."),
                LicenceBlock.Heading("Gradle catalog artifacts", 2),
                LicenceBlock.Row(listOf("androidx.room:room-runtime", "2.8.4", "Apache-2.0", "core:data"), header = listOf("Artifact", "Version", "Licence", "Used in")),
            ),
            LicenceText.parse(md),
        )
    }
}
