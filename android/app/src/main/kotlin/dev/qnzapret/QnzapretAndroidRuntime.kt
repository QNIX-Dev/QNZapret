package dev.qnzapret

import android.net.VpnService

internal data class AndroidRuntimeStartResult(
    val plan: StrategyRuntimePlan,
    val assetReport: StrategyAssetReport,
    val proxyEndpoint: LocalStrategyProxyEndpoint,
    val tunState: TunTransportState,
)

internal class QnzapretAndroidRuntime(
    private val service: VpnService,
) {
    private val localProxy = LocalStrategyProxy()
    private val tunTransport = TunTransport(service)

    fun start(config: VpnRuntimeConfig): AndroidRuntimeStartResult {
        val plan = StrategyProfileCompiler.compile(config.strategyProfile)
        val assetReport = StrategyAssetVerifier.verify(service, config.strategyProfile)
        val proxyEndpoint = localProxy.start(config, plan)
        val tunState = tunTransport.start(config, proxyEndpoint)

        return AndroidRuntimeStartResult(
            plan = plan,
            assetReport = assetReport,
            proxyEndpoint = proxyEndpoint,
            tunState = tunState,
        )
    }

    fun stop() {
        tunTransport.stop()
        localProxy.stop()
    }
}
