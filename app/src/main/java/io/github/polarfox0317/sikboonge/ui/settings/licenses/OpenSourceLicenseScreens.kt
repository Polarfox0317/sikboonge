package io.github.polarfox0317.sikboonge.ui.settings.licenses

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.polarfox0317.sikboonge.R
import io.github.polarfox0317.sikboonge.ui.components.CenteredLoading
import io.github.polarfox0317.sikboonge.ui.components.SectionCard
import io.github.polarfox0317.sikboonge.ui.shell.LocalUpdateController
import io.github.polarfox0317.sikboonge.ui.theme.AppDimens
import io.github.polarfox0317.sikboonge.ui.theme.rememberWindowTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LICENSES_INTRO =
    "싴붕이는 다음 오픈소스 소프트웨어를 사용해요. 각 항목에서 저작권 고지와 라이선스 원문을 확인할 수 있어요."

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun OpenSourceLicenseListScreen(
    onOpenLicense: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val horizontalPadding = AppDimens.contentHorizontalPadding(rememberWindowTier())
    val currentAppVersion = LocalUpdateController.current.currentVersionName
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = AppDimens.maxFormWidth).fillMaxWidth(),
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item(key = "license-intro") {
                Text(
                    LICENSES_INTRO,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = horizontalPadding, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(OpenSourceLicenses.entries, key = { _, entry -> entry.id }) { index, entry ->
                val version = if (entry.id == "sikboonge") currentAppVersion else entry.version
                SegmentedListItem(
                    onClick = { onOpenLicense(entry.id) },
                    shapes = ListItemDefaults.segmentedShapes(index, OpenSourceLicenses.entries.size),
                    // app choice: match filled Card container used by SectionCard
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)
                        .testTag("license_${entry.id}"),
                    content = { Text(entry.name) },
                    supportingContent = { Text("$version · ${entry.licenseLabel}") },
                    trailingContent = {
                        Icon(
                            painter = painterResource(R.drawable.ic_keyboard_arrow_down),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp).graphicsLayer(rotationZ = -90f),
                        )
                    },
                )
            }
            item(key = "license-list-bottom-spacing") {
                Spacer(Modifier.size(16.dp))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun OpenSourceLicenseDetailScreen(
    entry: OpenSourceLicense,
    onFeedback: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    val version = if (entry.id == "sikboonge") {
        LocalUpdateController.current.currentVersionName
    } else {
        entry.version
    }
    val horizontalPadding = AppDimens.contentHorizontalPadding(rememberWindowTier())
    var retryKey by rememberSaveable(entry.id) { mutableIntStateOf(0) }
    var documents by remember(entry.id) { mutableStateOf<List<LoadedLicenseDocument>?>(null) }
    var loadError by remember(entry.id) { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    LaunchedEffect(entry.id, retryKey) {
        documents = null
        loadError = false
        val result = try {
            Result.success(withContext(Dispatchers.IO) {
                entry.documents.map { document ->
                    LoadedLicenseDocument(document.title, readLicenseDocument(context, document))
                }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        result.onSuccess { documents = it }.onFailure {
            loadError = true
            onFeedback("라이선스 원문을 불러오지 못했어요.", true)
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (documents == null) {
            Box(
                modifier = Modifier.widthIn(max = AppDimens.maxFormWidth).fillMaxWidth()
                    .fillMaxHeight().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                if (loadError) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppDimens.itemSpacing),
                    ) {
                        Text("라이선스 원문을 불러오지 못했어요.", color = MaterialTheme.colorScheme.error)
                        TextButton(
                            onClick = { retryKey++ },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("다시 시도") }
                    }
                } else {
                    CenteredLoading(label = "불러오는 중")
                }
            }
        } else {
            Column(
                modifier = Modifier.widthIn(max = AppDimens.maxFormWidth).fillMaxWidth()
                    .padding(contentPadding)
                    .verticalScroll(scrollState)
                    .padding(horizontal = horizontalPadding, vertical = AppDimens.sectionSpacing),
                verticalArrangement = Arrangement.spacedBy(AppDimens.sectionSpacing),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.headlineSmall)
                    Text("$version · ${entry.licenseLabel}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        entry.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionCard(
                    title = { Text("저작권 고지") },
                    body = {
                        SelectionContainer {
                            Text(entry.copyrightNotice)
                        }
                    },
                )

                if (entry.components.isNotEmpty()) {
                    SectionCard(
                        title = { Text("포함된 구성 요소") },
                        body = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                entry.components.forEach { component ->
                                    SelectionContainer {
                                        Text(component)
                                    }
                                }
                            }
                        },
                    )
                }

                if (entry.sources.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("출처", style = MaterialTheme.typography.titleMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                            entry.sources.forEachIndexed { index, source ->
                                SegmentedListItem(
                                    onClick = {
                                        try {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(source.url)),
                                            )
                                        } catch (_: ActivityNotFoundException) {
                                            onFeedback("열 수 있는 브라우저가 없어요.", true)
                                        } catch (_: SecurityException) {
                                            onFeedback("브라우저를 열 수 없어요.", true)
                                        }
                                    },
                                    shapes = ListItemDefaults.segmentedShapes(index, entry.sources.size),
                                    // app choice: match filled Card container used by SectionCard
                                    colors = ListItemDefaults.segmentedColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    ),
                                    content = { Text(source.label) },
                                    supportingContent = { Text(source.url) },
                                    trailingContent = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_media_external),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                SectionCard(
                    title = { Text("라이선스 원문") },
                    body = {
                        if (documents!!.isEmpty()) {
                            Text("등록된 라이선스 원문이 없어요.")
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                documents!!.forEach { document ->
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(document.title, style = MaterialTheme.typography.titleSmall)
                                        SelectionContainer {
                                            Text(document.text)
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

private data class LoadedLicenseDocument(val title: String, val text: String)

internal fun readLicenseDocument(context: Context, document: LicenseDocument): String = when {
    document.assetPath != null -> context.assets.open(document.assetPath).use { input ->
        input.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    document.rawResourceName != null -> {
        val resourceId = context.resources.getIdentifier(document.rawResourceName, "raw", context.packageName)
        check(resourceId != 0) { "라이선스 원문 리소스를 찾을 수 없어요." }
        context.resources.openRawResource(resourceId).use { input ->
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
    else -> error("라이선스 원문 위치가 지정되지 않았어요.")
}
