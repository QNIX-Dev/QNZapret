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

internal enum class StrategyEndpointTransport(val wireValue: String) {
    TCP("tcp");

    companion object {
        fun fromWire(value: String?): StrategyEndpointTransport {
            return values().firstOrNull { it.wireValue == value } ?: TCP
        }
    }
}

internal enum class StrategyEndpointRouteKind(val wireValue: String) {
    REMOTE_RELAY("remoteRelay");

    companion object {
        fun fromWire(value: String?): StrategyEndpointRouteKind {
            return values().firstOrNull { it.wireValue == value } ?: REMOTE_RELAY
        }
    }
}

internal enum class StrategyRelayProtocol(val wireValue: String) {
    SOCKS5("socks5"),
    HTTPS_CONNECT("httpsConnect");

    companion object {
        fun fromWire(value: String?): StrategyRelayProtocol {
            return values().firstOrNull { it.wireValue == value } ?: SOCKS5
        }
    }
}

internal enum class StrategyEndpointFailureMode(val wireValue: String) {
    FAIL_CLOSED("failClosed");

    companion object {
        fun fromWire(value: String?): StrategyEndpointFailureMode {
            return values().firstOrNull { it.wireValue == value } ?: FAIL_CLOSED
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

internal data class StrategyRelayAuth(
    val username: String?,
    val password: String?,
)

internal data class StrategyEndpointRoute(
    val kind: StrategyEndpointRouteKind,
    val protocol: StrategyRelayProtocol,
    val host: String,
    val port: Int,
    val auth: StrategyRelayAuth?,
    val connectTimeoutMs: Int,
    val relayConnectTimeoutMs: Int,
    val failureMode: StrategyEndpointFailureMode,
)

internal data class StrategyEndpointPolicy(
    val id: String,
    val endpointClasses: List<String>,
    val transport: StrategyEndpointTransport,
    val route: StrategyEndpointRoute,
)

internal data class StrategyProfile(
    val id: String,
    val name: String,
    val description: String,
    val unmatchedTrafficPolicy: UnmatchedTrafficPolicy,
    val blobs: Map<String, String>,
    val endpointPolicies: List<StrategyEndpointPolicy> = emptyList(),
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
            endpointPolicies = emptyList(),
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
        val endpointPolicies = parseMapList(raw["endpointPolicies"]).map(::parseEndpointPolicy)
        val rules = parseMapList(raw["rules"]).map(::parseRule)

        return StrategyProfile(
            id = raw["id"] as? String ?: fallback.id,
            name = raw["name"] as? String ?: fallback.name,
            description = raw["description"] as? String ?: fallback.description,
            unmatchedTrafficPolicy = UnmatchedTrafficPolicy.fromWire(
                raw["unmatchedTrafficPolicy"] as? String,
            ),
            blobs = if (blobs.isEmpty()) fallback.blobs else blobs,
            endpointPolicies = endpointPolicies,
            rules = if (rules.isEmpty()) fallback.rules else rules,
        )
    }

    fun fromJson(raw: String?): StrategyProfile {
        if (raw.isNullOrBlank()) {
            return defaultLightweight()
        }

        return fromJsonObject(JSONObject(raw))
    }

    fun endpointPoliciesFromJson(raw: String?): List<StrategyEndpointPolicy> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        val root = JSONObject(raw)
        return when {
            root.has("endpointPolicies") -> {
                root.optJSONArray("endpointPolicies")?.let(::parseEndpointPolicies) ?: emptyList()
            }
            root.has("route") -> listOf(parseEndpointPolicy(root))
            else -> emptyList()
        }
    }

    fun toJson(profile: StrategyProfile): String {
        return JSONObject()
            .put("id", profile.id)
            .put("name", profile.name)
            .put("description", profile.description)
            .put("unmatchedTrafficPolicy", profile.unmatchedTrafficPolicy.wireValue)
            .put("blobs", JSONObject(profile.blobs))
            .apply {
                if (profile.endpointPolicies.isNotEmpty()) {
                    put("endpointPolicies", JSONArray(profile.endpointPolicies.map(::endpointPolicyToJson)))
                }
            }
            .put("rules", JSONArray(profile.rules.map(::ruleToJson)))
            .toString()
    }

    private fun fromJsonObject(raw: JSONObject): StrategyProfile {
        val fallback = defaultLightweight()
        val blobs = raw.optJSONObject("blobs")?.let(::parseStringMap) ?: emptyMap()
        val endpointPolicies = raw.optJSONArray("endpointPolicies")?.let(::parseEndpointPolicies) ?: emptyList()
        val rules = raw.optJSONArray("rules")?.let(::parseRules) ?: emptyList()

        return StrategyProfile(
            id = raw.optString("id", fallback.id),
            name = raw.optString("name", fallback.name),
            description = raw.optString("description", fallback.description),
            unmatchedTrafficPolicy = UnmatchedTrafficPolicy.fromWire(
                raw.optString("unmatchedTrafficPolicy", fallback.unmatchedTrafficPolicy.wireValue),
            ),
            blobs = if (blobs.isEmpty()) fallback.blobs else blobs,
            endpointPolicies = endpointPolicies,
            rules = if (rules.isEmpty()) fallback.rules else rules,
        )
    }

    private fun parseEndpointPolicy(raw: Map<*, *>): StrategyEndpointPolicy {
        return StrategyEndpointPolicy(
            id = raw["id"] as? String ?: "",
            endpointClasses = parseStringList(raw["endpointClasses"]),
            transport = StrategyEndpointTransport.fromWire(raw["transport"] as? String),
            route = parseEndpointRoute(raw["route"]),
        )
    }

    private fun parseEndpointPolicy(raw: JSONObject): StrategyEndpointPolicy {
        return StrategyEndpointPolicy(
            id = raw.optString("id"),
            endpointClasses = parseStringList(raw.optJSONArray("endpointClasses")),
            transport = StrategyEndpointTransport.fromWire(raw.optString("transport")),
            route = parseEndpointRoute(raw.optJSONObject("route") ?: JSONObject()),
        )
    }

    private fun parseEndpointRoute(raw: Any?): StrategyEndpointRoute {
        return when (raw) {
            is Map<*, *> -> parseEndpointRoute(raw)
            is JSONObject -> parseEndpointRoute(raw)
            else -> parseEndpointRoute(emptyMap<Any?, Any?>())
        }
    }

    private fun parseEndpointRoute(raw: Map<*, *>): StrategyEndpointRoute {
        return StrategyEndpointRoute(
            kind = StrategyEndpointRouteKind.fromWire(raw["kind"] as? String),
            protocol = StrategyRelayProtocol.fromWire(raw["protocol"] as? String),
            host = raw["host"] as? String ?: "",
            port = (raw["port"] as? Number)?.toInt() ?: 0,
            auth = parseRelayAuth(raw["auth"]),
            connectTimeoutMs = (raw["connectTimeoutMs"] as? Number)?.toInt() ?: DEFAULT_RELAY_CONNECT_MS,
            relayConnectTimeoutMs = (raw["relayConnectTimeoutMs"] as? Number)?.toInt()
                ?: DEFAULT_RELAY_HANDSHAKE_MS,
            failureMode = StrategyEndpointFailureMode.fromWire(raw["failureMode"] as? String),
        )
    }

    private fun parseEndpointRoute(raw: JSONObject): StrategyEndpointRoute {
        return StrategyEndpointRoute(
            kind = StrategyEndpointRouteKind.fromWire(raw.optString("kind")),
            protocol = StrategyRelayProtocol.fromWire(raw.optString("protocol")),
            host = raw.optString("host"),
            port = raw.optInt("port", 0),
            auth = raw.optJSONObject("auth")?.let(::parseRelayAuth),
            connectTimeoutMs = raw.optInt("connectTimeoutMs", DEFAULT_RELAY_CONNECT_MS),
            relayConnectTimeoutMs = raw.optInt("relayConnectTimeoutMs", DEFAULT_RELAY_HANDSHAKE_MS),
            failureMode = StrategyEndpointFailureMode.fromWire(raw.optString("failureMode")),
        )
    }

    private fun parseRelayAuth(raw: Any?): StrategyRelayAuth? {
        return when (raw) {
            is Map<*, *> -> parseRelayAuth(raw)
            is JSONObject -> parseRelayAuth(raw)
            else -> null
        }
    }

    private fun parseRelayAuth(raw: Map<*, *>): StrategyRelayAuth? {
        val username = raw["username"] as? String
        val password = raw["password"] as? String
        if (username.isNullOrEmpty() && password.isNullOrEmpty()) {
            return null
        }
        return StrategyRelayAuth(username = username, password = password)
    }

    private fun parseRelayAuth(raw: JSONObject): StrategyRelayAuth? {
        val username = raw.optString("username").takeIf { it.isNotEmpty() }
        val password = raw.optString("password").takeIf { it.isNotEmpty() }
        if (username == null && password == null) {
            return null
        }
        return StrategyRelayAuth(username = username, password = password)
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

    private fun parseEndpointPolicies(raw: JSONArray): List<StrategyEndpointPolicy> {
        return buildList {
            for (index in 0 until raw.length()) {
                add(parseEndpointPolicy(raw.getJSONObject(index)))
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

    private fun endpointPolicyToJson(policy: StrategyEndpointPolicy): JSONObject {
        return JSONObject()
            .put("id", policy.id)
            .put("endpointClasses", JSONArray(policy.endpointClasses))
            .put("transport", policy.transport.wireValue)
            .put("route", endpointRouteToJson(policy.route))
    }

    private fun endpointRouteToJson(route: StrategyEndpointRoute): JSONObject {
        return JSONObject()
            .put("kind", route.kind.wireValue)
            .put("protocol", route.protocol.wireValue)
            .put("host", route.host)
            .put("port", route.port)
            .put("connectTimeoutMs", route.connectTimeoutMs)
            .put("relayConnectTimeoutMs", route.relayConnectTimeoutMs)
            .put("failureMode", route.failureMode.wireValue)
            .apply {
                route.auth?.let { auth ->
                    put("auth", relayAuthToJson(auth))
                }
            }
    }

    private fun relayAuthToJson(auth: StrategyRelayAuth): JSONObject {
        return JSONObject().apply {
            auth.username?.let { put("username", it) }
            auth.password?.let { put("password", it) }
        }
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

    private const val DEFAULT_RELAY_CONNECT_MS = 3_000
    private const val DEFAULT_RELAY_HANDSHAKE_MS = 5_000
}
