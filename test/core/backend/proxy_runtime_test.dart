import 'package:flutter_test/flutter_test.dart';
import 'package:qnzapret/core/backend/proxy_runtime.dart';

void main() {
  test('launch config serializes to platform payload', () {
    const config = ProxyLaunchConfig(
      localHost: '127.0.0.1',
      localPort: 1080,
      poolSize: 8,
      cloudflareEnabled: true,
      secret: 'token',
    );

    expect(config.toMap(), {
      'localHost': '127.0.0.1',
      'localPort': 1080,
      'poolSize': 8,
      'cloudflareEnabled': true,
      'secret': 'token',
      'strategyProfile': StrategyProfile.defaultLightweight.toMap(),
      'establishTunnel': true,
      'tunnelMtu': 8500,
    });
  });

  test('default strategy profile carries HTTP TLS and QUIC rules', () {
    const profile = StrategyProfile.defaultLightweight;

    expect(profile.id, 'default-lightweight');
    expect(profile.unmatchedTrafficPolicy, UnmatchedTrafficPolicy.direct);
    expect(profile.rules, hasLength(3));
    expect(profile.rules[0].protocols, [StrategyProtocol.http]);
    expect(profile.rules[0].actions.map((action) => action.kind), [
      StrategyActionKind.fake,
      StrategyActionKind.split,
    ]);
    expect(profile.rules[0].actions.last.position, 1);
    expect(profile.rules[1].actions.map((action) => action.kind), [
      StrategyActionKind.fake,
      StrategyActionKind.split,
    ]);
    expect(profile.rules[2].udpPorts, [443]);
    expect(profile.rules[2].hostlists, isEmpty);
  });

  test('strategy profile roundtrips through platform map', () {
    final profile = StrategyProfile.fromMap(
      StrategyProfile.defaultLightweight.toMap(),
    );

    expect(profile.id, StrategyProfile.defaultLightweight.id);
    expect(
      profile.unmatchedTrafficPolicy,
      StrategyProfile.defaultLightweight.unmatchedTrafficPolicy,
    );
    expect(profile.blobs['tls_google'], contains('tls_clienthello'));
    expect(
      profile.blobs['quic_google'],
      'qnzapret/payloads/quic_initial_www_google_com.bin',
    );
    expect(
      profile.rules.first.hostlists.first,
      'qnzapret/lists/list-general.txt',
    );
    expect(profile.rules.last.protocols, [StrategyProtocol.quic]);
    expect(profile.rules.last.actions.single.kind, StrategyActionKind.udpFake);
    expect(profile.endpointPolicies, isEmpty);
  });

  test('strategy profile serializes Telegram endpoint relay policy', () {
    const profile = StrategyProfile(
      id: 'telegram-relay-smoke',
      name: 'Telegram relay smoke',
      description: 'Routes Telegram DC endpoints through a remote relay.',
      unmatchedTrafficPolicy: UnmatchedTrafficPolicy.direct,
      blobs: {},
      rules: [],
      endpointPolicies: [
        StrategyEndpointPolicy(
          id: 'telegram-remote-relay',
          endpointClasses: ['telegram', 'telegram_host', 'mtproto_port'],
          route: StrategyEndpointRoute(
            kind: StrategyEndpointRouteKind.remoteRelay,
            protocol: StrategyRelayProtocol.socks5,
            host: 'relay.example.net',
            port: 1080,
            auth: StrategyRelayAuth(username: 'user', password: 'pass'),
            connectTimeoutMs: 3000,
            relayConnectTimeoutMs: 5000,
            failureMode: StrategyEndpointFailureMode.failClosed,
          ),
        ),
      ],
    );

    final roundtripped = StrategyProfile.fromMap(profile.toMap());
    final policy = roundtripped.endpointPolicies.single;
    final route = policy.route;

    expect(policy.endpointClasses, [
      'telegram',
      'telegram_host',
      'mtproto_port',
    ]);
    expect(policy.transport, StrategyEndpointTransport.tcp);
    expect(route.kind, StrategyEndpointRouteKind.remoteRelay);
    expect(route.protocol, StrategyRelayProtocol.socks5);
    expect(route.host, 'relay.example.net');
    expect(route.port, 1080);
    expect(route.auth?.username, 'user');
    expect(route.auth?.password, 'pass');
    expect(route.failureMode, StrategyEndpointFailureMode.failClosed);
  });

  test('runtime snapshot parses Android VPN bridge payload', () {
    final snapshot = ProxyRuntimeSnapshot.fromMap({
      'platform': 'android',
      'state': 'running',
      'message': 'Android VPN service base is active.',
      'backendConnected': true,
      'vpnPermissionGranted': true,
      'serviceActive': true,
      'strategyEngineReady': true,
      'trafficForwarderReady': false,
      'tunnelActive': false,
      'packetCodecReady': true,
      'udpForwarderReady': true,
      'ipv6PacketCodecReady': true,
      'ipv6UdpForwarderReady': true,
      'tcpForwarderReady': false,
      'activeProfileName': 'Default lightweight',
      'telegramCompatibilityProxyReady': true,
      'telegramCompatibilitySetupRequired': true,
      'telegramCompatibilityProxyEndpoint': '127.0.0.1:1443',
      'telegramCompatibilityProxyMessage':
          'Telegram compatibility proxy слушает 127.0.0.1:1443.',
    });

    expect(snapshot.platform, ProxyPlatform.android);
    expect(snapshot.state, ProxyRuntimeState.running);
    expect(snapshot.message, 'Android VPN service base is active.');
    expect(snapshot.backendConnected, isTrue);
    expect(snapshot.vpnPermissionGranted, isTrue);
    expect(snapshot.serviceActive, isTrue);
    expect(snapshot.strategyEngineReady, isTrue);
    expect(snapshot.trafficForwarderReady, isFalse);
    expect(snapshot.tunnelActive, isFalse);
    expect(snapshot.packetCodecReady, isTrue);
    expect(snapshot.udpForwarderReady, isTrue);
    expect(snapshot.ipv6PacketCodecReady, isTrue);
    expect(snapshot.ipv6UdpForwarderReady, isTrue);
    expect(snapshot.tcpForwarderReady, isFalse);
    expect(snapshot.activeProfileName, 'Default lightweight');
    expect(snapshot.telegramCompatibilityProxyReady, isTrue);
    expect(snapshot.telegramCompatibilitySetupRequired, isTrue);
    expect(snapshot.telegramCompatibilityProxyEndpoint, '127.0.0.1:1443');
    expect(
      snapshot.telegramCompatibilityProxyMessage,
      'Telegram compatibility proxy слушает 127.0.0.1:1443.',
    );
  });

  test('prepare result parses native response', () {
    final result = ProxyPrepareResult.fromMap({
      'granted': true,
      'message': 'VPN-разрешение получено.',
    });

    expect(result.granted, isTrue);
    expect(result.message, 'VPN-разрешение получено.');
  });
}
