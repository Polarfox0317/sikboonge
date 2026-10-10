package io.github.polarfox0317.sikboonge

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppStoreRemovalTest {
    @Test fun selectedSubsetIsRemovedAndUnselectedHistoryFilesAndCurrentResultSurviveReload() = isolatedStore { context, audioDir ->
        val selected = createResult(audioDir, "selected.mp3")
        val currentUnselected = createResult(audioDir, "current.mp3")
        val otherUnselected = createResult(audioDir, "other.wav")
        listOf(selected, currentUnselected, otherUnselected).forEach { AppStore.add(context, it) }
        AppStore.state.value = ConversionState(result = currentUnselected)

        val removal = AppStore.removeMany(context, listOf(selected))

        assertEquals(1, removal.removedEntries)
        assertEquals(0, removal.failedFileDeletes)
        assertFalse(File(selected.path).exists())
        assertTrue(File(currentUnselected.path).isFile)
        assertTrue(File(otherUnselected.path).isFile)
        assertEquals(setOf(currentUnselected.path, otherUnselected.path), AppStore.history.value.map { it.path }.toSet())
        assertEquals(currentUnselected, AppStore.state.value.result)

        AppStore.load(context)
        assertEquals(setOf(currentUnselected.path, otherUnselected.path), AppStore.history.value.map { it.path }.toSet())
        assertEquals(currentUnselected, AppStore.state.value.result)
    }

    @Test fun invalidOutsidePathRejectsWholeRequestBeforeChangingFilesHistoryOrState() = isolatedStore { context, audioDir ->
        val valid = createResult(audioDir, "valid.mp3")
        val outsideDir = File(context.filesDir, "outside-audio").apply { mkdirs() }
        val outside = createResult(outsideDir, "outside.mp3")
        listOf(valid, outside).forEach { AppStore.add(context, it) }
        val previousHistory = AppStore.history.value.toList()
        val previousState = ConversionState(result = valid)
        AppStore.state.value = previousState

        var rejected = false
        try {
            AppStore.removeMany(context, listOf(valid, outside))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue("A request containing an outside path must be rejected", rejected)
        assertTrue(File(valid.path).isFile)
        assertTrue(File(outside.path).isFile)
        assertEquals(previousHistory, AppStore.history.value)
        assertEquals(previousState, AppStore.state.value)
        AppStore.load(context)
        assertEquals(previousHistory, AppStore.history.value)
    }

    @Test fun removingAllEntriesClearsMatchingCurrentResultAndReloadsEmpty() = isolatedStore { context, audioDir ->
        val first = createResult(audioDir, "first.mp3")
        val current = createResult(audioDir, "current.mp3")
        listOf(first, current).forEach { AppStore.add(context, it) }
        AppStore.state.value = ConversionState(result = current)

        val removal = AppStore.removeMany(context, listOf(first, current))

        assertEquals(2, removal.removedEntries)
        assertEquals(0, removal.failedFileDeletes)
        assertFalse(File(first.path).exists())
        assertFalse(File(current.path).exists())
        assertTrue(AppStore.history.value.isEmpty())
        assertNull(AppStore.state.value.result)
        assertFalse(AppStore.state.value.running)
        AppStore.load(context)
        assertTrue(AppStore.history.value.isEmpty())
    }

    private fun createResult(directory: File, name: String): AudioResult {
        val file = File(directory, name).apply { writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x01)) }
        return AudioResult(file.canonicalPath, name, "audio/mpeg", file.length(), 1L)
    }

    private inline fun isolatedStore(test: (Context, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheRoot = base.cacheDir.canonicalFile
        val uniqueRoot = File(cacheRoot, "appstore-removal-${UUID.randomUUID()}").canonicalFile
        require(uniqueRoot.path.startsWith(cacheRoot.path + File.separator))
        val filesRoot = File(uniqueRoot, "files").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = filesRoot
            override fun getApplicationContext(): Context = this
        }
        val audioDir = File(filesRoot, "audio").apply { mkdirs() }
        val previousHistory = AppStore.history.value
        val previousState = AppStore.state.value
        try {
            AppStore.history.value = emptyList()
            AppStore.state.value = ConversionState()
            test(context, audioDir)
        } finally {
            AppStore.history.value = previousHistory
            AppStore.state.value = previousState
            check(uniqueRoot.canonicalPath.startsWith(cacheRoot.path + File.separator))
            uniqueRoot.deleteRecursively()
        }
    }
}
