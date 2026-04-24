package dev.qnzapret

internal enum class StrategyTransport {
    TCP,
    UDP,
}

internal enum class StrategyDecisionKind {
    DIRECT,
    DESYNC,
}

internal data class StrategyFlowProbe(
    val transport: StrategyTransport,
    val destinationPort: Int,
    val payload: ByteArray,
    val knownHost: String? = null,
)

internal data class ResolvedStrategyAction(
    val kind: StrategyActionKind,
    val position: Int?,
    val repeats: Int,
    val blobKey: String?,
    val blobPayload: ByteArray?,
)

internal data class StrategyDecision(
    val kind: StrategyDecisionKind,
    val protocol: StrategyProtocol?,
    val host: String?,
    val ruleId: String?,
    val actions: List<ResolvedStrategyAction>,
    val reason: String,
) {
    companion object {
        fun direct(protocol: StrategyProtocol?, host: String?, reason: String): StrategyDecision {
            return StrategyDecision(
                kind = StrategyDecisionKind.DIRECT,
                protocol = protocol,
                host = host,
                ruleId = null,
                actions = emptyList(),
                reason = reason,
            )
        }
    }
}

internal data class StrategyEngineSummary(
    val profileId: String,
    val profileName: String,
    val unmatchedTrafficPolicy: UnmatchedTrafficPolicy,
    val hostlistCount: Int,
    val blobCount: Int,
    val supportedProtocols: Set<StrategyProtocol>,
)

internal class StrategyRuntimeEngine(
    private val profile: StrategyProfile,
    private val assets: StrategyAssetBundle,
) {
    val summary: StrategyEngineSummary = StrategyEngineSummary(
        profileId = profile.id,
        profileName = profile.name,
        unmatchedTrafficPolicy = profile.unmatchedTrafficPolicy,
        hostlistCount = assets.hostlistCount,
        blobCount = assets.blobCount,
        supportedProtocols = profile.rules.flatMap { it.protocols }.toSet(),
    )

    fun evaluate(probe: StrategyFlowProbe): StrategyDecision {
        val protocol = detectProtocol(probe)
        val host = probe.knownHost ?: detectHost(protocol, probe.payload)
        if (protocol == null) {
            return StrategyDecision.direct(
                protocol = null,
                host = host,
                reason = "protocol_not_recognized",
            )
        }

        val transportRules = profile.rules.filter { candidate ->
            candidate.matchesTransportAndProtocol(probe.transport, probe.destinationPort, protocol)
        }
        if (transportRules.isEmpty()) {
            return StrategyDecision.direct(
                protocol = protocol,
                host = host,
                reason = "no_rule_for_transport",
            )
        }

        if (host == null && transportRules.any { it.hostlists.isNotEmpty() }) {
            return StrategyDecision.direct(
                protocol = protocol,
                host = null,
                reason = "host_not_detected",
            )
        }

        val rule = transportRules.firstOrNull { candidate -> candidate.matchesHost(host) }
        return if (rule == null) {
            StrategyDecision.direct(
                protocol = protocol,
                host = host,
                reason = "host_not_in_strategy_lists",
            )
        } else {
            StrategyDecision(
                kind = StrategyDecisionKind.DESYNC,
                protocol = protocol,
                host = host,
                ruleId = rule.id,
                actions = rule.actions.map(::resolveAction),
                reason = "matched_strategy_rule",
            )
        }
    }

    private fun detectProtocol(probe: StrategyFlowProbe): StrategyProtocol? {
        return when (probe.transport) {
            StrategyTransport.TCP -> when {
                probe.destinationPort == 80 && HttpHostDetector.detectHost(probe.payload) != null -> {
                    StrategyProtocol.HTTP
                }
                probe.destinationPort == 443 && TlsClientHelloDetector.detectSni(probe.payload) != null -> {
                    StrategyProtocol.TLS
                }
                else -> null
            }
            StrategyTransport.UDP -> when {
                probe.destinationPort == 443 && QuicInitialDetector.isLikelyInitial(probe.payload) -> {
                    StrategyProtocol.QUIC
                }
                else -> null
            }
        }
    }

    private fun detectHost(protocol: StrategyProtocol?, payload: ByteArray): String? {
        return when (protocol) {
            StrategyProtocol.HTTP -> HttpHostDetector.detectHost(payload)
            StrategyProtocol.TLS -> TlsClientHelloDetector.detectSni(payload)
            StrategyProtocol.QUIC -> null
            null -> null
        }
    }

    private fun StrategyRule.matchesTransportAndProtocol(
        transport: StrategyTransport,
        destinationPort: Int,
        protocol: StrategyProtocol?,
    ): Boolean {
        val portMatches = when (transport) {
            StrategyTransport.TCP -> destinationPort in tcpPorts
            StrategyTransport.UDP -> destinationPort in udpPorts
        }

        return portMatches && protocol != null && protocol in protocols
    }

    private fun StrategyRule.matchesHost(host: String?): Boolean {
        if (hostlists.isEmpty()) {
            return true
        }

        return hostlists.any { path -> assets.hostlists[path]?.matches(host) == true }
    }

    private fun resolveAction(action: StrategyAction): ResolvedStrategyAction {
        return ResolvedStrategyAction(
            kind = action.kind,
            position = action.position,
            repeats = action.repeats,
            blobKey = action.blobKey,
            blobPayload = action.blobKey?.let { assets.blobs[it] },
        )
    }
}
