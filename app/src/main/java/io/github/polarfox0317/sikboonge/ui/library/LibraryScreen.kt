package io.github.polarfox0317.sikboonge.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import io.github.polarfox0317.sikboonge.PlayerArtwork
import io.github.polarfox0317.sikboonge.R
import io.github.polarfox0317.sikboonge.TransientErrorBanner
import io.github.polarfox0317.sikboonge.UserFacingErrors
import io.github.polarfox0317.sikboonge.AudioResult
import io.github.polarfox0317.sikboonge.LibraryDownload
import io.github.polarfox0317.sikboonge.canonicalAudioPath
import io.github.polarfox0317.sikboonge.ui.components.ConfirmDeleteDialog
import io.github.polarfox0317.sikboonge.ui.components.DownloadProgressCard
import io.github.polarfox0317.sikboonge.ui.components.EmptyState
import io.github.polarfox0317.sikboonge.ui.components.ListControls
import io.github.polarfox0317.sikboonge.ui.components.MediaCard
import io.github.polarfox0317.sikboonge.ui.components.MediaCardActions
import io.github.polarfox0317.sikboonge.ui.components.MediaMenuItem
import io.github.polarfox0317.sikboonge.ui.components.MediaTile
import io.github.polarfox0317.sikboonge.ui.components.RenameDialog
import io.github.polarfox0317.sikboonge.ui.components.ScrollJumpBubbles
import io.github.polarfox0317.sikboonge.ui.components.TagEditorDialog
import io.github.polarfox0317.sikboonge.ui.state.AppActions
import io.github.polarfox0317.sikboonge.ui.state.AppUiState
import io.github.polarfox0317.sikboonge.ui.state.Destination
import io.github.polarfox0317.sikboonge.ui.state.DateRange
import io.github.polarfox0317.sikboonge.ui.state.ListLayout
import io.github.polarfox0317.sikboonge.ui.state.ListQuery
import io.github.polarfox0317.sikboonge.ui.state.SortKey
import io.github.polarfox0317.sikboonge.ui.state.matchesTagFilter
import io.github.polarfox0317.sikboonge.ui.state.matchesTextTerms
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens
import io.github.polarfox0317.sikboonge.ui.theme.WindowTier
import java.text.DateFormat
import java.text.Collator
import java.text.Normalizer
import java.util.Date
import java.util.Locale

@Composable
internal fun LibraryScreen(
    state: AppUiState,
    actions: AppActions,
    tier: WindowTier,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    reselectionToken: Int = 0,
    onScrollJump: (toTop: Boolean) -> Unit = {},
) {
    val gridState = rememberLazyGridState()
    val horizontalPadding = AppDimens.contentHorizontalPadding(tier)
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    val gridPadding = PaddingValues(
        start = contentPadding.calculateStartPadding(direction) + horizontalPadding,
        top = contentPadding.calculateTopPadding() + AppDimens.itemSpacing,
        end = contentPadding.calculateEndPadding(direction) + horizontalPadding,
        bottom = contentPadding.calculateBottomPadding() + AppDimens.itemSpacing,
    )
    var editingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var editingTagPath by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(reselectionToken) {
        if (reselectionToken != 0) gridState.scrollToItem(0)
    }
    val fontScale = LocalDensity.current.fontScale
    val minimumColumnWidth = when (state.libraryLayout) {
        ListLayout.List -> AppDimens.minimumGridColumnWidth(fontScale)
        ListLayout.Grid -> AppDimens.minimumTileWidth(fontScale)
    }
    val allLibraryRows = remember(state.history, state.downloads) {
        buildList {
            state.history.forEach { add(LibraryRow.Completed(it)) }
            state.downloads.forEach { add(LibraryRow.Pending(it)) }
        }
    }
    val visibleLibraryRows = remember(allLibraryRows, state.visibleHistory, state.libraryQuery) {
        val visibleCompletedPaths = state.visibleHistory.mapTo(hashSetOf()) { it.path }
        val nowMs = System.currentTimeMillis()
        allLibraryRows.filter { row ->
            when (row) {
                is LibraryRow.Completed -> row.result.path in visibleCompletedPaths
                is LibraryRow.Pending -> matchesPendingDownload(row.download, state.libraryQuery, nowMs)
            }
        }.sortLibraryRows(state.libraryQuery.sortKey, state.libraryQuery.ascending)
    }
    Box(modifier.fillMaxSize().consumeWindowInsets(contentPadding)) {
        LazyVerticalGrid(
        columns = GridCells.Adaptive(minimumColumnWidth),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = gridPadding,
        horizontalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
        verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
    ) {
        if (allLibraryRows.isNotEmpty()) {
            item(key = "listControls", span = { GridItemSpan(maxLineSpan) }) {
                ListControls(
                    query = state.libraryQuery,
                    onQueryChange = actions::onLibraryQueryChanged,
                    layout = state.libraryLayout,
                    onLayoutChange = { actions.onLayoutChanged(Destination.Library, it) },
                    placeholder = "\uBCF4\uAD00\uD568 \uAC80\uC0C9",
                    tagPrefix = "library",
                    modifier = Modifier.fillMaxWidth(),
                    tagVocabulary = state.libraryTagVocabulary,
                )
            }
        }
        if (allLibraryRows.isEmpty()) {
            item(key = "library_empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    title = "\uC544\uC9C1 \uBCF4\uAD00\uD55C \uC624\uB514\uC624\uAC00 \uC5C6\uC5B4\uC694.",
                    body = "\uC601\uC0C1\uC744 \uC120\uD0DD\uD574 \uCCAB \uC624\uB514\uC624\uB97C \uCD94\uCD9C\uD574 \uBCF4\uC138\uC694.",
                    modifier = Modifier.testTag("libraryEmpty"),
                    actionLabel = "\uC601\uC0C1 \uD30C\uC77C\uC5D0\uC11C \uC624\uB514\uC624 \uCD94\uCD9C",
                    onAction = { actions.onTopLevelSelected(Destination.Convert); actions.onPickVideo() },
                )
            }
        }
        if (allLibraryRows.isNotEmpty() && visibleLibraryRows.isEmpty()) {
            item(key = "library_no_results", span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag("libraryNoResults"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                ) {
                    EmptyState(title = "\uAC80\uC0C9 \uACB0\uACFC\uAC00 \uC5C6\uC5B4\uC694.", body = "\uAC80\uC0C9\uC5B4\uB098 \uD544\uD130\uB97C \uBC14\uAFD4 \uBCF4\uC138\uC694.")
                    TextButton(onClick = { actions.onLibraryQueryChanged(ListQuery()) }) {
                        Text("\uAC80\uC0C9\u00B7\uD544\uD130 \uCD08\uAE30\uD654")
                    }
                }
            }
        }
        items(visibleLibraryRows, key = { it.stableKey }) { row ->
            when (row) {
                is LibraryRow.Pending -> DownloadProgressCard(
                    download = row.download,
                    onCancel = actions::onCancelDownload,
                    onPause = actions::onPauseDownload,
                    onResume = actions::onResumeDownload,
                    useGridTile = state.libraryLayout == ListLayout.Grid,
                    modifier = Modifier.animateItem()
                        .fillMaxWidth()
                        .testTag("libraryDownload_${row.download.id}"),
                )
                is LibraryRow.Completed -> {
            val result = row.result
            val current = state.stream.localAudioPath?.let(::canonicalAudioPath) == canonicalAudioPath(result.path)
            val playing = current && state.stream.playing
            val playLabel = if (playing) "\uC77C\uC2DC\uC815\uC9C0" else if (current && state.stream.ended) "\uCC98\uC74C\uBD80\uD130 \uB4E3\uAE30" else "\uB4E3\uAE30"
            Column(
                modifier = Modifier.animateItem(),
                verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
            ) {
                val itemModifier = Modifier.fillMaxWidth()
                    .testTag(if (state.selectionMode) "librarySelect_${result.name}" else "libraryResult_${result.name}")
                    .semantics { if (state.selectionMode) contentDescription = "\uC624\uB514\uC624 \uC120\uD0DD: ${result.name}" }
                val menuItems = listOf(
                    MediaMenuItem("\uC7AC\uC0DD\uBAA9\uB85D\uC5D0 \uCD94\uAC00", R.drawable.ic_playlist_add, onClick = { actions.onAddToQueue(result) }, testTag = "libraryAddToQueue"),
                    MediaMenuItem("\uD30C\uC77C \uC774\uB984 \uC218\uC815", R.drawable.ic_media_edit, onClick = { editingPath = result.path }, testTag = "libraryTitleEdit_${result.name}"),
                    MediaMenuItem("\uD0DC\uADF8 \uD3B8\uC9D1", R.drawable.ic_tag, onClick = { editingTagPath = result.path }, testTag = "libraryTagEdit_${result.name}"),
                    MediaMenuItem("\uB2E4\uB978 \uC571\uC73C\uB85C \uC7AC\uC0DD", R.drawable.ic_media_external, onClick = { actions.onExternalPlayAudio(result) }, testTag = "audioExternalPlay"),
                    MediaMenuItem("\uACF5\uC720", R.drawable.ic_media_share, onClick = { actions.onShareAudio(result) }, testTag = "libraryShare"),
                    MediaMenuItem(if (state.saving) "\uD30C\uC77C\uB85C \uC800\uC7A5 \uC911" else "\uD30C\uC77C\uB85C \uC800\uC7A5", R.drawable.ic_media_save, enabled = !state.saving, onClick = { actions.onSaveAudioToDevice(result) }, testTag = "librarySave"),
                    MediaMenuItem("\uBCF4\uAD00\uD568\uC5D0\uC11C \uC0AD\uC81C", R.drawable.ic_media_delete, destructive = true, onClick = { actions.onRequestLibraryDelete(listOf(result)) }, testTag = "libraryDelete_${result.name}"),
                )
                val itemActions: @Composable (Boolean) -> Unit = { compact ->
                    MediaCardActions(
                        compact = compact,
                        onListen = { actions.onPlayAudio(result) },
                        listenEnabled = true,
                        listenLabel = playLabel,
                        listenIconRes = if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                        modifier = Modifier.fillMaxWidth().testTag("libraryCardActions"),
                        listenModifier = Modifier.testTag("libraryPlay").semantics { contentDescription = playLabel },
                        moreModifier = Modifier.testTag("libraryMenu_${result.name}"),
                        menuModifier = Modifier.testTag("libraryMenuContent_${result.name}"),
                        menuItems = menuItems,
                    )
                }
                val itemMeta = "%.1f MB · %s".format(result.bytes / 1048576.0, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(result.date)))
                val itemThumbnail: @Composable (Modifier) -> Unit = { PlayerArtwork(result.artworkPath, "", it) }
                Box(if (state.selectionMode) Modifier.testTag("libraryResult_${result.name}") else Modifier) {
                    if (state.libraryLayout == ListLayout.Grid) {
                        MediaTile(
                            title = result.name,
                            meta = itemMeta,
                            selected = result.path in state.selectedAudioPaths,
                            selectionMode = state.selectionMode,
                            playing = playing,
                            onClick = { if (state.selectionMode) actions.onToggleAudioSelection(result.path) },
                            onLongClick = { if (!state.selectionMode) actions.onBeginSelection(); actions.onToggleAudioSelection(result.path) },
                            thumbnail = itemThumbnail,
                            actions = { itemActions(true) },
                            modifier = itemModifier,
                            tags = result.tags,
                        )
                    } else {
                        MediaCard(
                            title = result.name,
                            meta = itemMeta,
                            selected = result.path in state.selectedAudioPaths,
                            selectionMode = state.selectionMode,
                            playing = playing,
                            onClick = { if (state.selectionMode) actions.onToggleAudioSelection(result.path) },
                            onLongClick = { if (!state.selectionMode) actions.onBeginSelection(); actions.onToggleAudioSelection(result.path) },
                            thumbnail = itemThumbnail,
                            actions = { itemActions(false) },
                            modifier = itemModifier,
                            tags = result.tags,
                        )
                    }
                }
                if (current && state.stream.error != null) {
                    TransientErrorBanner(state.stream.error, UserFacingErrors.Context.LOCAL_MEDIA, testTag = "localPlaybackError")
                }
            }
                }
            }
        }
        }
        ScrollJumpBubbles(
            state = gridState,
            enabled = !state.selectionMode,
            onJump = onScrollJump,
            modifier = Modifier.align(Alignment.BottomEnd).padding(
                end = contentPadding.calculateEndPadding(direction) + 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
        )
    }
    state.pendingLibraryDelete?.takeIf { it.isNotEmpty() }?.let { targets ->
        ConfirmDeleteDialog(targets.size, { actions.onConfirmLibraryDelete(targets) }, { actions.onRequestLibraryDelete(emptyList()) })
    }
    state.history.firstOrNull { it.path == editingPath }?.let { result ->
        val dot = result.name.lastIndexOf('.')
        val extension = if (dot > 0 && dot < result.name.lastIndex) result.name.substring(dot + 1) else ""
        val stem = if (extension.isBlank()) result.name else result.name.substring(0, dot)
        RenameDialog(
            initial = stem,
            maxLength = Int.MAX_VALUE,
            onConfirm = { actions.onRenameAudio(result, it); editingPath = null },
            onDismiss = { editingPath = null },
            bot = false,
            extension = extension,
        )
    }
    state.history.firstOrNull { it.path == editingTagPath }?.let { result ->
        TagEditorDialog(
            initial = result.tags,
            suggestions = state.allTagVocabulary,
            onConfirm = { actions.onSetAudioTags(result, it); editingTagPath = null },
            onDismiss = { editingTagPath = null },
            testTagPrefix = "library",
        )
    }
}

private sealed interface LibraryRow {
    val stableKey: String

    data class Completed(val result: AudioResult) : LibraryRow {
        override val stableKey: String = "library-audio:${result.path}"
    }

    data class Pending(val download: LibraryDownload) : LibraryRow {
        override val stableKey: String = "library-download:${download.id}"
    }
}

private fun matchesPendingDownload(
    download: LibraryDownload,
    query: ListQuery,
    nowMs: Long,
): Boolean {
    if (!matchesTextTerms(download.name, emptyList(), query.text)) return false
    if (!matchesTagFilter(emptyList(), query.tags)) return false
    return when (query.range) {
        DateRange.All -> true
        DateRange.Week -> download.date in (nowMs - 7L * 86_400_000L)..nowMs
        DateRange.Month -> download.date in (nowMs - 30L * 86_400_000L)..nowMs
    }
}

private fun List<LibraryRow>.sortLibraryRows(sortKey: SortKey, ascending: Boolean): List<LibraryRow> =
    when (sortKey) {
        SortKey.Date -> if (ascending) sortedBy { it.date } else sortedByDescending { it.date }
        SortKey.Name -> {
            val collator = Collator.getInstance(Locale.KOREAN)
            val comparator = Comparator<LibraryRow> { first, second ->
                collator.compare(
                    Normalizer.normalize(first.name, Normalizer.Form.NFC),
                    Normalizer.normalize(second.name, Normalizer.Form.NFC),
                )
            }
            sortedWith(if (ascending) comparator else comparator.reversed())
        }
    }

private val LibraryRow.name: String
    get() = when (this) {
        is LibraryRow.Completed -> result.name
        is LibraryRow.Pending -> download.name
    }

private val LibraryRow.date: Long
    get() = when (this) {
        is LibraryRow.Completed -> result.date
        is LibraryRow.Pending -> download.date
    }
