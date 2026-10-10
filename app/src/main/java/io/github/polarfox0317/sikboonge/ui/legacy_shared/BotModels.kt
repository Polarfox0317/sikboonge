package io.github.polarfox0317.sikboonge

data class BotUiState(
    /** True once this process has finished its first persisted credential/state read. */
    val initialStateLoaded: Boolean = false,
    val setupComplete: Boolean = false,
    val refreshing: Boolean = false,
    val status: String = "연결 전",
    val busy: Boolean = false,
    val connected: Boolean = false,
    val username: String? = null,
    val botName: String? = null,
    val pairingUrl: String? = null,
    val paired: Boolean = false,
    val videos: List<BotVideo> = emptyList(),
    val error: String? = null,
    val needsReconnect: Boolean = false,
    val receiveDiagnostic: String = "새 메시지 업데이트를 기다리는 중",
)

data class BotCredentials(val apiId: Int, val apiHash: String, val token: String)

