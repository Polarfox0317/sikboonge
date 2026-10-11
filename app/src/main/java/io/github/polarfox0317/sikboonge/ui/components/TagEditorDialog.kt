package io.github.polarfox0317.sikboonge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.dp
import io.github.polarfox0317.sikboonge.R
import io.github.polarfox0317.sikboonge.TagPolicy

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagEditorDialog(
    initial: List<String>,
    suggestions: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier,
) {
    var draftTags by rememberSaveable(initial) { mutableStateOf(TagPolicy.normalizeTags(initial)) }
    var input by rememberSaveable(initial) { mutableStateOf("") }
    var duplicateAttempt by rememberSaveable(initial) { mutableStateOf(false) }
    val normalizedInitial = TagPolicy.normalizeTags(initial)
    val inputTag = TagPolicy.normalizeTag(input)
    val lengthCheck = input.trim().trimStart('#')
    val tooLong = inputTag == null && lengthCheck.codePointCount(0, lengthCheck.length) > TagPolicy.MAX_TAG_CHARS

    fun addTag(raw: String): Boolean {
        val normalized = TagPolicy.normalizeTag(raw) ?: return false
        if (draftTags.any { TagPolicy.key(it) == TagPolicy.key(normalized) }) {
            duplicateAttempt = true
            return false
        }
        if (draftTags.size >= TagPolicy.MAX_TAGS) return false
        draftTags = draftTags + normalized
        duplicateAttempt = false
        return true
    }

    fun commitInput() {
        if (input.isBlank()) {
            input = ""
            return
        }
        addTag(input)
        input = ""
    }

    val visibleSuggestions = suggestions
        .distinctBy(TagPolicy::key)
        .filter { suggestion -> draftTags.none { TagPolicy.key(it) == TagPolicy.key(suggestion) } }
        .filter { suggestion ->
            val filter = input.trim().removePrefix("#")
            filter.isEmpty() || suggestion.contains(filter, ignoreCase = true)
        }
        .take(12)
    val changed = draftTags != normalizedInitial || (inputTag != null &&
        draftTags.none { TagPolicy.key(it) == TagPolicy.key(inputTag) } && draftTags.size < TagPolicy.MAX_TAGS)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(testTagPrefix + "TagDialog"),
        title = { Text("태그 편집") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (draftTags.isEmpty()) {
                    Text("아직 태그가 없어요.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        draftTags.forEach { tag ->
                            InputChip(
                                selected = false,
                                onClick = { draftTags = draftTags.filterNot { it == tag }; duplicateAttempt = false },
                                label = { Text(tag) },
                                trailingIcon = {
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = "$tag 태그 삭제",
                                        modifier = Modifier.size(InputChipDefaults.IconSize))
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        duplicateAttempt = false
                        val separatorIndex = value.indexOfAny(charArrayOf(',', '，'))
                        if (separatorIndex >= 0) {
                            val committed = value.substring(0, separatorIndex)
                            addTag(committed)
                            input = value.substring(separatorIndex + 1)
                        } else {
                            input = value
                        }
                    },
                    label = { Text("새 태그") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitInput() }),
                    enabled = draftTags.size < TagPolicy.MAX_TAGS,
                    isError = tooLong,
                    supportingText = {
                        Text(when {
                            draftTags.size >= TagPolicy.MAX_TAGS -> "태그는 10개까지 넣을 수 있어요."
                            duplicateAttempt -> "이미 있는 태그예요."
                            tooLong -> "태그는 20자 이하로 입력해 주세요."
                            else -> "쉼표나 완료 키로 추가해요. 태그는 10개, 한 태그는 20자까지예요."
                        })
                    },
                    modifier = Modifier.fillMaxWidth().testTag(testTagPrefix + "TagInput"),
                )
                if (visibleSuggestions.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("추천 태그", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            visibleSuggestions.forEach { tag ->
                                SuggestionChip(
                                    onClick = { addTag(tag) },
                                    label = { Text(tag) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val result = inputTag?.let { pending ->
                        if (draftTags.size < TagPolicy.MAX_TAGS && draftTags.none { TagPolicy.key(it) == TagPolicy.key(pending) }) {
                            draftTags + pending
                        } else draftTags
                    } ?: draftTags
                    onConfirm(TagPolicy.normalizeTags(result))
                },
                enabled = changed,
                modifier = Modifier.testTag(testTagPrefix + "TagConfirm"),
            ) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(testTagPrefix + "TagCancel")) { Text("취소") }
        },
    )
}
