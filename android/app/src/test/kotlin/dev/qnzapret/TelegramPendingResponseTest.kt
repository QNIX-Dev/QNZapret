package dev.qnzapret

import org.junit.Assert.*
import org.junit.Test

class TelegramPendingResponseTest {
    @Test fun idleMediaConnectionDoesNotTimeOut() {
        assertFalse(TelegramPendingResponse().isStalled(600_000))
    }
    @Test fun unansweredRequestEventuallyTimesOut() {
        val progress = TelegramPendingResponse()
        progress.onWrite(100)
        assertFalse(progress.isStalled(30_099))
        assertTrue(progress.isStalled(30_100))
    }
    @Test fun smallStickerResponseThenIdleIsHealthy() {
        val progress = TelegramPendingResponse()
        progress.onWrite(0); progress.onRead()
        assertFalse(progress.isStalled(600_000))
    }
    @Test fun slowDownloadProgressIsNotAborted() {
        val progress = TelegramPendingResponse()
        repeat(30) { i ->
            progress.onWrite(i * 10_000L); progress.onRead()
            assertFalse(progress.isStalled(i * 10_000L + 9_000))
        }
        progress.onWrite(400_000)
        assertTrue(progress.isStalled(430_000))
    }
}
