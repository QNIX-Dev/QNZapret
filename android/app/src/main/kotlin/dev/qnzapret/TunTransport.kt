package dev.qnzapret

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.IOException

internal data class TunTransportState(
    val active: Boolean,
    val message: String,
)

internal class TunTransport(
    private val service: VpnService,
) {
    private var descriptor: ParcelFileDescriptor? = null

    fun start(
        config: VpnRuntimeConfig,
        proxyEndpoint: LocalStrategyProxyEndpoint,
    ): TunTransportState {
        if (!config.establishTunnel) {
            return TunTransportState(
                active = false,
                message = "TUN establishment is deferred until the userspace forwarder is wired.",
            )
        }

        val nextDescriptor = service.Builder()
            .setSession("QNZapret")
            .setMtu(config.tunnelMtu)
            .addAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(CLOUDFLARE_DNS)
            .establish()

        descriptor = nextDescriptor
        return if (nextDescriptor != null) {
            TunTransportState(
                active = true,
                message = "TUN fd established for local proxy ${proxyEndpoint.host}:${proxyEndpoint.port}.",
            )
        } else {
            TunTransportState(
                active = false,
                message = "Android returned no TUN fd for the requested VPN session.",
            )
        }
    }

    fun stop() {
        try {
            descriptor?.close()
        } catch (_: IOException) {
        } finally {
            descriptor = null
        }
    }

    private companion object {
        private const val TUN_IPV4_ADDRESS = "10.24.0.2"
        private const val TUN_IPV4_PREFIX_LENGTH = 32
        private const val CLOUDFLARE_DNS = "1.1.1.1"
    }
}
