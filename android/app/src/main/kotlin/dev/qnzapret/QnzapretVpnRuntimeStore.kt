package dev.qnzapret

import android.content.Context
import android.net.VpnService

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

    @Synchronized
    fun snapshot(context: Context): Map<String, Any> {
        syncPermission(context)
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
            "backendVersion" to "android-1",
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
        vpnPermissionGranted = granted
        state = RuntimeState.IDLE
        serviceActive = false
        clearRuntimeDetails()
        message = if (granted) {
            "VPN-разрешение получено. Сервисы готовы к запуску."
        } else {
            "VPN-разрешение отклонено. Запуск останется заблокированным до подтверждения."
        }
        return snapshot(context)
    }

    @Synchronized
    fun markStarting(newMessage: String) {
        state = RuntimeState.STARTING
        serviceActive = true
        clearRuntimeDetails()
        message = newMessage
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
        message = newMessage
    }

    @Synchronized
    fun updateTelegramCompatibility(
        ready: Boolean,
        setupRequired: Boolean,
        endpoint: String,
        telegramMessage: String,
    ) {
        if (state != RuntimeState.RUNNING && state != RuntimeState.STARTING) {
            return
        }
        telegramCompatibilityProxyReady = ready
        telegramCompatibilitySetupRequired = setupRequired
        telegramCompatibilityProxyEndpoint = endpoint
        telegramCompatibilityProxyMessage = telegramMessage
    }

    @Synchronized
    fun markStopping(newMessage: String) {
        state = RuntimeState.STOPPING
        serviceActive = true
        message = newMessage
    }

    @Synchronized
    fun markIdle(context: Context, newMessage: String? = null) {
        syncPermission(context)
        state = RuntimeState.IDLE
        serviceActive = false
        clearRuntimeDetails()
        message = newMessage ?: idleMessage()
    }

    @Synchronized
    fun markFailed(newMessage: String) {
        state = RuntimeState.FAILED
        serviceActive = false
        clearRuntimeDetails()
        message = newMessage
    }

    @Synchronized
    private fun syncPermission(context: Context) {
        vpnPermissionGranted = VpnService.prepare(context) == null
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
    }

    private enum class RuntimeState(val wireValue: String) {
        IDLE("idle"),
        STARTING("starting"),
        RUNNING("running"),
        STOPPING("stopping"),
        FAILED("failed"),
    }
}
