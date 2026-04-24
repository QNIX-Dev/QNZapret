package dev.qnzapret

internal data class StrategyRuntimePlan(
    val profileId: String,
    val profileName: String,
    val unmatchedTrafficPolicy: UnmatchedTrafficPolicy,
    val ruleCount: Int,
    val tcpPorts: Set<Int>,
    val udpPorts: Set<Int>,
    val protocols: Set<StrategyProtocol>,
    val requiredBlobKeys: Set<String>,
)

internal object StrategyProfileCompiler {
    fun compile(profile: StrategyProfile): StrategyRuntimePlan {
        return StrategyRuntimePlan(
            profileId = profile.id,
            profileName = profile.name,
            unmatchedTrafficPolicy = profile.unmatchedTrafficPolicy,
            ruleCount = profile.rules.size,
            tcpPorts = profile.rules.flatMap { it.tcpPorts }.toSet(),
            udpPorts = profile.rules.flatMap { it.udpPorts }.toSet(),
            protocols = profile.rules.flatMap { it.protocols }.toSet(),
            requiredBlobKeys = profile.rules
                .flatMap { rule -> rule.actions.mapNotNull { it.blobKey } }
                .toSet(),
        )
    }
}
