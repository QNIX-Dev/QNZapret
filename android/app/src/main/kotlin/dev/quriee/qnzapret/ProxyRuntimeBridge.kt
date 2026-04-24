package dev.quriee.qnzapret

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class ProxyRuntimeBridge(
    private val activity: MainActivity,
) : MethodChannel.MethodCallHandler {
    private var pendingPrepareResult: MethodChannel.Result? = null

    fun register(flutterEngine: FlutterEngine) {
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL,
        ).setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "prepare" -> prepare(result)
            "getSnapshot" -> result.success(QnzapretVpnRuntimeStore.snapshot(activity))
            "start" -> start(call, result)
            "stop" -> stop(result)
            else -> result.notImplemented()
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != VPN_PREPARE_REQUEST_CODE) {
            return false
        }

        val result = pendingPrepareResult ?: return true
        pendingPrepareResult = null

        val granted = resultCode == Activity.RESULT_OK || VpnService.prepare(activity) == null
        QnzapretVpnRuntimeStore.setVpnPermissionGranted(granted)
        result.success(
            mapOf(
                "granted" to granted,
                "message" to if (granted) {
                    "VPN permission granted. Android service base is ready to start."
                } else {
                    "VPN permission was not granted."
                },
            ),
        )
        return true
    }

    private fun prepare(result: MethodChannel.Result) {
        val prepareIntent = VpnService.prepare(activity)
        if (prepareIntent == null) {
            QnzapretVpnRuntimeStore.setVpnPermissionGranted(true)
            result.success(
                mapOf(
                    "granted" to true,
                    "message" to "VPN permission already granted. Android service base is ready.",
                ),
            )
            return
        }

        if (pendingPrepareResult != null) {
            result.error(
                "vpn_prepare_in_progress",
                "VPN permission request is already in progress.",
                null,
            )
            return
        }

        pendingPrepareResult = result
        activity.startActivityForResult(prepareIntent, VPN_PREPARE_REQUEST_CODE)
    }

    private fun start(call: MethodCall, result: MethodChannel.Result) {
        if (VpnService.prepare(activity) != null) {
            QnzapretVpnRuntimeStore.setVpnPermissionGranted(false)
            result.error(
                "vpn_permission_required",
                "VPN permission is required before starting runtime.",
                null,
            )
            return
        }

        val arguments = call.arguments as? Map<*, *>
        val config = arguments?.get("config") as? Map<*, *> ?: emptyMap<String, Any?>()
        QnzapretVpnRuntimeStore.setStarting(config)

        val intent = Intent(activity, QnzapretVpnService::class.java).apply {
            putExtra("localHost", config["localHost"] as? String ?: "127.0.0.1")
            putExtra("localPort", (config["localPort"] as? Number)?.toInt() ?: 1080)
            putExtra("poolSize", (config["poolSize"] as? Number)?.toInt() ?: 8)
            putExtra("cloudflareEnabled", config["cloudflareEnabled"] as? Boolean ?: true)
            putExtra("secret", config["secret"] as? String ?: "")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(intent)
        } else {
            activity.startService(intent)
        }
        result.success(null)
    }

    private fun stop(result: MethodChannel.Result) {
        val wasActive = QnzapretVpnRuntimeStore.serviceActive
        QnzapretVpnRuntimeStore.setStopping()
        activity.stopService(Intent(activity, QnzapretVpnService::class.java))
        if (!wasActive) {
            QnzapretVpnRuntimeStore.setIdle("Android VPN service base is idle.")
        }
        result.success(null)
    }

    private companion object {
        const val CHANNEL = "dev.quriee.qnzapret/proxy_runtime"
        const val VPN_PREPARE_REQUEST_CODE = 4201
    }
}
