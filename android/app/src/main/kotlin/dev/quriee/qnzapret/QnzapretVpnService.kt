package dev.quriee.qnzapret

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.VpnService
import androidx.core.app.NotificationCompat

data class VpnRuntimeConfig(
    val localHost: String,
    val localPort: Int,
    val poolSize: Int,
    val cloudflareEnabled: Boolean,
    val secret: String,
)

class QnzapretVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (VpnService.prepare(this) != null) {
            QnzapretVpnRuntimeStore.markFailed(
                "VPN permission is missing. Prepare the service before starting it.",
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val config = intent?.let(::readConfig)
        ensureNotificationChannel()
        val notification = buildNotification()
        startServiceInForeground(notification)

        val runtimeMessage = buildString {
            append("Android VPN service base is active.")
            if (config != null) {
                append(" Runtime target: ${config.localHost}:${config.localPort}.")
                append(" Pool size: ${config.poolSize}.")
                if (config.cloudflareEnabled) {
                    append(" Cloudflare mode enabled.")
                }
            }
            append(" Tunnel establishment is not wired yet.")
        }

        QnzapretVpnRuntimeStore.markRunning(runtimeMessage)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        QnzapretVpnRuntimeStore.markIdle(
            this,
            "Android VPN service base stopped. Bridge remains ready for the next start.",
        )
        super.onDestroy()
    }

    override fun onRevoke() {
        QnzapretVpnRuntimeStore.markIdle(
            this,
            "VPN permission was revoked by the system. Prepare the service again before starting it.",
        )
        stopSelf()
        super.onRevoke()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return
        }

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "QNZapret VPN runtime",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Foreground notification for the Android VPN runtime base."
        }

        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("QNZapret VPN base")
            .setContentText("Android VPN service base is running in foreground mode.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun startServiceInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            return
        }

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun readConfig(intent: Intent): VpnRuntimeConfig {
        return VpnRuntimeConfig(
            localHost = intent.getStringExtra(EXTRA_LOCAL_HOST) ?: "127.0.0.1",
            localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, 0),
            poolSize = intent.getIntExtra(EXTRA_POOL_SIZE, 0),
            cloudflareEnabled = intent.getBooleanExtra(
                EXTRA_CLOUDFLARE_ENABLED,
                false,
            ),
            secret = intent.getStringExtra(EXTRA_SECRET).orEmpty(),
        )
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "qnzapret_vpn_runtime"
        private const val NOTIFICATION_ID = 4107
        private const val EXTRA_LOCAL_HOST = "extra_local_host"
        private const val EXTRA_LOCAL_PORT = "extra_local_port"
        private const val EXTRA_POOL_SIZE = "extra_pool_size"
        private const val EXTRA_CLOUDFLARE_ENABLED = "extra_cloudflare_enabled"
        private const val EXTRA_SECRET = "extra_secret"

        fun createStartIntent(context: Context, config: VpnRuntimeConfig): Intent {
            return Intent(context, QnzapretVpnService::class.java).apply {
                putExtra(EXTRA_LOCAL_HOST, config.localHost)
                putExtra(EXTRA_LOCAL_PORT, config.localPort)
                putExtra(EXTRA_POOL_SIZE, config.poolSize)
                putExtra(EXTRA_CLOUDFLARE_ENABLED, config.cloudflareEnabled)
                putExtra(EXTRA_SECRET, config.secret)
            }
        }
    }
}
