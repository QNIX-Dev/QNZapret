package dev.qnzapret

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.IOException

internal data class TunTransportState(
    val active: Boolean,
    val forwarderReady: Boolean,
    val packetCodecReady: Boolean,
    val udpForwarderReady: Boolean,
    val tcpForwarderReady: Boolean,
    val message: String,
)

internal class TunTransport(
    private val service: VpnService,
    private val localProxy: LocalStrategyProxy,
) {
    private var descriptor: ParcelFileDescriptor? = null
    private var forwarder: TunPacketForwarder? = null

    fun start(
        config: VpnRuntimeConfig,
        proxyEndpoint: LocalStrategyProxyEndpoint,
    ): TunTransportState {
        val capabilities = TunPacketForwarder.CAPABILITIES
        if (!config.establishTunnel) {
            return TunTransportState(
                active = false,
                forwarderReady = capabilities.fullyReady,
                packetCodecReady = capabilities.packetCodecReady,
                udpForwarderReady = capabilities.udpForwarderReady,
                tcpForwarderReady = capabilities.tcpForwarderReady,
                message = "TUN establishment is deferred. Packet codec and UDP relay are ready; TCP relay is pending.",
            )
        }

        if (!capabilities.fullyReady) {
            return TunTransportState(
                active = false,
                forwarderReady = false,
                packetCodecReady = capabilities.packetCodecReady,
                udpForwarderReady = capabilities.udpForwarderReady,
                tcpForwarderReady = capabilities.tcpForwarderReady,
                message = "TUN establishment was requested for local proxy " +
                    "${proxyEndpoint.host}:${proxyEndpoint.port}, but TCP relay is pending.",
            )
        }

        val nextDescriptor = establishTunnel(config)
            ?: return TunTransportState(
                active = false,
                forwarderReady = false,
                packetCodecReady = capabilities.packetCodecReady,
                udpForwarderReady = capabilities.udpForwarderReady,
                tcpForwarderReady = capabilities.tcpForwarderReady,
                message = "Android returned no TUN fd for the requested VPN session.",
            )

        descriptor = nextDescriptor
        val nextForwarder = TunPacketForwarder(
            service = service,
            localProxy = localProxy,
            descriptor = nextDescriptor,
            mtu = config.tunnelMtu,
        )
        val forwarderStatus = nextForwarder.start()
        forwarder = nextForwarder

        return TunTransportState(
            active = true,
            forwarderReady = forwarderStatus.capabilities.fullyReady,
            packetCodecReady = forwarderStatus.capabilities.packetCodecReady,
            udpForwarderReady = forwarderStatus.capabilities.udpForwarderReady,
            tcpForwarderReady = forwarderStatus.capabilities.tcpForwarderReady,
            message = "TUN fd established for local proxy ${proxyEndpoint.host}:${proxyEndpoint.port}. " +
                forwarderStatus.message,
        )
    }

    fun stop() {
        try {
            forwarder?.stop()
            descriptor?.close()
        } catch (_: IOException) {
        } finally {
            forwarder = null
            descriptor = null
        }
    }

    @Suppress("unused")
    private fun establishTunnel(config: VpnRuntimeConfig): ParcelFileDescriptor? {
        return service.Builder()
            .setSession("QNZapret")
            .setMtu(config.tunnelMtu)
            .addAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(CLOUDFLARE_DNS)
            .establish()
    }

    private companion object {
        private const val TUN_IPV4_ADDRESS = "10.24.0.2"
        private const val TUN_IPV4_PREFIX_LENGTH = 32
        private const val CLOUDFLARE_DNS = "1.1.1.1"
    }
}
