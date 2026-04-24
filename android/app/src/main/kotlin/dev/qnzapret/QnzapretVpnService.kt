package dev.qnzapret

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

class QnzapretVpnService : VpnService() {
    private var runtime: QnzapretAndroidRuntime? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (VpnService.prepare(this) != null) {
            QnzapretVpnRuntimeStore.markFailed(
                "Нет VPN-разрешения. Перед запуском нужно подготовить сервис.",
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val config = intent?.let(::readConfig) ?: VpnRuntimeConfig()
        ensureNotificationChannel()
        val notification = buildNotification()
        startServiceInForeground(notification)
        runtime?.stop()
        val startResult = try {
            QnzapretAndroidRuntime(this).also { runtime = it }.start(config)
        } catch (error: Exception) {
            runtime?.stop()
            runtime = null
            QnzapretVpnRuntimeStore.markFailed(
                "Не удалось запустить сервис обхода: ${error.message ?: error.javaClass.simpleName}.",
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val runtimeMessage = buildString {
            append("Ядро обхода активно.")
            append(" Профиль: ${startResult.plan.profileName} (${startResult.plan.ruleCount} правил).")
            append(" Остальной трафик: ${startResult.plan.unmatchedTrafficPolicy.wireValue}.")
            append(" Локальный proxy: ${startResult.proxyEndpoint.host}:${startResult.proxyEndpoint.port}.")
            append(
                " Данные стратегии: ${startResult.proxyStatus.hostlistCount} списков, " +
                    "${startResult.proxyStatus.blobCount} payload-файлов.",
            )
            append(
                " Протоколы: ${
                    startResult.proxyStatus.supportedProtocols
                        .map { it.wireValue }
                        .sorted()
                        .joinToString()
                }.",
            )
            append(" TCP-порты: ${startResult.plan.tcpPorts.sorted().joinToString()}.")
            append(" UDP-порты: ${startResult.plan.udpPorts.sorted().joinToString()}.")
            append(
                " Передача: IPv4 codec=${startResult.tunState.packetCodecReady}, " +
                    "IPv6 codec=${startResult.tunState.ipv6PacketCodecReady}, " +
                    "UDP=${startResult.tunState.udpForwarderReady}, " +
                    "IPv6 UDP=${startResult.tunState.ipv6UdpForwarderReady}, " +
                    "TCP=${startResult.tunState.tcpForwarderReady}.",
            )
            if (startResult.plan.requiredBlobKeys.isNotEmpty()) {
                append(" Payload-файлы: ${startResult.plan.requiredBlobKeys.sorted().joinToString()}.")
            }
            if (startResult.assetReport.isComplete) {
                append(" Данные проверены: ${startResult.assetReport.presentCount}.")
            } else {
                append(
                    " Не найдены данные: ${startResult.assetReport.missingPaths.sorted().joinToString()}.",
                )
            }
            append(" ${startResult.tunState.message}")
        }

        QnzapretVpnRuntimeStore.markRunning(
            newMessage = runtimeMessage,
            newStrategyEngineReady = startResult.proxyStatus.engineReady,
            newTrafficForwarderReady = startResult.tunState.forwarderReady,
            newTunnelActive = startResult.tunState.active,
            newPacketCodecReady = startResult.tunState.packetCodecReady,
            newUdpForwarderReady = startResult.tunState.udpForwarderReady,
            newIpv6PacketCodecReady = startResult.tunState.ipv6PacketCodecReady,
            newIpv6UdpForwarderReady = startResult.tunState.ipv6UdpForwarderReady,
            newTcpForwarderReady = startResult.tunState.tcpForwarderReady,
            newActiveProfileName = startResult.plan.profileName,
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runtime?.stop()
        runtime = null
        QnzapretVpnRuntimeStore.markIdle(
            this,
            "Сервис обхода остановлен. Система готова к следующему запуску.",
        )
        super.onDestroy()
    }

    override fun onRevoke() {
        runtime?.stop()
        runtime = null
        QnzapretVpnRuntimeStore.markIdle(
            this,
            "Система отозвала VPN-разрешение. Перед новым запуском нужно разрешить VPN снова.",
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
            "QNZapret VPN",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Уведомление о работе VPN-сервиса QNZapret."
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
            .setContentTitle("QNZapret работает")
            .setContentText("Сервис обхода активен.")
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
            strategyProfile = StrategyProfileCodec.fromJson(
                intent.getStringExtra(EXTRA_STRATEGY_PROFILE),
            ),
            establishTunnel = intent.getBooleanExtra(EXTRA_ESTABLISH_TUNNEL, false),
            tunnelMtu = intent.getIntExtra(EXTRA_TUNNEL_MTU, 8500),
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
        private const val EXTRA_STRATEGY_PROFILE = "extra_strategy_profile"
        private const val EXTRA_ESTABLISH_TUNNEL = "extra_establish_tunnel"
        private const val EXTRA_TUNNEL_MTU = "extra_tunnel_mtu"

        internal fun createStartIntent(context: Context, config: VpnRuntimeConfig): Intent {
            return Intent(context, QnzapretVpnService::class.java).apply {
                putExtra(EXTRA_LOCAL_HOST, config.localHost)
                putExtra(EXTRA_LOCAL_PORT, config.localPort)
                putExtra(EXTRA_POOL_SIZE, config.poolSize)
                putExtra(EXTRA_CLOUDFLARE_ENABLED, config.cloudflareEnabled)
                putExtra(EXTRA_SECRET, config.secret)
                putExtra(
                    EXTRA_STRATEGY_PROFILE,
                    StrategyProfileCodec.toJson(config.strategyProfile),
                )
                putExtra(EXTRA_ESTABLISH_TUNNEL, config.establishTunnel)
                putExtra(EXTRA_TUNNEL_MTU, config.tunnelMtu)
            }
        }
    }
}
