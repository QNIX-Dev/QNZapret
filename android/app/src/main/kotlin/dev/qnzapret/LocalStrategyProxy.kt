package dev.qnzapret

internal data class LocalStrategyProxyEndpoint(
    val host: String,
    val port: Int,
)

internal class LocalStrategyProxy {
    private var endpoint: LocalStrategyProxyEndpoint? = null

    val isRunning: Boolean
        get() = endpoint != null

    fun start(config: VpnRuntimeConfig, plan: StrategyRuntimePlan): LocalStrategyProxyEndpoint {
        val proxyPort = if (config.localPort > 0) config.localPort else DEFAULT_PROXY_PORT
        val nextEndpoint = LocalStrategyProxyEndpoint(
            host = config.localHost.ifBlank { DEFAULT_PROXY_HOST },
            port = proxyPort,
        )

        endpoint = nextEndpoint
        return nextEndpoint
    }

    fun stop() {
        endpoint = null
    }

    private companion object {
        private const val DEFAULT_PROXY_HOST = "127.0.0.1"
        private const val DEFAULT_PROXY_PORT = 1080
    }
}
