package dev.qnzapret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyProfileCodecTest {
    @Test
    fun legacyProfileDefaultsEndpointPoliciesToEmpty() {
        val profile = StrategyProfileCodec.fromMap(
            mapOf(
                "id" to "legacy",
                "name" to "Legacy",
                "description" to "No endpoint policy field.",
            ),
        )

        assertTrue(profile.endpointPolicies.isEmpty())
        assertEquals(StrategyProfileCodec.defaultLightweight().rules.size, profile.rules.size)
    }

    @Test
    fun endpointPoliciesParseFromMethodChannelMap() {
        val parsed = StrategyProfileCodec.fromMap(
            mapOf(
                "endpointPolicies" to listOf(
                    mapOf(
                        "id" to "telegram-remote-relay",
                        "endpointClasses" to listOf("telegram", "telegram_host", "mtproto_port"),
                        "transport" to "tcp",
                        "route" to mapOf(
                            "kind" to "remoteRelay",
                            "protocol" to "socks5",
                            "host" to "relay.example.net",
                            "port" to 1080,
                            "auth" to mapOf(
                                "username" to "user",
                                "password" to "pass",
                            ),
                            "connectTimeoutMs" to 3_000,
                            "relayConnectTimeoutMs" to 5_000,
                            "failureMode" to "failClosed",
                        ),
                    ),
                ),
            ),
        )

        val policy = parsed.endpointPolicies.single()
        val route = policy.route

        assertEquals(listOf("telegram", "telegram_host", "mtproto_port"), policy.endpointClasses)
        assertEquals(StrategyEndpointTransport.TCP, policy.transport)
        assertEquals(StrategyEndpointRouteKind.REMOTE_RELAY, route.kind)
        assertEquals(StrategyRelayProtocol.SOCKS5, route.protocol)
        assertEquals("relay.example.net", route.host)
        assertEquals(1080, route.port)
        assertEquals("user", route.auth?.username)
        assertEquals("pass", route.auth?.password)
        assertEquals(StrategyEndpointFailureMode.FAIL_CLOSED, route.failureMode)
    }
}
