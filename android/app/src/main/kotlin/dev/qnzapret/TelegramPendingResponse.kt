package dev.qnzapret

/** Idle connections and slow but progressing downloads must not be treated as dead routes. */
internal class TelegramPendingResponse(private val timeoutMs: Long = 30_000L) {
    private var pendingSince: Long? = null

    @Synchronized fun onWrite(nowMs: Long) {
        if (pendingSince == null) pendingSince = nowMs
    }

    @Synchronized fun onRead() { pendingSince = null }

    @Synchronized fun isStalled(nowMs: Long): Boolean =
        pendingSince?.let { nowMs - it >= timeoutMs } ?: false
}
