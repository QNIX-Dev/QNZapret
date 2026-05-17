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
import android.util.Log
import androidx.core.app.NotificationCompat

class QnzapretVpnService : VpnService() {
    private var runtime: QnzapretAndroidRuntime? = null
    private var telegramCompatibilityProxy: TelegramCompatibilityProxyManager? = null
    private var lastConfig: VpnRuntimeConfig? = null
    private var lastProxyEndpoint: LocalStrategyProxyEndpoint? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OPEN_TELEGRAM_PROXY) {
            Log.d(LOG_TAG, "telegram setup action received")
            val telegramState = ensureTelegramCompatibilityProxyStarted()
            updateNotification(NotificationState.ACTIVE, lastConfig, telegramState)
            if (!telegramCompatibilityProxy.orCreate().openSetupScreen(telegramState)) {
                Log.d(LOG_TAG, "telegram setup action failed to open confirmation screen")
            }
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            Log.d(LOG_TAG, "stop action received")
            ensureNotificationChannel()
            QnzapretVpnRuntimeStore.markStopping("Останавливаем сервис обхода.")
            updateNotification(NotificationState.STOPPING, lastConfig, telegramCompatibilityProxy?.currentState())
            stopRuntime("Сервис обхода остановлен. Система готова к следующему запуску.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (VpnService.prepare(this) != null) {
            QnzapretVpnRuntimeStore.markFailed(
                "Нет VPN-разрешения. Перед запуском нужно подготовить сервис.",
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val isRestart = intent?.action == ACTION_RESTART
        val startOrigin = intent?.getStringExtra(EXTRA_START_ORIGIN) ?: START_ORIGIN_UNKNOWN
        val config = intent?.let(::readConfig) ?: lastConfig ?: VpnRuntimeConfig()
        lastConfig = config
        if (isRestart) {
            Log.d(LOG_TAG, "restart action received")
            QnzapretVpnRuntimeStore.markStarting("Перезапускаем сервис обхода.")
        }
        ensureNotificationChannel()
        val notification = buildNotification(NotificationState.STARTING, config, telegramCompatibilityProxy?.currentState())
        startServiceInForeground(notification)
        AndroidNetworkSelfTest.run(this, "pre_vpn_start")
        runtime?.stop()
        telegramCompatibilityProxy?.stop()
        lastProxyEndpoint = null
        val startResult = try {
            QnzapretAndroidRuntime(this).also { runtime = it }.start(config)
        } catch (error: Exception) {
            Log.d(
                LOG_TAG,
                "runtime start failed ${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            runtime?.stop()
            runtime = null
            QnzapretVpnRuntimeStore.markFailed(
                "Не удалось запустить сервис обхода: ${error.message ?: error.javaClass.simpleName}.",
            )
            updateNotification(NotificationState.ERROR, config, telegramCompatibilityProxy?.currentState())
            stopSelf()
            return START_NOT_STICKY
        }
        lastProxyEndpoint = startResult.proxyEndpoint
        val initialTelegramState = ensureTelegramCompatibilityProxyStarted()
        val telegramState =
            if (startOrigin == START_ORIGIN_UI &&
                initialTelegramState.ready &&
                initialTelegramState.setupRequired &&
                telegramCompatibilityProxy.orCreate().shouldAutoOpenSetup(initialTelegramState)
            ) {
                telegramCompatibilityProxy.orCreate().openSetupScreen(initialTelegramState)
                telegramCompatibilityProxy.orCreate().currentState()
            } else {
                initialTelegramState
            }
        AndroidNetworkSelfTest.run(this, "post_tun_start")

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
            append(" Telegram proxy: ${telegramState.message}")
            if (telegramState.setupRequired) {
                append(" Нужно подключить Telegram к локальному proxy.")
            }
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
            newTelegramCompatibilityProxyReady = telegramState.ready,
            newTelegramCompatibilitySetupRequired = telegramState.setupRequired,
            newTelegramCompatibilityProxyEndpoint = telegramState.endpoint,
            newTelegramCompatibilityProxyMessage = telegramState.message,
        )
        updateNotification(NotificationState.ACTIVE, config, telegramState)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRuntime("Сервис обхода остановлен. Система готова к следующему запуску.")
        super.onDestroy()
    }

    override fun onRevoke() {
        stopRuntime("Система отозвала VPN-разрешение. Перед новым запуском нужно разрешить VPN снова.")
        stopSelf()
        super.onRevoke()
    }

    private fun stopRuntime(message: String) {
        runtime?.stop()
        runtime = null
        lastProxyEndpoint = null
        telegramCompatibilityProxy?.stop()
        telegramCompatibilityProxy = null
        QnzapretVpnRuntimeStore.markIdle(this, message)
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

    private fun buildNotification(
        state: NotificationState,
        config: VpnRuntimeConfig?,
        telegramState: TelegramCompatibilityProxyState?,
    ): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            1,
            createStopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val restartIntent = config?.let { restartConfig ->
            PendingIntent.getService(
                this,
                2,
                createRestartIntent(this, restartConfig),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val telegramIntent = telegramState
            ?.takeIf { it.ready && state != NotificationState.STOPPING }
            ?.let {
                PendingIntent.getActivity(
                    this,
                    TELEGRAM_SETUP_REQUEST_CODE,
                    telegramCompatibilityProxy.orCreate().createSetupIntent(it),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        val (title, text) = when (state) {
            NotificationState.STARTING -> "QNZapret запускается" to "Поднимаем VPN и локальный proxy."
            NotificationState.ACTIVE -> "QNZapret активно" to if (telegramState?.ready == true) {
                if (telegramState.setupRequired) {
                    "Передача активна. Нужно подключить Telegram: ${telegramState.endpoint}."
                } else {
                    "Передача активна. Telegram proxy: ${telegramState.endpoint}."
                }
            } else {
                "Передача активна."
            }
            NotificationState.ERROR -> "QNZapret ошибка" to "Проверьте состояние в приложении."
            NotificationState.STOPPING -> "QNZapret останавливается" to "Закрываем VPN и локальный proxy."
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopIntent)

        if (restartIntent != null && state != NotificationState.STOPPING) {
            builder.addAction(android.R.drawable.ic_popup_sync, "Перезапустить", restartIntent)
        }
        if (telegramIntent != null) {
            builder.addAction(android.R.drawable.ic_dialog_map, "Подключить Telegram", telegramIntent)
        }

        return builder.build()
    }

    private fun updateNotification(
        state: NotificationState,
        config: VpnRuntimeConfig?,
        telegramState: TelegramCompatibilityProxyState? = telegramCompatibilityProxy?.currentState(),
    ) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(state, config, telegramState))
    }

    private fun ensureTelegramCompatibilityProxyStarted(): TelegramCompatibilityProxyState {
        return try {
            telegramCompatibilityProxy.orCreate().start()
        } catch (error: Exception) {
            Log.d(
                LOG_TAG,
                "telegram compatibility start failed ${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            TelegramCompatibilityProxyState(
                ready = false,
                setupRequired = false,
                host = "127.0.0.1",
                port = 1443,
                secretWithPrefix = "",
                message = "Telegram compatibility proxy не запустился: ${error.message ?: error.javaClass.simpleName}.",
            )
        }
    }

    private fun TelegramCompatibilityProxyManager?.orCreate(): TelegramCompatibilityProxyManager {
        val existing = this
        if (existing != null) {
            return existing
        }
        val created = TelegramCompatibilityProxyManager(
            service = this@QnzapretVpnService,
            strategyProxyProvider = { lastProxyEndpoint },
        ) { state ->
            updateNotification(NotificationState.ACTIVE, lastConfig, state)
        }
        telegramCompatibilityProxy = created
        return created
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
        val strategyProfile = StrategyProfileDevOverrides.apply(
            this,
            StrategyProfileCodec.fromJson(
                intent.getStringExtra(EXTRA_STRATEGY_PROFILE),
            ),
        )
        return VpnRuntimeConfig(
            localHost = intent.getStringExtra(EXTRA_LOCAL_HOST) ?: "127.0.0.1",
            localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, 0),
            poolSize = intent.getIntExtra(EXTRA_POOL_SIZE, 0),
            cloudflareEnabled = intent.getBooleanExtra(
                EXTRA_CLOUDFLARE_ENABLED,
                false,
            ),
            secret = intent.getStringExtra(EXTRA_SECRET).orEmpty(),
            strategyProfile = strategyProfile,
            establishTunnel = intent.getBooleanExtra(EXTRA_ESTABLISH_TUNNEL, true),
            tunnelMtu = intent.getIntExtra(EXTRA_TUNNEL_MTU, 8500),
        )
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "qnzapret_vpn_runtime"
        private const val NOTIFICATION_ID = 4107
        private const val TELEGRAM_SETUP_REQUEST_CODE = 4109
        private const val EXTRA_LOCAL_HOST = "extra_local_host"
        private const val EXTRA_LOCAL_PORT = "extra_local_port"
        private const val EXTRA_POOL_SIZE = "extra_pool_size"
        private const val EXTRA_CLOUDFLARE_ENABLED = "extra_cloudflare_enabled"
        private const val EXTRA_SECRET = "extra_secret"
        private const val EXTRA_STRATEGY_PROFILE = "extra_strategy_profile"
        private const val EXTRA_ESTABLISH_TUNNEL = "extra_establish_tunnel"
        private const val EXTRA_TUNNEL_MTU = "extra_tunnel_mtu"
        private const val EXTRA_START_ORIGIN = "extra_start_origin"
        private const val ACTION_STOP = "dev.qnzapret.action.STOP_VPN_RUNTIME"
        private const val ACTION_RESTART = "dev.qnzapret.action.RESTART_VPN_RUNTIME"
        private const val ACTION_OPEN_TELEGRAM_PROXY = "dev.qnzapret.action.OPEN_TELEGRAM_PROXY"
        private const val START_ORIGIN_UI = "ui"
        private const val START_ORIGIN_TILE = "tile"
        private const val START_ORIGIN_NOTIFICATION = "notification"
        private const val START_ORIGIN_UNKNOWN = "unknown"
        private const val LOG_TAG = "QNZapretService"

        internal fun createStartIntent(
            context: Context,
            config: VpnRuntimeConfig,
            origin: String = START_ORIGIN_UNKNOWN,
        ): Intent {
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
                putExtra(EXTRA_START_ORIGIN, origin)
            }
        }

        internal fun createUiStartIntent(context: Context, config: VpnRuntimeConfig): Intent {
            return createStartIntent(context, config, START_ORIGIN_UI)
        }

        internal fun createTileStartIntent(context: Context, config: VpnRuntimeConfig): Intent {
            return createStartIntent(context, config, START_ORIGIN_TILE)
        }

        internal fun createStopIntent(context: Context): Intent {
            return Intent(context, QnzapretVpnService::class.java).apply {
                action = ACTION_STOP
            }
        }

        private fun createRestartIntent(context: Context, config: VpnRuntimeConfig): Intent {
            return createStartIntent(context, config, START_ORIGIN_NOTIFICATION).apply {
                action = ACTION_RESTART
            }
        }

        private fun createOpenTelegramProxyIntent(context: Context): Intent {
            return Intent(context, QnzapretVpnService::class.java).apply {
                action = ACTION_OPEN_TELEGRAM_PROXY
            }
        }
    }

    private enum class NotificationState {
        STARTING,
        ACTIVE,
        ERROR,
        STOPPING,
    }
}
