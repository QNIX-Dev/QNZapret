package dev.qnzapret

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.android.FlutterActivity

class MainActivity : FlutterActivity() {
    private lateinit var proxyRuntimeBridge: ProxyRuntimeBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPostNotificationsIfNeeded()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        proxyRuntimeBridge = ProxyRuntimeBridge(this, flutterEngine)
    }

    @Deprecated("Uses Activity result API compatibility for the VPN prepare flow.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (::proxyRuntimeBridge.isInitialized &&
            proxyRuntimeBridge.onActivityResult(requestCode, resultCode, data)
        ) {
            return
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }

        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS,
        )
    }

    private companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 4018
    }
}
