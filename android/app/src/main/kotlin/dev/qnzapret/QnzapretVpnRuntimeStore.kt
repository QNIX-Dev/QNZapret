package dev.qnzapret

import android.content.Context
import android.content.ComponentName
import android.os.Build
import android.service.quicksettings.TileService
import java.util.concurrent.CopyOnWriteArraySet

internal object QnzapretVpnRuntimeStore {
    private const val PLATFORM = "android"
    private const val IDLE_READY_MESSAGE =
        "VPN-разрешение получено. Сервисы готовы к запуску."
    private const val IDLE_PREPARE_MESSAGE =
        "Перед запуском нужно разрешить VPN-подключение."

    private var state: RuntimeState = RuntimeState.IDLE
    private var message: String = IDLE_PREPARE_MESSAGE
    private var backendConnected: Boolean = true
    private var vpnPermissionGranted: Boolean = false
    private var serviceActive: Boolean = false
    private var strategyEngineReady: Boolean = false
    private var trafficForwarderReady: Boolean = false
    private var tunnelActive: Boolean = false
    private var packetCodecReady: Boolean = false
    private var udpForwarderReady: Boolean = false
    private var ipv6PacketCodecReady: Boolean = false
    private var ipv6UdpForwarderReady: Boolean = false
    private var tcpForwarderReady: Boolean = false
    private var activeProfileName: String = ""
    private var telegramCompatibilityProxyReady: Boolean = false
    private var telegramCompatibilitySetupRequired: Boolean = false
    private var telegramCompatibilityProxyEndpoint: String = ""
    private var telegramCompatibilityProxyMessage: String = ""
    private var telegramSidecarState: String = "idle"
    private var applicationContext: Context? = null
    private val listeners = CopyOnWriteArraySet<(Map<String, Any>) -> Unit>()

    fun addListener(context: Context, listener: (Map<String, Any>) -> Unit) {
        applicationContext = context.applicationContext
        listeners.add(listener)
        listener(snapshot(context))
    }

    fun removeListener(listener: (Map<String, Any>) -> Unit) {
        listeners.remove(listener)
    }

    private fun publish() {
        val context = applicationContext ?: return
        val value = snapshot(context)
        listeners.forEach { listener -> listener(value) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { TileService.requestListeningState(context,
                ComponentName(context, QnzapretQuickSettingsTileService::class.java)) }
        }
    }

    @Synchronized
    fun snapshot(context: Context): Map<String, Any> {
        applicationContext = context.applicationContext
        vpnPermissionGranted = VpnConsentStore.read(context)
        if (!serviceActive && state == RuntimeState.IDLE) {
            message = idleMessage()
        }

        return mapOf(
            "platform" to PLATFORM,
            "state" to state.wireValue,
            "message" to message,
            "backendConnected" to backendConnected,
            "vpnPermissionGranted" to vpnPermissionGranted,
            "serviceActive" to serviceActive,
            "strategyEngineReady" to strategyEngineReady,
            "trafficForwarderReady" to trafficForwarderReady,
            "tunnelActive" to tunnelActive,
            "trafficInterceptionMode" to if (tunnelActive) {
                "androidVpnTun"
            } else {
                "none"
            },
            "trafficInterceptionActive" to tunnelActive,
            "backendVersion" to "android-2",
            "telegramSidecarState" to telegramSidecarState,
            "degraded" to (telegramSidecarState == "failed"),
            "packetCodecReady" to packetCodecReady,
            "udpForwarderReady" to udpForwarderReady,
            "ipv6PacketCodecReady" to ipv6PacketCodecReady,
            "ipv6UdpForwarderReady" to ipv6UdpForwarderReady,
            "tcpForwarderReady" to tcpForwarderReady,
            "activeProfileName" to activeProfileName,
            "telegramCompatibilityProxyReady" to telegramCompatibilityProxyReady,
            "telegramCompatibilitySetupRequired" to telegramCompatibilitySetupRequired,
            "telegramCompatibilityProxyEndpoint" to telegramCompatibilityProxyEndpoint,
            "telegramCompatibilityProxyMessage" to telegramCompatibilityProxyMessage,
        )
    }

    @Synchronized
    fun onPrepareResult(context: Context, granted: Boolean): Map<String, Any> {
        VpnConsentStore.record(context, granted)
        vpnPermissionGranted = granted
        state = RuntimeState.IDLE
        serviceActive = false
        clearRuntimeDetails()
        message = if (granted) {
            "VPN-разрешение получено. Сервисы готовы к запуску."
        } else {
            "VPN-разрешение отклонено. Запуск останется заблокированным до подтверждения."
        }
        publish()
        return snapshot(context)
    }

    @Synchronized
    fun markStarting(newMessage: String) {
        state = RuntimeState.STARTING
        serviceActive = true
        clearRuntimeDetails()
        message = newMessage
        publish()
    }

    @Synchronized
    fun markRunning(
        newMessage: String,
        newStrategyEngineReady: Boolean = false,
        newTrafficForwarderReady: Boolean = false,
        newTunnelActive: Boolean = false,
        newPacketCodecReady: Boolean = false,
        newUdpForwarderReady: Boolean = false,
        newIpv6PacketCodecReady: Boolean = false,
        newIpv6UdpForwarderReady: Boolean = false,
        newTcpForwarderReady: Boolean = false,
        newActiveProfileName: String = "",
        newTelegramCompatibilityProxyReady: Boolean = false,
        newTelegramCompatibilitySetupRequired: Boolean = false,
        newTelegramCompatibilityProxyEndpoint: String = "",
        newTelegramCompatibilityProxyMessage: String = "",
        newTelegramSidecarState: String = "idle",
    ) {
        state = RuntimeState.RUNNING
        serviceActive = true
        strategyEngineReady = newStrategyEngineReady
        trafficForwarderReady = newTrafficForwarderReady
        tunnelActive = newTunnelActive
        packetCodecReady = newPacketCodecReady
        udpForwarderReady = newUdpForwarderReady
        ipv6PacketCodecReady = newIpv6PacketCodecReady
        ipv6UdpForwarderReady = newIpv6UdpForwarderReady
        tcpForwarderReady = newTcpForwarderReady
        activeProfileName = newActiveProfileName
        telegramCompatibilityProxyReady = newTelegramCompatibilityProxyReady
        telegramCompatibilitySetupRequired = newTelegramCompatibilitySetupRequired
        telegramCompatibilityProxyEndpoint = newTelegramCompatibilityProxyEndpoint
        telegramCompatibilityProxyMessage = newTelegramCompatibilityProxyMessage
        telegramSidecarState = newTelegramSidecarState
        message = newMessage
        publish()
    }

    @Synchronized
    fun updateTelegramCompatibility(
        ready: Boolean,
        setupRequired: Boolean,
        endpoint: String,
        telegramMessage: String,
        listenerReady: Boolean = ready,
    ) {
        if (state != RuntimeState.RUNNING && state != RuntimeState.STARTING) {
            return
        }
        telegramCompatibilityProxyReady = ready
        telegramCompatibilitySetupRequired = setupRequired
        telegramCompatibilityProxyEndpoint = endpoint
        telegramCompatibilityProxyMessage = telegramMessage
        telegramSidecarState = if (listenerReady) "running" else "failed"
        publish()
    }

    @Synchronized
    fun markStopping(newMessage: String) {
        state = RuntimeState.STOPPING
        serviceActive = true
        message = newMessage
        publish()
    }

    @Synchronized
    fun markIdle(context: Context, newMessage: String? = null) {
        vpnPermissionGranted = VpnConsentStore.read(context)
        state = RuntimeState.IDLE
        serviceActive = false
        clearRuntimeDetails()
        message = newMessage ?: idleMessage()
        publish()
    }

    @Synchronized
    fun markFailed(newMessage: String) {
        state = RuntimeState.FAILED
        serviceActive = false
        clearRuntimeDetails()
        message = newMessage
        publish()
    }

    private fun idleMessage(): String {
        return if (vpnPermissionGranted) IDLE_READY_MESSAGE else IDLE_PREPARE_MESSAGE
    }

    private fun clearRuntimeDetails() {
        strategyEngineReady = false
        trafficForwarderReady = false
        tunnelActive = false
        packetCodecReady = false
        udpForwarderReady = false
        ipv6PacketCodecReady = false
        ipv6UdpForwarderReady = false
        tcpForwarderReady = false
        activeProfileName = ""
        telegramCompatibilityProxyReady = false
        telegramCompatibilitySetupRequired = false
        telegramCompatibilityProxyEndpoint = ""
        telegramCompatibilityProxyMessage = ""
        telegramSidecarState = "idle"
    }

    private enum class RuntimeState(val wireValue: String) {
        IDLE("idle"),
        STARTING("starting"),
        RUNNING("running"),
        STOPPING("stopping"),
        FAILED("failed"),
    }
}
