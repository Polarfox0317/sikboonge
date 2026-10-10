package io.github.polarfox0317.sikboonge

internal object BotInboxPolicy {
    private val privatePostLink = Regex("""(?i)(?:https?://)?(?:www\.)?(?:t\.me|telegram\.me)/c/\d+/\d+""")

    fun pairingMatches(text: String, nonce: String?, createdMs: Long, nowMs: Long): Boolean =
        nonce != null && nowMs - createdMs in 0..300_000L &&
            java.security.MessageDigest.isEqual(text.toByteArray(), "/start $nonce".toByteArray())

    fun containsPrivatePostLink(text: String): Boolean = privatePostLink.containsMatchIn(text)

    fun acceptsPairedPrivateIncoming(sender: Long, chat: Long, pairedUser: Long, pairedChat: Long,
                                     privateChat: Boolean, outgoing: Boolean): Boolean =
        privateChat && !outgoing && pairedUser > 0 && pairedChat > 0 && sender == pairedUser && chat == pairedChat

    fun acceptsVideo(sender: Long, chat: Long, pairedUser: Long, pairedChat: Long, privateChat: Boolean,
                     outgoing: Boolean, canBeSaved: Boolean): Boolean =
        acceptsPairedPrivateIncoming(sender, chat, pairedUser, pairedChat, privateChat, outgoing) && canBeSaved

    fun isDuplicateMedia(messageId: Long, fileId: Int, indexed: Set<Pair<Long, Int>>): Boolean =
        messageId > 0L && fileId > 0 && (messageId to fileId) in indexed
}
