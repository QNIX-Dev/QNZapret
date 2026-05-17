package dev.qnzapret

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.Locale

internal const val TELEGRAM_COMPAT_PREFS_NAME = "telegram_compatibility_proxy"

internal data class TelegramSetupHealthRecord(
    val fingerprint: String? = null,
    val lastSetupOpenedAtMs: Long = 0L,
    val lastSuccessfulHandshakeAtMs: Long = 0L,
    val lastSuccessfulBridgeAtMs: Long = 0L,
)

internal object TelegramSetupHealthPolicy {
    const val SETUP_AUTO_OPEN_COOLDOWN_MS: Long = 10 * 60 * 1000L

    fun fingerprint(host: String, port: Int, secretWithPrefix: String): String {
        val source = "$host:$port:$secretWithPrefix"
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun normalize(
        record: TelegramSetupHealthRecord,
        currentFingerprint: String,
    ): TelegramSetupHealthRecord {
        return if (record.fingerprint == currentFingerprint) {
            record
        } else {
            TelegramSetupHealthRecord(fingerprint = currentFingerprint)
        }
    }

    fun setupRequired(
        record: TelegramSetupHealthRecord,
        currentFingerprint: String,
        serverStartedAtMs: Long,
    ): Boolean {
        val normalized = normalize(record, currentFingerprint)
        return normalized.lastSuccessfulHandshakeAtMs < serverStartedAtMs ||
            normalized.lastSuccessfulBridgeAtMs < serverStartedAtMs
    }

    fun canAutoOpenSetup(
        record: TelegramSetupHealthRecord,
        currentFingerprint: String,
        serverStartedAtMs: Long,
        nowMs: Long,
        cooldownMs: Long = SETUP_AUTO_OPEN_COOLDOWN_MS,
    ): Boolean {
        val normalized = normalize(record, currentFingerprint)
        if (!setupRequired(normalized, currentFingerprint, serverStartedAtMs)) {
            return false
        }
        if (wasEverConfirmed(normalized)) {
            return false
        }
        return normalized.lastSetupOpenedAtMs <= 0L ||
            nowMs - normalized.lastSetupOpenedAtMs >= cooldownMs
    }

    fun markSetupOpened(
        record: TelegramSetupHealthRecord,
        currentFingerprint: String,
        nowMs: Long,
    ): TelegramSetupHealthRecord {
        return normalize(record, currentFingerprint).copy(lastSetupOpenedAtMs = nowMs)
    }

    fun markSuccessfulHandshake(
        record: TelegramSetupHealthRecord,
        currentFingerprint: String,
        nowMs: Long,
    ): TelegramSetupHealthRecord {
        return normalize(record, currentFingerprint).copy(lastSuccessfulHandshakeAtMs = nowMs)
    }

    fun markSuccessfulBridge(
        record: TelegramSetupHealthRecord,
        currentFingerprint: String,
        nowMs: Long,
    ): TelegramSetupHealthRecord {
        return normalize(record, currentFingerprint).copy(lastSuccessfulBridgeAtMs = nowMs)
    }

    private fun wasEverConfirmed(record: TelegramSetupHealthRecord): Boolean {
        return record.lastSuccessfulHandshakeAtMs > 0L && record.lastSuccessfulBridgeAtMs > 0L
    }
}

internal class TelegramSetupHealthStore(private val prefs: SharedPreferences) {
    @Synchronized
    fun read(): TelegramSetupHealthRecord {
        return TelegramSetupHealthRecord(
            fingerprint = prefs.getString(KEY_SETUP_FINGERPRINT, null),
            lastSetupOpenedAtMs = prefs.getLong(KEY_LAST_SETUP_OPENED_AT_MS, 0L),
            lastSuccessfulHandshakeAtMs = prefs.getLong(KEY_LAST_SUCCESSFUL_HANDSHAKE_AT_MS, 0L),
            lastSuccessfulBridgeAtMs = prefs.getLong(KEY_LAST_SUCCESSFUL_BRIDGE_AT_MS, 0L),
        )
    }

    @Synchronized
    fun syncFingerprint(currentFingerprint: String): TelegramSetupHealthRecord {
        val previous = read()
        val normalized = TelegramSetupHealthPolicy.normalize(previous, currentFingerprint)
        if (normalized != previous) {
            write(normalized)
        }
        return normalized
    }

    @Synchronized
    fun markSetupOpened(currentFingerprint: String, nowMs: Long): TelegramSetupHealthRecord {
        val next = TelegramSetupHealthPolicy.markSetupOpened(read(), currentFingerprint, nowMs)
        write(next)
        return next
    }

    @Synchronized
    fun markSuccessfulHandshake(currentFingerprint: String, nowMs: Long): TelegramSetupHealthRecord {
        val next = TelegramSetupHealthPolicy.markSuccessfulHandshake(read(), currentFingerprint, nowMs)
        write(next)
        return next
    }

    @Synchronized
    fun markSuccessfulBridge(currentFingerprint: String, nowMs: Long): TelegramSetupHealthRecord {
        val next = TelegramSetupHealthPolicy.markSuccessfulBridge(read(), currentFingerprint, nowMs)
        write(next)
        return next
    }

    @Synchronized
    fun setupRequired(currentFingerprint: String, serverStartedAtMs: Long): Boolean {
        return TelegramSetupHealthPolicy.setupRequired(
            record = syncFingerprint(currentFingerprint),
            currentFingerprint = currentFingerprint,
            serverStartedAtMs = serverStartedAtMs,
        )
    }

    @Synchronized
    fun canAutoOpenSetup(
        currentFingerprint: String,
        serverStartedAtMs: Long,
        nowMs: Long,
    ): Boolean {
        return TelegramSetupHealthPolicy.canAutoOpenSetup(
            record = syncFingerprint(currentFingerprint),
            currentFingerprint = currentFingerprint,
            serverStartedAtMs = serverStartedAtMs,
            nowMs = nowMs,
        )
    }

    private fun write(record: TelegramSetupHealthRecord) {
        prefs.edit()
            .putString(KEY_SETUP_FINGERPRINT, record.fingerprint)
            .putLong(KEY_LAST_SETUP_OPENED_AT_MS, record.lastSetupOpenedAtMs)
            .putLong(KEY_LAST_SUCCESSFUL_HANDSHAKE_AT_MS, record.lastSuccessfulHandshakeAtMs)
            .putLong(KEY_LAST_SUCCESSFUL_BRIDGE_AT_MS, record.lastSuccessfulBridgeAtMs)
            .apply()
    }

    internal companion object {
        private const val KEY_SETUP_FINGERPRINT = "setup_fingerprint"
        private const val KEY_LAST_SETUP_OPENED_AT_MS = "last_setup_opened_at_ms"
        private const val KEY_LAST_SUCCESSFUL_HANDSHAKE_AT_MS = "last_successful_handshake_at_ms"
        private const val KEY_LAST_SUCCESSFUL_BRIDGE_AT_MS = "last_successful_bridge_at_ms"

        fun from(context: Context): TelegramSetupHealthStore {
            return TelegramSetupHealthStore(
                context.getSharedPreferences(TELEGRAM_COMPAT_PREFS_NAME, Context.MODE_PRIVATE),
            )
        }
    }
}

internal fun String.redactedFingerprint(): String {
    return lowercase(Locale.US).take(12)
}
