package io.github.polarfox0317.sikboonge.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.polarfox0317.sikboonge.R
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens
import io.github.polarfox0317.sikboonge.update.AppChangelog
import io.github.polarfox0317.sikboonge.update.AppRelease
import io.github.polarfox0317.sikboonge.update.ReleaseNoteBlock
import io.github.polarfox0317.sikboonge.update.ReleaseNotesContent
import io.github.polarfox0317.sikboonge.update.bundledChangelog
import io.github.polarfox0317.sikboonge.update.parseReleaseNotes

@Composable
internal fun UpdateAvailableDialog(
    release: AppRelease,
    installedVersion: String,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onSkip: () -> Unit,
) {
    val notes = releaseNotes(release.notes, release.title, release.version, release.releaseUrl)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = {
            ReleaseNotesBody(
                versionLine = stringResource(R.string.update_version_transition, installedVersion, release.version),
                content = notes,
                onSkip = onSkip,
                showSkip = true,
            )
        },
        confirmButton = { TextButton(onClick = onUpdate) { Text(stringResource(R.string.update_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) } },
    )
}

@Composable
internal fun UpdateCompletedDialog(changelog: AppChangelog, onDismiss: () -> Unit) {
    val isEnglish = LocalConfiguration.current.locales[0]?.language?.startsWith("en") == true
    val blocks = when {
        changelog.versionCode in setOf(125L, 132L) ->
            bundledChangelog(changelog.versionCode, if (isEnglish) "en" else "ko") ?: changelog.notes
        isEnglish && changelog.versionCode in setOf(109L, 110L) -> listOf(
            ReleaseNoteBlock.Item(stringResource(R.string.changelog_note_connected_search)),
            ReleaseNoteBlock.Item(stringResource(R.string.changelog_note_offline_list)),
            ReleaseNoteBlock.Item(stringResource(R.string.changelog_note_bot_audio)),
        )
        isEnglish && changelog.versionCode == 111L -> listOf(
            ReleaseNoteBlock.Heading(stringResource(R.string.changelog_1_0_2_downloads_heading)),
            ReleaseNoteBlock.Item(stringResource(R.string.changelog_1_0_2_downloads_library), depth = 1),
            ReleaseNoteBlock.Item(stringResource(R.string.changelog_1_0_2_downloads_metrics), depth = 1),
            ReleaseNoteBlock.Item(stringResource(R.string.changelog_1_0_2_downloads_cancel), depth = 1),
            ReleaseNoteBlock.Item(stringResource(R.string.changelog_1_0_2_downloads_complete), depth = 1),
            ReleaseNoteBlock.Heading(stringResource(R.string.changelog_1_0_2_updates_heading)),
            ReleaseNoteBlock.Item(stringResource(R.string.changelog_1_0_2_updates_language), depth = 1),
        )
        else -> changelog.notes
    }
    val content = remember(changelog.versionCode, blocks, changelog.releaseUrl) {
        ReleaseNotesContent(
            blocks = blocks,
            releaseUrl = changelog.releaseUrl,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_completed_title)) },
        text = {
            ReleaseNotesBody(
                versionLine = stringResource(R.string.update_version_name, changelog.versionName),
                content = content,
                showSkip = false,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_confirm)) } },
    )
}

/** Shared by update prompts, the post-update changelog, and Settings. */
@Composable
internal fun ReleaseNotesBody(
    versionLine: String?,
    content: ReleaseNotesContent,
    modifier: Modifier = Modifier,
    onSkip: (() -> Unit)? = null,
    showSkip: Boolean = false,
    scrollNotes: Boolean = true,
) {
    if (scrollNotes) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val hasBoundedHeight = maxHeight != Dp.Infinity
            Column(
                modifier = if (hasBoundedHeight) Modifier.heightIn(max = maxHeight) else Modifier,
                verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
            ) {
                versionLine?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                if (hasBoundedHeight) {
                    Column(
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                    ) { ReleaseNoteRows(content) }
                } else ReleaseNoteRows(content)
                ReleaseNotesFooter(content.releaseUrl, onSkip, showSkip)
            }
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing)) {
            versionLine?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            ReleaseNoteRows(content)
            ReleaseNotesFooter(content.releaseUrl, onSkip, showSkip)
        }
    }
}

@Composable
private fun ReleaseNoteRows(content: ReleaseNotesContent) {
    Column(verticalArrangement = Arrangement.spacedBy(AppDimens.listItemSpacing)) {
        if (content.blocks.isEmpty()) {
            Text(stringResource(R.string.update_notes_empty), style = MaterialTheme.typography.bodyMedium)
        } else content.blocks.forEach { block ->
            when (block) {
                is ReleaseNoteBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                is ReleaseNoteBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
                is ReleaseNoteBlock.Item -> Row(
                    modifier = Modifier.fillMaxWidth().padding(start = AppDimens.itemSpacing * block.depth),
                    horizontalArrangement = Arrangement.spacedBy(AppDimens.listItemSpacing),
                ) {
                    Text(block.marker, style = MaterialTheme.typography.bodyMedium)
                    Text(block.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotesFooter(
    releaseUrl: String?,
    onSkip: (() -> Unit)?,
    showSkip: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing)) {
        releaseUrl?.let { url ->
            val label = stringResource(R.string.update_full_release)
            val link = buildAnnotatedString {
                withLink(
                    LinkAnnotation.Url(
                        url,
                        TextLinkStyles(
                            SpanStyle(
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                            )
                        ),
                    )
                ) { append(label) }
            }
            Text(link, style = MaterialTheme.typography.bodyMedium)
        }
        if (showSkip && onSkip != null) {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.update_skip_version)) }
        }
    }
}

@Composable
internal fun releaseNotes(body: String, title: String, version: String, url: String): ReleaseNotesContent {
    val configuration = LocalConfiguration.current
    val language = configuration.locales[0]?.language
    return remember(body, language, title, version, url) {
        parseReleaseNotes(body, language, title, version, url)
    }
}
