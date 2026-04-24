package dev.qnzapret

import android.content.Context

internal data class LocalStrategyProxyEndpoint(
    val host: String,
    val port: Int,
)

internal data class LocalStrategyProxyStatus(
    val engineReady: Boolean,
    val hostlistCount: Int,
    val blobCount: Int,
    val supportedProtocols: Set<StrategyProtocol>,
    val unmatchedTrafficPolicy: UnmatchedTrafficPolicy,
) {
    companion object {
        fun stopped(): LocalStrategyProxyStatus {
            return LocalStrategyProxyStatus(
                engineReady = false,
                hostlistCount = 0,
                blobCount = 0,
                supportedProtocols = emptySet(),
                unmatchedTrafficPolicy = UnmatchedTrafficPolicy.DIRECT,
            )
        }
    }
}

internal data class LocalStrategyProxyStartResult(
    val endpoint: LocalStrategyProxyEndpoint,
    val status: LocalStrategyProxyStatus,
)

internal class LocalStrategyProxy(
    private val context: Context,
) {
    private var endpoint: LocalStrategyProxyEndpoint? = null
    private var engine: StrategyRuntimeEngine? = null
    private var status: LocalStrategyProxyStatus = LocalStrategyProxyStatus.stopped()

    val isRunning: Boolean
        get() = endpoint != null

    val currentStatus: LocalStrategyProxyStatus
        get() = status

    fun start(config: VpnRuntimeConfig, plan: StrategyRuntimePlan): LocalStrategyProxyStartResult {
        val proxyPort = if (config.localPort > 0) config.localPort else DEFAULT_PROXY_PORT
        val nextEndpoint = LocalStrategyProxyEndpoint(
            host = config.localHost.ifBlank { DEFAULT_PROXY_HOST },
            port = proxyPort,
        )
        val assetBundle = StrategyAssetStore.load(context, config.strategyProfile)
        val nextEngine = StrategyRuntimeEngine(config.strategyProfile, assetBundle)
        val nextStatus = LocalStrategyProxyStatus(
            engineReady = true,
            hostlistCount = nextEngine.summary.hostlistCount,
            blobCount = nextEngine.summary.blobCount,
            supportedProtocols = nextEngine.summary.supportedProtocols,
            unmatchedTrafficPolicy = plan.unmatchedTrafficPolicy,
        )

        endpoint = nextEndpoint
        engine = nextEngine
        status = nextStatus
        return LocalStrategyProxyStartResult(
            endpoint = nextEndpoint,
            status = nextStatus,
        )
    }

    fun evaluate(probe: StrategyFlowProbe): StrategyDecision {
        return engine?.evaluate(probe) ?: StrategyDecision.direct(
            protocol = null,
            host = probe.knownHost,
            reason = "strategy_engine_not_started",
        )
    }

    fun stop() {
        endpoint = null
        engine = null
        status = LocalStrategyProxyStatus.stopped()
    }

    private companion object {
        private const val DEFAULT_PROXY_HOST = "127.0.0.1"
        private const val DEFAULT_PROXY_PORT = 1080
    }
}
