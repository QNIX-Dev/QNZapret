package dev.qnzapret

import org.json.JSONArray
import org.json.JSONObject

internal enum class StrategyProtocol(val wireValue: String) {
    HTTP("http"),
    TLS("tls"),
    QUIC("quic");

    companion object {
        fun fromWire(value: String?): StrategyProtocol {
            return values().firstOrNull { it.wireValue == value } ?: HTTP
        }
    }
}

internal enum class StrategyActionKind(val wireValue: String) {
    SPLIT("split"),
    FAKE("fake"),
    UDP_FAKE("udpFake");

    companion object {
        fun fromWire(value: String?): StrategyActionKind {
            return values().firstOrNull { it.wireValue == value } ?: SPLIT
        }
    }
}

internal enum class UnmatchedTrafficPolicy(val wireValue: String) {
    DIRECT("direct");

    companion object {
        fun fromWire(value: String?): UnmatchedTrafficPolicy {
            return values().firstOrNull { it.wireValue == value } ?: DIRECT
        }
    }
}

internal data class StrategyAction(
    val kind: StrategyActionKind,
    val position: Int?,
    val repeats: Int,
    val blobKey: String?,
)

internal data class StrategyRule(
    val id: String,
    val name: String,
    val tcpPorts: List<Int>,
    val udpPorts: List<Int>,
    val protocols: List<StrategyProtocol>,
    val hostlists: List<String>,
    val actions: List<StrategyAction>,
)

internal data class StrategyProfile(
    val id: String,
    val name: String,
    val description: String,
    val unmatchedTrafficPolicy: UnmatchedTrafficPolicy,
    val blobs: Map<String, String>,
    val rules: List<StrategyRule>,
)

internal object StrategyProfileCodec {
    fun defaultLightweight(): StrategyProfile {
        return StrategyProfile(
            id = "default-lightweight",
            name = "Default lightweight",
            description = "No-root VPN/proxy subset inspired by the base zapret profile.",
            unmatchedTrafficPolicy = UnmatchedTrafficPolicy.DIRECT,
            blobs = mapOf(
                "tls_google" to "qnzapret/payloads/tls_clienthello_www_google_com.bin",
                "quic_google" to "qnzapret/payloads/quic_initial_www_google_com.bin",
            ),
            rules = listOf(
                StrategyRule(
                    id = "http-hostlist-fake-split",
                    name = "HTTP hostlist fake + split",
                    tcpPorts = listOf(80),
                    udpPorts = emptyList(),
                    protocols = listOf(StrategyProtocol.HTTP),
                    hostlists = listOf(
                        "qnzapret/lists/list-general.txt",
                        "qnzapret/lists/list-user.txt",
                        "qnzapret/lists/list-google.txt",
                    ),
                    actions = listOf(
                        StrategyAction(
                            kind = StrategyActionKind.FAKE,
                            position = null,
                            repeats = 1,
                            blobKey = null,
                        ),
                        StrategyAction(
                            kind = StrategyActionKind.SPLIT,
                            position = 1,
                            repeats = 1,
                            blobKey = null,
                        ),
                    ),
                ),
                StrategyRule(
                    id = "tls-hostlist-split",
                    name = "TLS ClientHello split",
                    tcpPorts = listOf(443),
                    udpPorts = emptyList(),
                    protocols = listOf(StrategyProtocol.TLS),
                    hostlists = listOf(
                        "qnzapret/lists/list-general.txt",
                        "qnzapret/lists/list-user.txt",
                        "qnzapret/lists/list-google.txt",
                    ),
                    actions = listOf(
                        StrategyAction(
                            kind = StrategyActionKind.FAKE,
                            position = null,
                            repeats = 1,
                            blobKey = "tls_google",
                        ),
                        StrategyAction(
                            kind = StrategyActionKind.SPLIT,
                            position = 1,
                            repeats = 1,
                            blobKey = null,
                        ),
                    ),
                ),
                StrategyRule(
                    id = "quic-initial-fake",
                    name = "QUIC Initial fake",
                    tcpPorts = emptyList(),
                    udpPorts = listOf(443),
                    protocols = listOf(StrategyProtocol.QUIC),
                    hostlists = emptyList(),
                    actions = listOf(
                        StrategyAction(
                            kind = StrategyActionKind.UDP_FAKE,
                            position = null,
                            repeats = 1,
                            blobKey = "quic_google",
                        ),
                    ),
                ),
            ),
        )
    }

    fun fromMap(raw: Map<*, *>?): StrategyProfile {
        if (raw == null) {
            return defaultLightweight()
        }

        val fallback = defaultLightweight()
        val blobs = parseStringMap(raw["blobs"])
        val rules = parseMapList(raw["rules"]).map(::parseRule)

        return StrategyProfile(
            id = raw["id"] as? String ?: fallback.id,
            name = raw["name"] as? String ?: fallback.name,
            description = raw["description"] as? String ?: fallback.description,
            unmatchedTrafficPolicy = UnmatchedTrafficPolicy.fromWire(
                raw["unmatchedTrafficPolicy"] as? String,
            ),
            blobs = if (blobs.isEmpty()) fallback.blobs else blobs,
            rules = if (rules.isEmpty()) fallback.rules else rules,
        )
    }

    fun fromJson(raw: String?): StrategyProfile {
        if (raw.isNullOrBlank()) {
            return defaultLightweight()
        }

        return fromJsonObject(JSONObject(raw))
    }

    fun toJson(profile: StrategyProfile): String {
        return JSONObject()
            .put("id", profile.id)
            .put("name", profile.name)
            .put("description", profile.description)
            .put("unmatchedTrafficPolicy", profile.unmatchedTrafficPolicy.wireValue)
            .put("blobs", JSONObject(profile.blobs))
            .put("rules", JSONArray(profile.rules.map(::ruleToJson)))
            .toString()
    }

    private fun fromJsonObject(raw: JSONObject): StrategyProfile {
        val fallback = defaultLightweight()
        val blobs = raw.optJSONObject("blobs")?.let(::parseStringMap) ?: emptyMap()
        val rules = raw.optJSONArray("rules")?.let(::parseRules) ?: emptyList()

        return StrategyProfile(
            id = raw.optString("id", fallback.id),
            name = raw.optString("name", fallback.name),
            description = raw.optString("description", fallback.description),
            unmatchedTrafficPolicy = UnmatchedTrafficPolicy.fromWire(
                raw.optString("unmatchedTrafficPolicy", fallback.unmatchedTrafficPolicy.wireValue),
            ),
            blobs = if (blobs.isEmpty()) fallback.blobs else blobs,
            rules = if (rules.isEmpty()) fallback.rules else rules,
        )
    }

    private fun parseRule(raw: Map<*, *>): StrategyRule {
        return StrategyRule(
            id = raw["id"] as? String ?: "",
            name = raw["name"] as? String ?: "",
            tcpPorts = parseIntList(raw["tcpPorts"]),
            udpPorts = parseIntList(raw["udpPorts"]),
            protocols = parseStringList(raw["protocols"]).map(StrategyProtocol::fromWire),
            hostlists = parseStringList(raw["hostlists"]),
            actions = parseMapList(raw["actions"]).map(::parseAction),
        )
    }

    private fun parseAction(raw: Map<*, *>): StrategyAction {
        return StrategyAction(
            kind = StrategyActionKind.fromWire(raw["kind"] as? String),
            position = (raw["position"] as? Number)?.toInt(),
            repeats = (raw["repeats"] as? Number)?.toInt() ?: 1,
            blobKey = raw["blobKey"] as? String,
        )
    }

    private fun parseRules(raw: JSONArray): List<StrategyRule> {
        return buildList {
            for (index in 0 until raw.length()) {
                add(parseRule(raw.getJSONObject(index)))
            }
        }
    }

    private fun parseRule(raw: JSONObject): StrategyRule {
        return StrategyRule(
            id = raw.optString("id"),
            name = raw.optString("name"),
            tcpPorts = parseIntList(raw.optJSONArray("tcpPorts")),
            udpPorts = parseIntList(raw.optJSONArray("udpPorts")),
            protocols = parseStringList(raw.optJSONArray("protocols")).map(StrategyProtocol::fromWire),
            hostlists = parseStringList(raw.optJSONArray("hostlists")),
            actions = parseActions(raw.optJSONArray("actions") ?: JSONArray()),
        )
    }

    private fun parseActions(raw: JSONArray): List<StrategyAction> {
        return buildList {
            for (index in 0 until raw.length()) {
                val item = raw.getJSONObject(index)
                add(
                    StrategyAction(
                        kind = StrategyActionKind.fromWire(item.optString("kind")),
                        position = if (item.has("position")) item.optInt("position") else null,
                        repeats = item.optInt("repeats", 1),
                        blobKey = if (item.has("blobKey")) item.optString("blobKey") else null,
                    ),
                )
            }
        }
    }

    private fun ruleToJson(rule: StrategyRule): JSONObject {
        return JSONObject()
            .put("id", rule.id)
            .put("name", rule.name)
            .put("tcpPorts", JSONArray(rule.tcpPorts))
            .put("udpPorts", JSONArray(rule.udpPorts))
            .put("protocols", JSONArray(rule.protocols.map { it.wireValue }))
            .put("hostlists", JSONArray(rule.hostlists))
            .put("actions", JSONArray(rule.actions.map(::actionToJson)))
    }

    private fun actionToJson(action: StrategyAction): JSONObject {
        return JSONObject()
            .put("kind", action.kind.wireValue)
            .put("repeats", action.repeats)
            .apply {
                action.position?.let { put("position", it) }
                action.blobKey?.let { put("blobKey", it) }
            }
    }

    private fun parseStringMap(raw: Any?): Map<String, String> {
        return when (raw) {
            is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value.toString() }
            is JSONObject -> raw.keys().asSequence().associateWith { raw.optString(it) }
            else -> emptyMap()
        }
    }

    private fun parseMapList(raw: Any?): List<Map<*, *>> {
        if (raw !is Iterable<*>) {
            return emptyList()
        }

        return raw.filterIsInstance<Map<*, *>>()
    }

    private fun parseStringList(raw: Any?): List<String> {
        return when (raw) {
            is Iterable<*> -> raw.map { it.toString() }
            is JSONArray -> buildList {
                for (index in 0 until raw.length()) {
                    add(raw.getString(index))
                }
            }
            else -> emptyList()
        }
    }

    private fun parseIntList(raw: Any?): List<Int> {
        return when (raw) {
            is Iterable<*> -> raw.mapNotNull { (it as? Number)?.toInt() ?: it.toString().toIntOrNull() }
            is JSONArray -> buildList {
                for (index in 0 until raw.length()) {
                    add(raw.getInt(index))
                }
            }
            else -> emptyList()
        }
    }
}
