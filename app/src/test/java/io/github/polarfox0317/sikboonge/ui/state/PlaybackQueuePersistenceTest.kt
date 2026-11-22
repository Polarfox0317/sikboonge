package io.github.polarfox0317.sikboonge.ui.state

import io.github.polarfox0317.sikboonge.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun botRow(messageId: Long, fileId: Int? = null) = QueueRow(
    key = "bot:7:$messageId",
    title = "봇 영상 $messageId",
    sourceKind = PlaybackSource.BOT,
    botMessageId = messageId,
    botChatId = 7L,
    botFileId = fileId,
    artworkPath = "/thumb/$messageId.jpg",
    entryId = "entry-bot-$messageId",
)

private fun localRow(path: String) = QueueRow(
    key = "local:$path",
    title = "보관함 항목",
    sourceKind = PlaybackSource.LOCAL_AUDIO,
    localAudioPath = path,
    entryId = "entry-local-$path",
)

private fun session(rows: List<QueueRow> = listOf(botRow(1), localRow("/data/a.m4a"))) = PlaybackQueueSnapshot(
    rows = rows,
    currentKey = rows[1].key,
    resumeKey = rows[0].key,
    pausedKey = rows[1].key,
    pausedPositionMs = 42_000L,
    pausedDurationMs = 180_000L,
    playerExpanded = false,
    emptyPlayerVisible = true,
    currentEntryId = rows[1].entryId,
    resumeEntryId = rows[0].entryId,
    pausedEntryId = rows[1].entryId,
)

class PlaybackQueueSnapshotCodecTest {
    // The whole resumable session survives a process death without a Bundle.
    @Test fun roundTripsEveryResumableField() {
        val saved = session()
        val encoded = PlaybackQueueSnapshotCodec.encode(saved)
        val decoded = PlaybackQueueSnapshotCodec.decode(encoded)
        assertEquals(saved, decoded)
    }

    // Artwork is referenced by path only; a large Base64 blob is never written.
    @Test fun neverEncodesArtworkPayload() {
        val withArtwork = session().let { it.copy(rows = it.rows.map { row -> row.copy(artworkBase64 = "Q".repeat(200_000)) }) }
        val encoded = PlaybackQueueSnapshotCodec.encode(withArtwork)
        assertTrue("Payload grew beyond a small descriptor: ${encoded.length}", encoded.length <= 4_096)
        // The path survives; the inline payload is not part of the checkpoint at all.
        val decoded = requireNotNull(PlaybackQueueSnapshotCodec.decode(encoded))
        assertEquals("/thumb/1.jpg", decoded.rows[0].artworkPath)
        assertTrue(decoded.rows.all { it.artworkBase64.isEmpty() })
    }

    @Test fun keepsRowOrderAndIdentity() {
        val rows = listOf(botRow(3), botRow(1), localRow("/data/z.m4a"), localRow("/data/a.m4a"))
        val decoded = requireNotNull(PlaybackQueueSnapshotCodec.decode(PlaybackQueueSnapshotCodec.encode(session(rows))))
        assertEquals(rows.map { it.key }, decoded.rows.map { it.key })
        assertEquals(rows.map { it.title }, decoded.rows.map { it.title })
    }

    // A row that never carried a handle decodes as "no handle", not as a fake one.
    @Test fun missingHandlesStayAbsent() {
        val decoded = requireNotNull(PlaybackQueueSnapshotCodec.decode(PlaybackQueueSnapshotCodec.encode(session())))
        assertNull(decoded.rows[0].botFileId)
        assertEquals(7L, decoded.rows[0].botChatId)
        assertNull(decoded.rows[1].botMessageId)
        assertEquals("/data/a.m4a", decoded.rows[1].localAudioPath)
    }

    // Corrupt or hostile payloads are rejected instead of throwing into restore.
    @Test fun malformedPayloadsAreRejectedSafely() {
        assertNull(PlaybackQueueSnapshotCodec.decode(""))
        assertNull(PlaybackQueueSnapshotCodec.decode("not-base64!!"))
        assertNull(PlaybackQueueSnapshotCodec.decode("AAAA"))
        // Truncated real payload.
        val encoded = PlaybackQueueSnapshotCodec.encode(session())
        assertNull(PlaybackQueueSnapshotCodec.decode(encoded.take(encoded.length / 2)))
        // Trailing garbage after a complete payload.
        assertNull(PlaybackQueueSnapshotCodec.decode(encoded + "AAAA"))
    }

    @Test fun unknownSourceKindIsRejected() {
        val tampered = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            byteArrayOf(0, 0, 0, 2, 0, 0, 0, 1) + serializedRow(source = "SOMETHING_ELSE"),
        )
        assertNull(PlaybackQueueSnapshotCodec.decode(tampered))
    }

    // A version 1 checkpoint (media keys only, no occurrence ids) still restores.
    @Test fun versionOneCheckpointStillDecodes() {
        val v1 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            java.io.ByteArrayOutputStream().use { buffer ->
                java.io.DataOutputStream(buffer).use { out ->
                    out.writeInt(1)
                    out.writeInt(2)
                    for (id in listOf(1L, 2L)) {
                        out.writeUTF("bot:7:$id"); out.writeUTF("봇 $id"); out.writeUTF("BOT")
                        out.writeBoolean(false); out.writeLong(id); out.writeLong(7L); out.writeInt(-1)
                        out.writeBoolean(false)
                    }
                    out.writeBoolean(true); out.writeUTF("bot:7:2")
                    out.writeBoolean(true); out.writeUTF("bot:7:1")
                    out.writeBoolean(true); out.writeUTF("bot:7:2")
                    out.writeLong(1_000L); out.writeLong(2_000L)
                    out.writeBoolean(true); out.writeBoolean(false)
                }
                buffer.toByteArray()
            },
        )
        val decoded = requireNotNull(PlaybackQueueSnapshotCodec.decode(v1))
        assertEquals(listOf("bot:7:1", "bot:7:2"), decoded.rows.map { it.key })
        // Each legacy row gets a deterministic id from its position, and the media keys resolve to it.
        assertEquals("legacy-0", decoded.rows[0].entryId)
        assertEquals("legacy-1", decoded.rows[1].entryId)
        assertEquals("legacy-1", decoded.pausedEntryId)
        assertEquals("legacy-0", decoded.resumeEntryId)
    }

    // Two queued copies of one media restore the exact occurrence that was current/paused.
    @Test fun roundTripsTheSelectedOccurrenceOfADuplicate() {
        val first = localRow("/data/a.m4a").copy(entryId = "entry-a1")
        val duplicate = localRow("/data/a.m4a").copy(entryId = "entry-a2")
        val saved = PlaybackQueueSnapshot(
            rows = listOf(first, duplicate, botRow(1)),
            currentKey = duplicate.key, resumeKey = duplicate.key, pausedKey = duplicate.key,
            pausedPositionMs = 7_000L, pausedDurationMs = 60_000L,
            playerExpanded = false, emptyPlayerVisible = true,
            currentEntryId = "entry-a2", resumeEntryId = "entry-a2", pausedEntryId = "entry-a2",
        )
        val decoded = requireNotNull(PlaybackQueueSnapshotCodec.decode(PlaybackQueueSnapshotCodec.encode(saved)))
        assertEquals(saved, decoded)
        assertEquals(listOf("entry-a1", "entry-a2"), decoded.rows.take(2).map { it.entryId })
        assertEquals("entry-a2", decoded.pausedEntryId)
    }

    private fun serializedRow(source: String): ByteArray = java.io.ByteArrayOutputStream().use { buffer ->
        java.io.DataOutputStream(buffer).use { out ->
            out.writeUTF("entry-serialized")
            out.writeUTF("bot:7:9"); out.writeUTF("제목"); out.writeUTF(source)
            out.writeBoolean(false); out.writeLong(9L); out.writeLong(7L); out.writeInt(90)
            out.writeBoolean(false)
        }
        buffer.toByteArray()
    }
}

/**
 * Restore, pause and dismiss policy over a checkpoint: restoring never starts playback, and an
 * explicit close leaves nothing that a later pass could rewrite.
 */
class PlaybackCheckpointPolicyTest {
    // Restore represents the session as paused rows; the current entry is the one that was stopped.
    @Test fun restoreKeepsCurrentRowAndStoppedPosition() {
        val saved = session()
        val rows = saved.rows.map {
            io.github.polarfox0317.sikboonge.ui.state.QueueItemUi(
                key = it.key, title = it.title, sourceKind = it.sourceKind,
                localAudioPath = it.localAudioPath, botMessageId = it.botMessageId,
                botChatId = it.botChatId, botFileId = it.botFileId, artworkPath = it.artworkPath,
            )
        }
        val index = restoreResumeIndex(rows, saved.currentKey, saved.resumeKey, fallback = 0)
        assertEquals(1, index)
        assertEquals(saved.currentKey, rows[index].key)
        assertEquals(42_000L, saved.pausedPositionMs)
    }

    // A deleted row falls back to the saved resume entry instead of a wrong neighbour.
    @Test fun removedCurrentRowFallsBackToResumeKey() {
        val saved = session()
        val remaining = saved.rows.filterNot { it.key == saved.currentKey }.map { row ->
            io.github.polarfox0317.sikboonge.ui.state.QueueItemUi(key = row.key, title = row.title, sourceKind = row.sourceKind)
        }
        assertEquals(0, restoreResumeIndex(remaining, saved.currentKey, saved.resumeKey, fallback = 1))
    }

    // A loaded owner that no longer lists a row must NOT delete it: only an explicit delete may.
    @Test fun unresolvedBotRowsStayPendingEvenAfterTheOwnerLoads() {
        val rows = listOf(botRow(1), localRow("/data/a.m4a"))
        val pending = resolveRestoreQueue(rows, ownerLoaded = { it == PlaybackSource.LOCAL_AUDIO }, live = { null })
        assertTrue(pending[0] is RestoredRow.Pending)
        assertTrue(pending[1] is RestoredRow.Pending)
        // A transient empty/partial owner emission must not silently drop saved rows.
        val loaded = resolveRestoreQueue(rows, ownerLoaded = { true }, live = { null })
        assertTrue(loaded.all { it is RestoredRow.Pending })
        assertEquals(rows.size, loaded.toQueueEntries().size)
    }

    // Saved order is authoritative even when the owner publishes rows in another order.
    @Test fun savedOrderSurvivesOwnerOrder() {
        val rows = listOf(botRow(3), botRow(1))
        val resolved = resolveRestoreQueue(
            rows,
            ownerLoaded = { false },
            live = { row -> io.github.polarfox0317.sikboonge.ui.state.QueueItemUi(key = row.key, title = row.title, sourceKind = row.sourceKind) },
        )
        assertEquals(rows.map { it.key }, resolved.map { (it as RestoredRow.Live).item.key })
    }

    // Closing the player keeps the durable queue: only explicit deletion removes an entry.
    @Test fun closingThePlayerKeepsTheSavedQueue() {
        val saved = session()
        val restored = PlaybackQueueSnapshotCodec.decode(PlaybackQueueSnapshotCodec.encode(saved))!!
        assertEquals(saved.rows.map { it.key }, restored.rows.map { it.key })
        assertEquals(saved.pausedKey, restored.pausedKey)
        assertEquals(saved.pausedPositionMs, restored.pausedPositionMs)
    }
}
