package io.github.polarfox0317.sikboonge.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineBreak

@Composable
internal fun ConfirmDeleteDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    bot: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(if (bot) "봇 목록 항목을 삭제할까요?" else if (count == 1) "오디오를 삭제할까요?" else "선택한 오디오를 삭제할까요?") },
        text = { Text(if (bot) "${count}개 봇 목록 항목을 삭제해요. Telegram 메시지는 유지돼요." else "${count}개 앱 내부 오디오 파일을 삭제해요. 다른 폴더에 저장한 사본은 유지돼요.") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag(if (bot) "confirmBotDelete" else "confirmLibraryDelete"),
            ) { Text(if (bot) "목록에서 삭제" else "삭제") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(if (bot) "cancelBotDelete" else "cancelLibraryDelete")) { Text("취소") }
        },
    )
}

@Composable
internal fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    bot: Boolean = true,
    extension: String = "",
) {
    val draftState = rememberSaveable(initial, bot, extension, saver = TextFieldState.Saver) { TextFieldState(initial) }
    var botDraft by rememberSaveable(initial, bot, extension, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial))
    }
    val draft = if (bot) botDraft.text else draftState.text.toString()
    val title = if (bot) draft.filterNot { Character.isISOControl(it) }.trim() else {
        val entered = draft.trim().trim('.')
        if (extension.isNotBlank() && entered.endsWith(".$extension", ignoreCase = true)) entered.dropLast(extension.length + 1) else entered
    }
    val valid = title.isNotBlank() && title != initial
    val prefix = if (bot) "botRename" else "libraryRename"
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(prefix + "Dialog"),
        title = { Text(if (bot) "영상 제목 수정" else "파일 이름 수정") },
        text = {
            if (bot) {
                // VisualTransformation leaves Foundation's IME, copy/paste and key movement
                // in the original title. OutputTransformation in Foundation 1.12 exposes
                // inserted wrapping characters to those paths, so it is unsuitable here.
                OutlinedTextField(
                    value = botDraft,
                    onValueChange = { botDraft = it },
                    label = { Text("영상 제목") },
                    minLines = 1,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    textStyle = LocalTextStyle.current.copy(
                        lineBreak = LineBreak.Simple.copy(strictness = LineBreak.Strictness.Loose),
                    ),
                    visualTransformation = GraphemeWrapVisualTransformation,
                    supportingText = { if (title.isBlank()) Text("제목을 입력해 주세요.") },
                    modifier = Modifier.fillMaxWidth().testTag(prefix + "Title"),
                )
            } else EdgeHoldOutlinedTextField(
                state = draftState,
                label = { Text("파일 이름") },
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                supportingText = {
                    if (extension.isNotBlank()) Text(".$extension 확장자는 유지돼요.")
                },
                modifier = Modifier.fillMaxWidth().testTag(prefix + "Title"),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onConfirm(title) }, enabled = valid, modifier = Modifier.testTag(prefix + "Confirm")) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(prefix + "Cancel")) { Text("취소") }
        },
    )
}

@Composable
internal fun DisconnectDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text("봇 연결을 초기화할까요?") },
        text = { Text("현재 연결을 끊거나 진행 중인 연결을 취소하고, 이 기기에 저장된 token과 API 정보를 삭제해요. 다시 연결하려면 세 값을 다시 입력해야 해요.") },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("삭제하고 연결 해제") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
