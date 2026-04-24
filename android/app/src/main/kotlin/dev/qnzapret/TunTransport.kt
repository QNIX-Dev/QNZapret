package dev.qnzapret

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.IOException

internal data class TunTransportState(
    val active: Boolean,
    val forwarderReady: Boolean,
    val packetCodecReady: Boolean,
    val udpForwarderReady: Boolean,
    val ipv6PacketCodecReady: Boolean,
    val ipv6UdpForwarderReady: Boolean,
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
                forwarderReady = false,
                packetCodecReady = capabilities.packetCodecReady,
                udpForwarderReady = capabilities.udpForwarderReady,
                ipv6PacketCodecReady = capabilities.ipv6PacketCodecReady,
                ipv6UdpForwarderReady = capabilities.ipv6UdpForwarderReady,
                tcpForwarderReady = capabilities.tcpForwarderReady,
                message = "Поднятие TUN отложено конфигурацией. IPv4/IPv6 codec, UDP relay и TCP relay готовы.",
            )
        }

        if (!capabilities.fullyReady) {
            return TunTransportState(
                active = false,
                forwarderReady = false,
                packetCodecReady = capabilities.packetCodecReady,
                udpForwarderReady = capabilities.udpForwarderReady,
                ipv6PacketCodecReady = capabilities.ipv6PacketCodecReady,
                ipv6UdpForwarderReady = capabilities.ipv6UdpForwarderReady,
                tcpForwarderReady = capabilities.tcpForwarderReady,
                message = "Запрошен TUN для local proxy ${proxyEndpoint.host}:${proxyEndpoint.port}, " +
                    "но часть возможностей передачи пока недоступна.",
            )
        }

        val nextDescriptor = establishTunnel(config)
            ?: return TunTransportState(
                active = false,
                forwarderReady = false,
                packetCodecReady = capabilities.packetCodecReady,
                udpForwarderReady = capabilities.udpForwarderReady,
                ipv6PacketCodecReady = capabilities.ipv6PacketCodecReady,
                ipv6UdpForwarderReady = capabilities.ipv6UdpForwarderReady,
                tcpForwarderReady = capabilities.tcpForwarderReady,
                message = "Android не вернул TUN fd для запрошенной VPN-сессии.",
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
            forwarderReady = forwarderStatus.running && forwarderStatus.capabilities.fullyReady,
            packetCodecReady = forwarderStatus.capabilities.packetCodecReady,
            udpForwarderReady = forwarderStatus.capabilities.udpForwarderReady,
            ipv6PacketCodecReady = forwarderStatus.capabilities.ipv6PacketCodecReady,
            ipv6UdpForwarderReady = forwarderStatus.capabilities.ipv6UdpForwarderReady,
            tcpForwarderReady = forwarderStatus.capabilities.tcpForwarderReady,
            message = "TUN fd поднят для local proxy ${proxyEndpoint.host}:${proxyEndpoint.port}. " +
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
            .addAddress(TUN_IPV6_ADDRESS, TUN_IPV6_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(CLOUDFLARE_DNS)
            .addDnsServer(CLOUDFLARE_IPV6_DNS)
            .establish()
    }

    private companion object {
        private const val TUN_IPV4_ADDRESS = "10.24.0.2"
        private const val TUN_IPV4_PREFIX_LENGTH = 32
        private const val TUN_IPV6_ADDRESS = "fd00:24::2"
        private const val TUN_IPV6_PREFIX_LENGTH = 128
        private const val CLOUDFLARE_DNS = "1.1.1.1"
        private const val CLOUDFLARE_IPV6_DNS = "2606:4700:4700::1111"
    }
}
