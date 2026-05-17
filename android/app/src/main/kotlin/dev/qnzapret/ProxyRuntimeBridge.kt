package dev.qnzapret

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class ProxyRuntimeBridge(
    private val activity: FlutterActivity,
    flutterEngine: FlutterEngine,
) : MethodChannel.MethodCallHandler {
    private val context = activity.applicationContext
    private val channel = MethodChannel(
        flutterEngine.dartExecutor.binaryMessenger,
        CHANNEL_NAME,
    )

    private var pendingPrepareResult: MethodChannel.Result? = null

    init {
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "prepare" -> handlePrepare(result)
            "getSnapshot" -> result.success(QnzapretVpnRuntimeStore.snapshot(context))
            "start" -> handleStart(call, result)
            "stop" -> handleStop(result)
            else -> result.notImplemented()
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_PREPARE_VPN) {
            return false
        }

        val pendingResult = pendingPrepareResult ?: return false
        pendingPrepareResult = null

        val granted = resultCode == Activity.RESULT_OK
        val response = QnzapretVpnRuntimeStore.onPrepareResult(context, granted)
        pendingResult.success(
            mapOf(
                "granted" to granted,
                "message" to response["message"],
            ),
        )
        return true
    }

    private fun handlePrepare(result: MethodChannel.Result) {
        if (pendingPrepareResult != null) {
            result.error(
                "vpn_prepare_in_progress",
                "Запрос VPN-разрешения уже открыт.",
                null,
            )
            return
        }

        val prepareIntent = VpnService.prepare(activity)
        if (prepareIntent == null) {
            val response = QnzapretVpnRuntimeStore.onPrepareResult(context, true)
            result.success(
                mapOf(
                    "granted" to true,
                    "message" to response["message"],
                ),
            )
            return
        }

        pendingPrepareResult = result
        activity.startActivityForResult(prepareIntent, REQUEST_PREPARE_VPN)
    }

    private fun handleStart(call: MethodCall, result: MethodChannel.Result) {
        if (VpnService.prepare(context) != null) {
            result.error(
                "vpn_permission_required",
                "Перед запуском нужно разрешить VPN-подключение.",
                null,
            )
            return
        }

        val rawConfig = call.argument<Map<String, Any?>>("config") ?: emptyMap()
        val config = VpnRuntimeConfig(
            localHost = rawConfig["localHost"] as? String ?: "127.0.0.1",
            localPort = (rawConfig["localPort"] as? Number)?.toInt() ?: 0,
            poolSize = (rawConfig["poolSize"] as? Number)?.toInt() ?: 0,
            cloudflareEnabled = rawConfig["cloudflareEnabled"] as? Boolean ?: false,
            secret = rawConfig["secret"] as? String ?: "",
            strategyProfile = StrategyProfileCodec.fromMap(
                rawConfig["strategyProfile"] as? Map<*, *>,
            ),
            establishTunnel = rawConfig["establishTunnel"] as? Boolean ?: true,
            tunnelMtu = (rawConfig["tunnelMtu"] as? Number)?.toInt() ?: 8500,
        )

        QnzapretVpnRuntimeStore.markStarting(
            "Запускаем сервис обхода на ${config.localHost}:${config.localPort}.",
        )

        try {
            ContextCompat.startForegroundService(
                context,
                QnzapretVpnService.createUiStartIntent(context, config),
            )
            result.success(null)
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            QnzapretVpnRuntimeStore.markFailed(
                "Не удалось запустить сервис обхода: $message.",
            )
            result.error(
                "vpn_start_failed",
                "Не удалось запустить сервис обхода: $message.",
                null,
            )
        }
    }

    private fun handleStop(result: MethodChannel.Result) {
        Log.d(LOG_TAG, "stop requested")
        QnzapretVpnRuntimeStore.markStopping("Останавливаем сервис обхода.")

        val explicitServiceIntent = Intent(context, QnzapretVpnService::class.java)
        val stopDelivered = try {
            context.startService(QnzapretVpnService.createStopIntent(context)) != null
        } catch (error: Exception) {
            Log.d(
                LOG_TAG,
                "stop action failed: ${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            false
        }
        val stopRequested = try {
            context.stopService(explicitServiceIntent)
        } catch (error: Exception) {
            Log.d(
                LOG_TAG,
                "stopService failed: ${error.javaClass.simpleName}:${error.message ?: "-"}",
            )
            false
        }

        Log.d(LOG_TAG, "stop command delivered=$stopDelivered stopRequested=$stopRequested")

        if (!stopDelivered && !stopRequested) {
            QnzapretVpnRuntimeStore.markIdle(
                context,
                "Сервис обхода уже остановлен.",
            )
        }
        result.success(null)
    }

    private companion object {
        private const val CHANNEL_NAME = "dev.qnzapret/proxy_runtime"
        private const val REQUEST_PREPARE_VPN = 4017
        private const val LOG_TAG = "QNZapretBridge"
    }
}
