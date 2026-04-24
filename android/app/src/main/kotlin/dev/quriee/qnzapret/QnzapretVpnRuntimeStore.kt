package dev.quriee.qnzapret

import android.content.Context
import android.net.VpnService

object QnzapretVpnRuntimeStore {
    private var state: String = "idle"
    private var message: String = "Android runtime bridge is ready."
    private var vpnPermissionGrantedValue: Boolean = false
    private var serviceActiveValue: Boolean = false
    private var lastConfig: Map<*, *> = emptyMap<String, Any?>()

    val serviceActive: Boolean
        @Synchronized get() = serviceActiveValue

    @Synchronized
    fun setVpnPermissionGranted(granted: Boolean) {
        vpnPermissionGrantedValue = granted
    }

    @Synchronized
    fun setStarting(config: Map<*, *>) {
        state = "starting"
        message = "Android VPN service base is starting."
        vpnPermissionGrantedValue = true
        serviceActiveValue = false
        lastConfig = config
    }

    @Synchronized
    fun setRunning(message: String = "Android VPN service base is active.") {
        state = "running"
        this.message = message
        vpnPermissionGrantedValue = true
        serviceActiveValue = true
    }

    @Synchronized
    fun setStopping() {
        state = "stopping"
        message = "Android VPN service base is stopping."
    }

    @Synchronized
    fun setIdle(message: String = "Android VPN service base is idle.") {
        state = "idle"
        this.message = message
        serviceActiveValue = false
    }

    @Synchronized
    fun setFailed(message: String) {
        state = "failed"
        this.message = message
        serviceActiveValue = false
    }

    @Synchronized
    fun snapshot(context: Context): Map<String, Any?> {
        vpnPermissionGrantedValue = VpnService.prepare(context) == null
        return mapOf(
            "platform" to "android",
            "state" to state,
            "message" to message,
            "backendConnected" to true,
            "vpnPermissionGranted" to vpnPermissionGrantedValue,
            "serviceActive" to serviceActiveValue,
            "lastConfig" to lastConfig,
        )
    }
}
