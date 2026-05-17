package dev.qnzapret

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

internal class TelegramSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(LOG_TAG, "telegram setup action received")

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val secretWithPrefix = intent.getStringExtra(EXTRA_SECRET).orEmpty()
        if (host.isBlank() || port !in 1..65535 || secretWithPrefix.isBlank()) {
            Log.d(
                LOG_TAG,
                "telegram setup open failed errorCode=invalid_endpoint " +
                    "host=${host.ifBlank { "-" }} port=$port",
            )
            finish()
            return
        }

        val endpoint = "$host:$port"
        val fingerprint = TelegramSetupHealthPolicy.fingerprint(host, port, secretWithPrefix)
        TelegramSetupHealthStore.from(this).markSetupOpened(fingerprint, System.currentTimeMillis())
        Log.d(LOG_TAG, "telegram setup open start endpoint=$endpoint")

        if (openSetupUri("tg", telegramUri(host, port, secretWithPrefix))) {
            finish()
            return
        }
        if (openSetupUri("https", telegramHttpsUri(host, port, secretWithPrefix))) {
            finish()
            return
        }

        Log.d(LOG_TAG, "telegram setup open failed endpoint=$endpoint")
        finish()
    }

    private fun openSetupUri(scheme: String, uri: Uri): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            Log.d(LOG_TAG, "telegram setup open ok scheme=$scheme")
            true
        } catch (error: Exception) {
            Log.d(
                LOG_TAG,
                "telegram setup open failed scheme=$scheme " +
                    "error=${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            false
        }
    }

    internal companion object {
        private const val LOG_TAG = "QNZapretTgCompat"
        private const val EXTRA_HOST = "extra_telegram_setup_host"
        private const val EXTRA_PORT = "extra_telegram_setup_port"
        private const val EXTRA_SECRET = "extra_telegram_setup_secret"

        fun createIntent(context: Context, state: TelegramCompatibilityProxyState): Intent {
            return Intent(context, TelegramSetupActivity::class.java).apply {
                putExtra(EXTRA_HOST, state.host)
                putExtra(EXTRA_PORT, state.port)
                putExtra(EXTRA_SECRET, state.secretWithPrefix)
            }
        }

        fun telegramUri(host: String, port: Int, secretWithPrefix: String): Uri {
            return Uri.Builder()
                .scheme("tg")
                .authority("proxy")
                .appendQueryParameter("server", host)
                .appendQueryParameter("port", port.toString())
                .appendQueryParameter("secret", secretWithPrefix)
                .build()
        }

        fun telegramHttpsUri(host: String, port: Int, secretWithPrefix: String): Uri {
            return Uri.Builder()
                .scheme("https")
                .authority("t.me")
                .appendPath("proxy")
                .appendQueryParameter("server", host)
                .appendQueryParameter("port", port.toString())
                .appendQueryParameter("secret", secretWithPrefix)
                .build()
        }
    }
}
