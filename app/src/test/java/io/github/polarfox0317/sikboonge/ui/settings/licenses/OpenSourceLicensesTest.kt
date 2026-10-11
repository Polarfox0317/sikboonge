package io.github.polarfox0317.sikboonge.ui.settings.licenses

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourceLicensesTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "licenses/RuntimeInventory.txt").isFile }

    @Test fun catalogIdsAndDocumentsAreUnambiguous() {
        val entries = OpenSourceLicenses.entries
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        entries.forEach { entry ->
            assertTrue("${entry.id} lacks original documents", entry.documents.isNotEmpty())
            assertTrue("${entry.id} lacks copyright", entry.copyrightNotice.isNotBlank())
            entry.documents.forEach { document ->
                assertTrue("${entry.id}: ambiguous document", (document.assetPath != null) xor (document.rawResourceName != null))
                document.assetPath?.let { path ->
                    val asset = File(root, "licenses/$path")
                    assertTrue("Missing $path", asset.isFile && asset.length() > 0)
                }
                document.rawResourceName?.let { name ->
                    val original = File(root, "artifacts/third-party-source/ffmpeg-kit-audio-8.1.7-corresponding-source/aar-notices/$name.txt")
                    assertTrue("Missing AAR original $name", original.isFile && original.length() > 0)
                }
            }
            entry.sources.forEach { source -> assertTrue(source.url.startsWith("https://")) }
        }
    }

    @Test fun selectedRuntimeCoordinatesAreCovered() {
        val expected = File(root, "licenses/RuntimeInventory.txt").readLines()
            .map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        val actual = OpenSourceLicenses.entries.flatMap { it.components }.toSet()
        assertTrue("Runtime inventory is unexpectedly empty", expected.size >= 100)
        assertTrue("Unlisted runtime dependencies: ${expected - actual}", actual.containsAll(expected))
        assertTrue(OpenSourceLicenses.entries.none { it.components.any { component ->
            component.startsWith("junit:") || component.startsWith("com.dropbox.dropshots:")
        } })
    }
}
