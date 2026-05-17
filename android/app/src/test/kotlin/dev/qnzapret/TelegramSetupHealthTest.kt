package dev.qnzapret

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramSetupHealthTest {
    @Test
    fun freshInstallRequiresSetupAndAllowsInitialUiOpen() {
        val fingerprint = TelegramSetupHealthPolicy.fingerprint("127.0.0.1", 1443, "dd${SECRET_A}")
        val record = TelegramSetupHealthRecord()

        assertTrue(
            TelegramSetupHealthPolicy.setupRequired(
                record = record,
                currentFingerprint = fingerprint,
                serverStartedAtMs = 1_000L,
            ),
        )
        assertTrue(
            TelegramSetupHealthPolicy.canAutoOpenSetup(
                record = record,
                currentFingerprint = fingerprint,
                serverStartedAtMs = 1_000L,
                nowMs = 1_100L,
            ),
        )
    }

    @Test
    fun openedButNotConfirmedStillRequiresSetupAndRespectsCooldown() {
        val fingerprint = TelegramSetupHealthPolicy.fingerprint("127.0.0.1", 1443, "dd${SECRET_A}")
        val opened = TelegramSetupHealthPolicy.markSetupOpened(
            record = TelegramSetupHealthRecord(),
            currentFingerprint = fingerprint,
            nowMs = 1_100L,
        )

        assertTrue(
            TelegramSetupHealthPolicy.setupRequired(
                record = opened,
                currentFingerprint = fingerprint,
                serverStartedAtMs = 1_000L,
            ),
        )
        assertFalse(
            TelegramSetupHealthPolicy.canAutoOpenSetup(
                record = opened,
                currentFingerprint = fingerprint,
                serverStartedAtMs = 1_000L,
                nowMs = 1_200L,
            ),
        )
        assertTrue(
            TelegramSetupHealthPolicy.canAutoOpenSetup(
                record = opened,
                currentFingerprint = fingerprint,
                serverStartedAtMs = 1_000L,
                nowMs = 1_100L + TelegramSetupHealthPolicy.SETUP_AUTO_OPEN_COOLDOWN_MS + 1L,
            ),
        )
    }

    @Test
    fun confirmedHandshakeAndBridgeClearsSetupRequiredForCurrentRun() {
        val fingerprint = TelegramSetupHealthPolicy.fingerprint("127.0.0.1", 1443, "dd${SECRET_A}")
        val withHandshake = TelegramSetupHealthPolicy.markSuccessfulHandshake(
            record = TelegramSetupHealthRecord(),
            currentFingerprint = fingerprint,
            nowMs = 1_100L,
        )
        val confirmed = TelegramSetupHealthPolicy.markSuccessfulBridge(
            record = withHandshake,
            currentFingerprint = fingerprint,
            nowMs = 1_200L,
        )

        assertFalse(
            TelegramSetupHealthPolicy.setupRequired(
                record = confirmed,
                currentFingerprint = fingerprint,
                serverStartedAtMs = 1_000L,
            ),
        )
        assertFalse(
            TelegramSetupHealthPolicy.canAutoOpenSetup(
                record = confirmed,
                currentFingerprint = fingerprint,
                serverStartedAtMs = 1_000L,
                nowMs = 1_300L,
            ),
        )
    }

    @Test
    fun endpointFingerprintChangeResetsSetupHealth() {
        val first = TelegramSetupHealthPolicy.fingerprint("127.0.0.1", 1443, "dd${SECRET_A}")
        val second = TelegramSetupHealthPolicy.fingerprint("127.0.0.1", 1444, "dd${SECRET_A}")
        val confirmed = TelegramSetupHealthPolicy.markSuccessfulBridge(
            record = TelegramSetupHealthPolicy.markSuccessfulHandshake(
                record = TelegramSetupHealthRecord(),
                currentFingerprint = first,
                nowMs = 1_100L,
            ),
            currentFingerprint = first,
            nowMs = 1_200L,
        )

        assertNotEquals(first, second)
        assertTrue(
            TelegramSetupHealthPolicy.setupRequired(
                record = confirmed,
                currentFingerprint = second,
                serverStartedAtMs = 1_300L,
            ),
        )
    }

    @Test
    fun previouslyConfirmedButNoHandshakeAfterRestartRequiresSetupWithoutAutoSpam() {
        val fingerprint = TelegramSetupHealthPolicy.fingerprint("127.0.0.1", 1443, "dd${SECRET_A}")
        val confirmed = TelegramSetupHealthPolicy.markSuccessfulBridge(
            record = TelegramSetupHealthPolicy.markSuccessfulHandshake(
                record = TelegramSetupHealthRecord(),
                currentFingerprint = fingerprint,
                nowMs = 1_100L,
            ),
            currentFingerprint = fingerprint,
            nowMs = 1_200L,
        )

        assertTrue(
            TelegramSetupHealthPolicy.setupRequired(
                record = confirmed,
                currentFingerprint = fingerprint,
                serverStartedAtMs = 5_000L,
            ),
        )
        assertFalse(
            TelegramSetupHealthPolicy.canAutoOpenSetup(
                record = confirmed,
                currentFingerprint = fingerprint,
                serverStartedAtMs = 5_000L,
                nowMs = 5_100L,
            ),
        )
    }

    private companion object {
        private const val SECRET_A = "0123456789abcdef0123456789abcdef"
    }
}
