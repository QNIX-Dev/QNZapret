package dev.qnzapret

internal data class VpnRuntimeConfig(
    val localHost: String = "127.0.0.1",
    val localPort: Int = 1080,
    val poolSize: Int = 0,
    val cloudflareEnabled: Boolean = false,
    val secret: String = "",
    val strategyProfile: StrategyProfile = StrategyProfileCodec.defaultLightweight(),
    val establishTunnel: Boolean = true,
    val tunnelMtu: Int = 8500,
)
