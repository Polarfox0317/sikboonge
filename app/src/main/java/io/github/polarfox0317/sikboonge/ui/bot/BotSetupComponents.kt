package io.github.polarfox0317.sikboonge.ui.bot

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.polarfox0317.sikboonge.BotCredentials
import io.github.polarfox0317.sikboonge.BotUiState
import io.github.polarfox0317.sikboonge.ui.state.AppActions
import io.github.polarfox0317.sikboonge.ui.state.AppUiState

@Composable
internal fun BotTokenField(
    state: AppUiState,
    actions: AppActions,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = state.botToken,
        onValueChange = { actions.onBotTokenChanged(it.trim()) },
        label = { Text("봇 token") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = botFieldKeyboardActions(imeAction, onImeAction),
        singleLine = true,
        enabled = !state.bot.connected && !state.bot.busy,
        modifier = modifier,
    )
}

@Composable
internal fun BotApiIdField(
    state: AppUiState,
    actions: AppActions,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = state.botApiIdText,
        onValueChange = { actions.onBotApiIdChanged(it.filter(Char::isDigit).take(10)) },
        label = { Text("api_id") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        keyboardActions = botFieldKeyboardActions(imeAction, onImeAction),
        singleLine = true,
        enabled = !state.bot.connected && !state.bot.busy,
        modifier = modifier,
    )
}

@Composable
internal fun BotApiHashField(
    state: AppUiState,
    actions: AppActions,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = state.botApiHash,
        onValueChange = actions::onBotApiHashChanged,
        label = { Text("api_hash") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = botFieldKeyboardActions(imeAction, onImeAction),
        singleLine = true,
        enabled = !state.bot.connected && !state.bot.busy,
        modifier = modifier,
    )
}

private fun botFieldKeyboardActions(imeAction: ImeAction, onImeAction: () -> Unit) = KeyboardActions(
    onDone = { if (imeAction == ImeAction.Done) onImeAction() },
    onGo = { if (imeAction == ImeAction.Go) onImeAction() },
    onNext = { if (imeAction == ImeAction.Next) onImeAction() },
    onPrevious = { if (imeAction == ImeAction.Previous) onImeAction() },
    onSearch = { if (imeAction == ImeAction.Search) onImeAction() },
    onSend = { if (imeAction == ImeAction.Send) onImeAction() },
)

@Composable
internal fun BotStatus(bot: BotUiState) {
    Text("${bot.status}${bot.username?.let { " · @$it" }.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
    BotInlineStatus(bot)
}

internal fun botCanConnect(state: AppUiState): Boolean {
    val parsedApiId = state.botApiIdText.toIntOrNull()
    return !state.bot.busy && parsedApiId != null && parsedApiId > 0 &&
        state.botApiHash.isNotBlank() && state.botToken.isNotBlank()
}

internal fun connectBot(state: AppUiState, actions: AppActions) {
    state.botApiIdText.toIntOrNull()?.let {
        actions.onConnectBot(BotCredentials(it, state.botApiHash.trim(), state.botToken.trim()))
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BotPairButton(bot: BotUiState, actions: AppActions, modifier: Modifier = Modifier) {
    Button(
        onClick = actions::onPairBot,
        enabled = !bot.busy && !bot.username.isNullOrBlank(),
        shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
        modifier = modifier.heightIn(min = ButtonDefaults.MediumContainerHeight),
    ) {
        Text("내 Telegram 계정 연결", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
    }
}
