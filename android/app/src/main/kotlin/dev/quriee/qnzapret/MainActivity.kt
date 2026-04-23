package dev.quriee.qnzapret

import android.content.Intent
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.android.FlutterActivity

class MainActivity : FlutterActivity() {
    private lateinit var proxyRuntimeBridge: ProxyRuntimeBridge

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
}
