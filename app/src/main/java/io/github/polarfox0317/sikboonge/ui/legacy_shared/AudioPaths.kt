package io.github.polarfox0317.sikboonge

import java.io.File

internal fun canonicalAudioPath(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)
