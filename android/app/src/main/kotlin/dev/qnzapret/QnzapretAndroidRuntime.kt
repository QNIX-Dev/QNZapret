package dev.qnzapret

import android.net.VpnService

internal data class AndroidRuntimeStartResult(
    val plan: StrategyRuntimePlan,
    val assetReport: StrategyAssetReport,
    val proxyEndpoint: LocalStrategyProxyEndpoint,
    val proxyStatus: LocalStrategyProxyStatus,
    val tunState: TunTransportState,
)

internal class QnzapretAndroidRuntime(
    private val service: VpnService,
) {
    private val localProxy = LocalStrategyProxy(service)
    private val tunTransport = TunTransport(service, localProxy)

    fun start(config: VpnRuntimeConfig): AndroidRuntimeStartResult {
        val plan = StrategyProfileCompiler.compile(config.strategyProfile)
        val assetReport = StrategyAssetVerifier.verify(service, config.strategyProfile)
        if (!assetReport.isComplete) {
            throw IllegalStateException(
                "Missing strategy assets: ${assetReport.missingPaths.sorted().joinToString()}",
            )
        }

        val proxyStartResult = localProxy.start(config, plan)
        val tunState = tunTransport.start(config, proxyStartResult.endpoint)

        return AndroidRuntimeStartResult(
            plan = plan,
            assetReport = assetReport,
            proxyEndpoint = proxyStartResult.endpoint,
            proxyStatus = proxyStartResult.status,
            tunState = tunState,
        )
    }

    fun stop() {
        tunTransport.stop()
        localProxy.stop()
    }
}
