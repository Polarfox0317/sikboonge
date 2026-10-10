package io.github.polarfox0317.sikboonge

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppStoreRenameTest {
    @Test fun renamePersistsFileAndHistoryAndPreservesMetadataAndCurrentResult() = isolatedStore { context, audioDir ->
        val originalBytes = byteArrayOf(0x49, 0x44, 0x33, 0x02)
        val originalFile = File(audioDir, "original.m4a").apply { writeBytes(originalBytes) }
        val original = AudioResult(originalFile.canonicalPath, "original.m4a", "audio/mp4", originalBytes.size.toLong(), 1234L)
        AppStore.add(context, original)
        AppStore.state.value = ConversionState(running = true, stage = "완료", progress = 1f, result = original)

        val renamed = AppStore.rename(context, original, "renamed:/mix")

        assertEquals("renamed__mix.m4a", renamed.name)
        assertEquals(original.mime, renamed.mime)
        assertEquals(original.bytes, renamed.bytes)
        assertEquals(original.date, renamed.date)
        assertFalse(originalFile.exists())
        assertEquals(originalBytes.toList(), File(renamed.path).readBytes().toList())
        assertEquals(listOf(renamed), AppStore.history.value)
        assertEquals(renamed, AppStore.state.value.result)
        assertTrue(AppStore.state.value.running)

        AppStore.load(context)
        assertEquals(listOf(renamed), AppStore.history.value)
    }

    @Test fun renameUsesAvailableSuffixAndNeverReplacesExistingAudio() = isolatedStore { context, audioDir ->
        val original = createResult(audioDir, "source.wav", byteArrayOf(1, 2, 3))
        val occupied = createResult(audioDir, "Mix.wav", byteArrayOf(9, 8, 7))
        AppStore.add(context, original)
        AppStore.add(context, occupied)
        val occupiedBytes = File(occupied.path).readBytes().toList()

        val renamed = AppStore.rename(context, original, "Mix")

        assertEquals("Mix (2).wav", renamed.name)
        assertEquals(occupiedBytes, File(occupied.path).readBytes().toList())
        assertEquals(setOf(renamed.path, occupied.path), AppStore.history.value.map { it.path }.toSet())
    }

    @Test fun longKoreanAndEmojiTitleFitsFilesystemNameLimitAndReloads() = isolatedStore { context, audioDir ->
        val original = createResult(audioDir, "before.mp3", byteArrayOf(7, 8, 9))
        AppStore.add(context, original)
        val staleCallerResult = original.copy(name = "stale.mp3", mime = "audio/unknown", bytes = 999L, date = 0L)
        val longTitle = "한😀".repeat(80)

        val renamed = AppStore.rename(context, staleCallerResult, longTitle)

        assertTrue("Filename component exceeds 255 UTF-8 bytes", renamed.name.toByteArray(Charsets.UTF_8).size <= 255)
        assertEquals(original.mime, renamed.mime)
        assertEquals(original.bytes, renamed.bytes)
        assertEquals(original.date, renamed.date)
        AppStore.load(context)
        assertEquals(listOf(renamed), AppStore.history.value)
    }

    @Test fun blankTitleAndOutsideOrUnindexedFilesAreRejectedWithoutChangingStore() = isolatedStore { context, audioDir ->
        val indexed = createResult(audioDir, "indexed.mp3", byteArrayOf(1))
        val outsideDir = File(context.filesDir, "outside-audio").apply { mkdirs() }
        val outside = createResult(outsideDir, "outside.mp3", byteArrayOf(2))
        val unindexed = createResult(audioDir, "unindexed.mp3", byteArrayOf(3))
        AppStore.add(context, indexed)
        AppStore.add(context, outside)
        val oldHistory = AppStore.history.value.toList()

        assertThrows(IllegalArgumentException::class.java) { AppStore.rename(context, indexed, "  .  ") }
        assertThrows(IllegalArgumentException::class.java) { AppStore.rename(context, outside, "renamed") }
        assertThrows(IllegalArgumentException::class.java) { AppStore.rename(context, unindexed, "renamed") }

        assertTrue(File(indexed.path).isFile)
        assertTrue(File(outside.path).isFile)
        assertTrue(File(unindexed.path).isFile)
        assertEquals(oldHistory, AppStore.history.value)
    }

    @Test fun historyPersistenceFailureRollsBackFileRename() = isolatedStore { context, audioDir ->
        val original = createResult(audioDir, "before.mp3", byteArrayOf(4, 5, 6))
        AppStore.add(context, original)
        val previousHistory = AppStore.history.value.toList()
        // A directory at the temp-file path makes history persistence fail after the media move.
        assertTrue(File(context.filesDir, "history.tmp").mkdir())

        assertThrows(IllegalStateException::class.java) { AppStore.rename(context, original, "after") }

        assertTrue(File(original.path).isFile)
        assertFalse(File(audioDir, "after.mp3").exists())
        assertEquals(previousHistory, AppStore.history.value)
        AppStore.load(context)
        assertEquals(previousHistory, AppStore.history.value)
    }

    private fun createResult(directory: File, name: String, bytes: ByteArray): AudioResult {
        val file = File(directory, name).apply { writeBytes(bytes) }
        return AudioResult(file.canonicalPath, name, "audio/mpeg", file.length(), 55L)
    }

    private inline fun isolatedStore(test: (Context, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheRoot = base.cacheDir.canonicalFile
        val uniqueRoot = File(cacheRoot, "appstore-rename-${UUID.randomUUID()}").canonicalFile
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
