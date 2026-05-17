package dev.qnzapret

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat

class QnzapretQuickSettingsTileService : TileService() {
    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { handleTileClick() }
            return
        }
        handleTileClick()
    }

    private fun handleTileClick() {
        val snapshot = QnzapretVpnRuntimeStore.snapshot(this)
        val state = snapshot["state"] as? String ?: STATE_IDLE
        val serviceActive = snapshot["serviceActive"] as? Boolean ?: false

        when {
            state == STATE_STOPPING -> updateTileState()
            serviceActive || state == STATE_STARTING || state == STATE_RUNNING -> stopRuntimeFromTile()
            VpnService.prepare(this) != null -> openMainActivityForConsent()
            else -> startRuntimeFromTile()
        }

        updateTileState()
    }

    private fun startRuntimeFromTile() {
        QnzapretVpnRuntimeStore.markStarting("Запускаем сервис обхода из Quick Settings.")
        try {
            ContextCompat.startForegroundService(
                this,
                QnzapretVpnService.createTileStartIntent(this, VpnRuntimeConfig()),
            )
            Log.d(LOG_TAG, "tile start requested")
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            QnzapretVpnRuntimeStore.markFailed(
                "Не удалось запустить сервис обхода из Quick Settings: $message.",
            )
            Log.d(LOG_TAG, "tile start failed ${error.javaClass.simpleName}:${error.message ?: "-"}")
        }
    }

    private fun stopRuntimeFromTile() {
        QnzapretVpnRuntimeStore.markStopping("Останавливаем сервис обхода из Quick Settings.")

        val stopDelivered = try {
            startService(QnzapretVpnService.createStopIntent(this)) != null
        } catch (error: Exception) {
            Log.d(LOG_TAG, "tile stop action failed ${error.javaClass.simpleName}:${error.message ?: "-"}")
            false
        }
        val stopRequested = try {
            stopService(Intent(this, QnzapretVpnService::class.java))
        } catch (error: Exception) {
            Log.d(LOG_TAG, "tile stopService failed ${error.javaClass.simpleName}:${error.message ?: "-"}")
            false
        }

        Log.d(LOG_TAG, "tile stop delivered=$stopDelivered stopRequested=$stopRequested")
        if (!stopDelivered && !stopRequested) {
            QnzapretVpnRuntimeStore.markIdle(this, "Сервис обхода уже остановлен.")
        }
    }

    private fun openMainActivityForConsent() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra(EXTRA_QS_TILE_CONSENT, true)
        collapseToActivity(intent)
        Log.d(LOG_TAG, "tile opened MainActivity for VPN consent")
    }

    private fun collapseToActivity(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                REQUEST_OPEN_FROM_TILE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
            return
        }

        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val snapshot = QnzapretVpnRuntimeStore.snapshot(this)
        val state = snapshot["state"] as? String ?: STATE_IDLE
        val permissionGranted = snapshot["vpnPermissionGranted"] as? Boolean ?: (VpnService.prepare(this) == null)
        val serviceActive = snapshot["serviceActive"] as? Boolean ?: false

        tile.label = "QNZapret"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !permissionGranted -> "Нужно разрешение"
                serviceActive && state == STATE_RUNNING -> "Активно"
                serviceActive && state == STATE_STARTING -> "Запуск"
                state == STATE_STOPPING -> "Остановка"
                state == STATE_FAILED -> "Ошибка"
                else -> "Остановлено"
            }
        }
        tile.state = when {
            state == STATE_FAILED -> Tile.STATE_UNAVAILABLE
            serviceActive && state != STATE_STOPPING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    private companion object {
        private const val LOG_TAG = "QNZapretTile"
        private const val STATE_IDLE = "idle"
        private const val STATE_STARTING = "starting"
        private const val STATE_RUNNING = "running"
        private const val STATE_STOPPING = "stopping"
        private const val STATE_FAILED = "failed"
        private const val REQUEST_OPEN_FROM_TILE = 4108
        private const val EXTRA_QS_TILE_CONSENT = "dev.qnzapret.extra.QS_TILE_CONSENT"
    }
}
