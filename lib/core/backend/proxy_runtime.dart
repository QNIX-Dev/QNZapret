import 'dart:async';

enum ProxyPlatform { android, linux, windows }

enum ProxyRuntimeState { idle, starting, running, stopping, failed }

enum TrafficInterceptionMode { none, androidVpnTun, linuxNfqueue }

enum TelegramSidecarState {
  unavailable,
  idle,
  starting,
  running,
  stopping,
  failed,
}

enum ProxyRuntimeEventKind { snapshot, log }

enum ProxyRuntimeLogLevel { debug, info, warning, error }

enum StrategyProtocol { http, tls, quic }

enum StrategyActionKind { split, fake, udpFake }

enum StrategyEndpointTransport { tcp }

enum StrategyEndpointRouteKind { remoteRelay }

enum StrategyRelayProtocol { socks5, httpsConnect }

enum StrategyEndpointFailureMode { failClosed }

enum UnmatchedTrafficPolicy { direct }

class ProxyPrepareResult {
  const ProxyPrepareResult({required this.granted, required this.message});

  final bool granted;
  final String message;

  factory ProxyPrepareResult.fromMap(Map<Object?, Object?> map) {
    return ProxyPrepareResult(
      granted: map['granted'] as bool? ?? false,
      message: map['message'] as String? ?? '',
    );
  }
}

class ProxyRuntimeLogEvent {
  const ProxyRuntimeLogEvent({
    required this.timestamp,
    required this.level,
    required this.source,
    required this.code,
    required this.message,
  });

  final DateTime timestamp;
  final ProxyRuntimeLogLevel level;
  final String source;
  final String code;
  final String message;

  factory ProxyRuntimeLogEvent.fromMap(Map<Object?, Object?> map) {
    final timestampMillis = (map['timestampMillis'] as num?)?.toInt();
    final timestampText = map['timestamp'] as String?;
    return ProxyRuntimeLogEvent(
      timestamp: timestampMillis != null
          ? DateTime.fromMillisecondsSinceEpoch(
              timestampMillis,
              isUtc: true,
            ).toLocal()
          : DateTime.tryParse(timestampText ?? '')?.toLocal() ?? DateTime.now(),
      level: _parseRuntimeLogLevel(map['level'] as String?),
      source: map['source'] as String? ?? 'runtime',
      code: map['code'] as String? ?? 'runtime_event',
      message: map['message'] as String? ?? '',
    );
  }
}

class ProxyRuntimeEvent {
  const ProxyRuntimeEvent.snapshot(this.snapshot) : log = null;

  const ProxyRuntimeEvent.log(this.log) : snapshot = null;

  final ProxyRuntimeSnapshot? snapshot;
  final ProxyRuntimeLogEvent? log;

  ProxyRuntimeEventKind get kind => snapshot != null
      ? ProxyRuntimeEventKind.snapshot
      : ProxyRuntimeEventKind.log;

  factory ProxyRuntimeEvent.fromMap(Map<Object?, Object?> map) {
    if (map['type'] == 'snapshot') {
      return ProxyRuntimeEvent.snapshot(
        ProxyRuntimeSnapshot.fromMap(_parseObjectMap(map['snapshot'])),
      );
    }
    return ProxyRuntimeEvent.log(
      ProxyRuntimeLogEvent.fromMap(_parseObjectMap(map['log'])),
    );
  }
}

class ProxyLaunchConfig {
  const ProxyLaunchConfig({
    required this.localHost,
    required this.localPort,
    required this.poolSize,
    required this.cloudflareEnabled,
    required this.secret,
    this.strategyProfile = StrategyProfile.defaultLightweight,
    this.establishTunnel = true,
    this.tunnelMtu = 8500,
  });

  static const defaultAndroidStrategy = ProxyLaunchConfig(
    localHost: '127.0.0.1',
    localPort: 1080,
    poolSize: 0,
    cloudflareEnabled: false,
    secret: '',
  );

  static const defaultLinuxStrategy = ProxyLaunchConfig(
    localHost: '127.0.0.1',
    localPort: 1080,
    poolSize: 0,
    cloudflareEnabled: true,
    secret: '',
  );

  static ProxyLaunchConfig defaultForPlatform(ProxyPlatform platform) {
    return platform == ProxyPlatform.linux
        ? defaultLinuxStrategy
        : defaultAndroidStrategy;
  }

  final String localHost;
  final int localPort;
  final int poolSize;
  final bool cloudflareEnabled;
  final String secret;
  final StrategyProfile strategyProfile;
  final bool establishTunnel;
  final int tunnelMtu;

  Map<String, Object?> toMap() {
    return {
      'localHost': localHost,
      'localPort': localPort,
      'poolSize': poolSize,
      'cloudflareEnabled': cloudflareEnabled,
      'secret': secret,
      'strategyProfile': strategyProfile.toMap(),
      'establishTunnel': establishTunnel,
      'tunnelMtu': tunnelMtu,
    };
  }

  ProxyLaunchConfig copyWith({
    String? localHost,
    int? localPort,
    int? poolSize,
    bool? cloudflareEnabled,
    String? secret,
    StrategyProfile? strategyProfile,
    bool? establishTunnel,
    int? tunnelMtu,
  }) {
    return ProxyLaunchConfig(
      localHost: localHost ?? this.localHost,
      localPort: localPort ?? this.localPort,
      poolSize: poolSize ?? this.poolSize,
      cloudflareEnabled: cloudflareEnabled ?? this.cloudflareEnabled,
      secret: secret ?? this.secret,
      strategyProfile: strategyProfile ?? this.strategyProfile,
      establishTunnel: establishTunnel ?? this.establishTunnel,
      tunnelMtu: tunnelMtu ?? this.tunnelMtu,
    );
  }
}

class StrategyProfile {
  const StrategyProfile({
    required this.id,
    required this.name,
    required this.description,
    required this.unmatchedTrafficPolicy,
    required this.blobs,
    required this.rules,
    this.endpointPolicies = const [],
  });

  static const defaultLightweight = StrategyProfile(
    id: 'default-lightweight',
    name: 'Default lightweight',
    description:
        'No-root VPN/proxy subset inspired by the base zapret profile.',
    unmatchedTrafficPolicy: UnmatchedTrafficPolicy.direct,
    blobs: {
      'tls_google': 'qnzapret/payloads/tls_clienthello_www_google_com.bin',
      'quic_google': 'qnzapret/payloads/quic_initial_www_google_com.bin',
    },
    endpointPolicies: [],
    rules: [
      StrategyRule(
        id: 'http-hostlist-fake-split',
        name: 'HTTP hostlist fake + split',
        tcpPorts: [80],
        udpPorts: [],
        protocols: [StrategyProtocol.http],
        hostlists: [
          'qnzapret/lists/list-general.txt',
          'qnzapret/lists/list-user.txt',
          'qnzapret/lists/list-google.txt',
        ],
        actions: [
          StrategyAction(kind: StrategyActionKind.fake, repeats: 1),
          StrategyAction(
            kind: StrategyActionKind.split,
            position: 1,
            repeats: 1,
          ),
        ],
      ),
      StrategyRule(
        id: 'tls-hostlist-split',
        name: 'TLS ClientHello split',
        tcpPorts: [443],
        udpPorts: [],
        protocols: [StrategyProtocol.tls],
        hostlists: [
          'qnzapret/lists/list-general.txt',
          'qnzapret/lists/list-user.txt',
          'qnzapret/lists/list-google.txt',
        ],
        actions: [
          StrategyAction(
            kind: StrategyActionKind.fake,
            blobKey: 'tls_google',
            repeats: 1,
          ),
          StrategyAction(
            kind: StrategyActionKind.split,
            position: 1,
            repeats: 1,
          ),
        ],
      ),
      StrategyRule(
        id: 'quic-initial-fake',
        name: 'QUIC Initial fake',
        tcpPorts: [],
        udpPorts: [443],
        protocols: [StrategyProtocol.quic],
        hostlists: [],
        actions: [
          StrategyAction(
            kind: StrategyActionKind.udpFake,
            blobKey: 'quic_google',
            repeats: 1,
          ),
        ],
      ),
    ],
  );

  final String id;
  final String name;
  final String description;
  final UnmatchedTrafficPolicy unmatchedTrafficPolicy;
  final Map<String, String> blobs;
  final List<StrategyEndpointPolicy> endpointPolicies;
  final List<StrategyRule> rules;

  Map<String, Object?> toMap() {
    return {
      'id': id,
      'name': name,
      'description': description,
      'unmatchedTrafficPolicy': unmatchedTrafficPolicy.name,
      'blobs': blobs,
      if (endpointPolicies.isNotEmpty)
        'endpointPolicies': endpointPolicies
            .map((policy) => policy.toMap())
            .toList(growable: false),
      'rules': rules.map((rule) => rule.toMap()).toList(),
    };
  }

  factory StrategyProfile.fromMap(Map<Object?, Object?> map) {
    final blobs = _parseStringMap(map['blobs']);
    final endpointPolicies = _parseMapList(
      map['endpointPolicies'],
    ).map(StrategyEndpointPolicy.fromMap).toList(growable: false);
    final rules = _parseMapList(
      map['rules'],
    ).map(StrategyRule.fromMap).toList(growable: false);

    return StrategyProfile(
      id: map['id'] as String? ?? defaultLightweight.id,
      name: map['name'] as String? ?? defaultLightweight.name,
      description:
          map['description'] as String? ?? defaultLightweight.description,
      unmatchedTrafficPolicy: _parseUnmatchedTrafficPolicy(
        map['unmatchedTrafficPolicy'] as String?,
      ),
      blobs: blobs.isEmpty ? defaultLightweight.blobs : blobs,
      endpointPolicies: endpointPolicies,
      rules: rules.isEmpty ? defaultLightweight.rules : rules,
    );
  }
}

class StrategyEndpointPolicy {
  const StrategyEndpointPolicy({
    required this.id,
    required this.endpointClasses,
    required this.route,
    this.transport = StrategyEndpointTransport.tcp,
  });

  final String id;
  final List<String> endpointClasses;
  final StrategyEndpointTransport transport;
  final StrategyEndpointRoute route;

  Map<String, Object?> toMap() {
    return {
      'id': id,
      'endpointClasses': endpointClasses,
      'transport': transport.name,
      'route': route.toMap(),
    };
  }

  factory StrategyEndpointPolicy.fromMap(Map<Object?, Object?> map) {
    return StrategyEndpointPolicy(
      id: map['id'] as String? ?? '',
      endpointClasses: _parseStringList(map['endpointClasses']),
      transport: _parseStrategyEndpointTransport(map['transport'] as String?),
      route: StrategyEndpointRoute.fromMap(_parseObjectMap(map['route'])),
    );
  }
}

class StrategyEndpointRoute {
  const StrategyEndpointRoute({
    required this.kind,
    required this.protocol,
    required this.host,
    required this.port,
    this.auth,
    this.connectTimeoutMs = 3000,
    this.relayConnectTimeoutMs = 5000,
    this.failureMode = StrategyEndpointFailureMode.failClosed,
  });

  final StrategyEndpointRouteKind kind;
  final StrategyRelayProtocol protocol;
  final String host;
  final int port;
  final StrategyRelayAuth? auth;
  final int connectTimeoutMs;
  final int relayConnectTimeoutMs;
  final StrategyEndpointFailureMode failureMode;

  Map<String, Object?> toMap() {
    return {
      'kind': kind.name,
      'protocol': protocol.name,
      'host': host,
      'port': port,
      if (auth != null) 'auth': auth!.toMap(),
      'connectTimeoutMs': connectTimeoutMs,
      'relayConnectTimeoutMs': relayConnectTimeoutMs,
      'failureMode': failureMode.name,
    };
  }

  factory StrategyEndpointRoute.fromMap(Map<Object?, Object?> map) {
    final auth = StrategyRelayAuth.fromMapOrNull(_parseObjectMap(map['auth']));
    return StrategyEndpointRoute(
      kind: _parseStrategyEndpointRouteKind(map['kind'] as String?),
      protocol: _parseStrategyRelayProtocol(map['protocol'] as String?),
      host: map['host'] as String? ?? '',
      port: (map['port'] as num?)?.toInt() ?? 0,
      auth: auth,
      connectTimeoutMs: (map['connectTimeoutMs'] as num?)?.toInt() ?? 3000,
      relayConnectTimeoutMs:
          (map['relayConnectTimeoutMs'] as num?)?.toInt() ?? 5000,
      failureMode: _parseStrategyEndpointFailureMode(
        map['failureMode'] as String?,
      ),
    );
  }
}

class StrategyRelayAuth {
  const StrategyRelayAuth({this.username, this.password});

  final String? username;
  final String? password;

  Map<String, Object?> toMap() {
    return {
      if (username != null) 'username': username,
      if (password != null) 'password': password,
    };
  }

  static StrategyRelayAuth? fromMapOrNull(Map<Object?, Object?> map) {
    if (map.isEmpty) {
      return null;
    }
    final username = map['username'] as String?;
    final password = map['password'] as String?;
    if ((username == null || username.isEmpty) &&
        (password == null || password.isEmpty)) {
      return null;
    }
    return StrategyRelayAuth(username: username, password: password);
  }
}

class StrategyRule {
  const StrategyRule({
    required this.id,
    required this.name,
    required this.tcpPorts,
    required this.udpPorts,
    required this.protocols,
    required this.hostlists,
    required this.actions,
  });

  final String id;
  final String name;
  final List<int> tcpPorts;
  final List<int> udpPorts;
  final List<StrategyProtocol> protocols;
  final List<String> hostlists;
  final List<StrategyAction> actions;

  Map<String, Object?> toMap() {
    return {
      'id': id,
      'name': name,
      'tcpPorts': tcpPorts,
      'udpPorts': udpPorts,
      'protocols': protocols.map((protocol) => protocol.name).toList(),
      'hostlists': hostlists,
      'actions': actions.map((action) => action.toMap()).toList(),
    };
  }

  factory StrategyRule.fromMap(Map<Object?, Object?> map) {
    return StrategyRule(
      id: map['id'] as String? ?? '',
      name: map['name'] as String? ?? '',
      tcpPorts: _parseIntList(map['tcpPorts']),
      udpPorts: _parseIntList(map['udpPorts']),
      protocols: _parseStringList(
        map['protocols'],
      ).map(_parseStrategyProtocol).toList(growable: false),
      hostlists: _parseStringList(map['hostlists']),
      actions: _parseMapList(
        map['actions'],
      ).map(StrategyAction.fromMap).toList(growable: false),
    );
  }
}

class StrategyAction {
  const StrategyAction({
    required this.kind,
    this.position,
    this.repeats = 1,
    this.blobKey,
  });

  final StrategyActionKind kind;
  final int? position;
  final int repeats;
  final String? blobKey;

  Map<String, Object?> toMap() {
    return {
      'kind': kind.name,
      if (position != null) 'position': position,
      'repeats': repeats,
      if (blobKey != null) 'blobKey': blobKey,
    };
  }

  factory StrategyAction.fromMap(Map<Object?, Object?> map) {
    return StrategyAction(
      kind: _parseStrategyActionKind(map['kind'] as String?),
      position: (map['position'] as num?)?.toInt(),
      repeats: (map['repeats'] as num?)?.toInt() ?? 1,
      blobKey: map['blobKey'] as String?,
    );
  }
}

class ProxyRuntimeSnapshot {
  const ProxyRuntimeSnapshot({
    required this.platform,
    required this.state,
    required this.message,
    required this.backendConnected,
    required this.vpnPermissionGranted,
    required this.serviceActive,
    required this.strategyEngineReady,
    required this.trafficForwarderReady,
    required this.tunnelActive,
    required this.packetCodecReady,
    required this.udpForwarderReady,
    required this.ipv6PacketCodecReady,
    required this.ipv6UdpForwarderReady,
    required this.tcpForwarderReady,
    this.activeProfileName,
    this.trafficInterceptionMode = TrafficInterceptionMode.none,
    this.trafficInterceptionActive = false,
    this.queueRegistered = false,
    this.nftRulesInstalled = false,
    this.interceptionReady = false,
    this.backendVersion,
    this.runtimeOwnerUid,
    this.telegramSidecarState = TelegramSidecarState.unavailable,
    this.degraded = false,
    this.partialFailureCode,
    this.partialFailureMessage,
    this.telegramCompatibilityProxyReady = false,
    this.telegramCompatibilitySetupRequired = false,
    this.telegramCompatibilityProxyEndpoint,
    this.telegramCompatibilityProxyMessage,
  });

  final ProxyPlatform platform;
  final ProxyRuntimeState state;
  final String message;
  final bool backendConnected;
  final bool vpnPermissionGranted;
  final bool serviceActive;
  final bool strategyEngineReady;
  final bool trafficForwarderReady;
  final bool tunnelActive;
  final TrafficInterceptionMode trafficInterceptionMode;
  final bool trafficInterceptionActive;
  final bool queueRegistered;
  final bool nftRulesInstalled;
  final bool interceptionReady;
  final bool packetCodecReady;
  final bool udpForwarderReady;
  final bool ipv6PacketCodecReady;
  final bool ipv6UdpForwarderReady;
  final bool tcpForwarderReady;
  final String? activeProfileName;
  final String? backendVersion;
  final int? runtimeOwnerUid;
  final TelegramSidecarState telegramSidecarState;
  final bool degraded;
  final String? partialFailureCode;
  final String? partialFailureMessage;
  final bool telegramCompatibilityProxyReady;
  final bool telegramCompatibilitySetupRequired;
  final String? telegramCompatibilityProxyEndpoint;
  final String? telegramCompatibilityProxyMessage;

  factory ProxyRuntimeSnapshot.initial(ProxyPlatform platform) {
    return ProxyRuntimeSnapshot(
      platform: platform,
      state: ProxyRuntimeState.idle,
      message: 'Состояние сервиса еще не загружено.',
      backendConnected: false,
      vpnPermissionGranted: false,
      serviceActive: false,
      strategyEngineReady: false,
      trafficForwarderReady: false,
      tunnelActive: false,
      trafficInterceptionMode: TrafficInterceptionMode.none,
      trafficInterceptionActive: false,
      queueRegistered: false,
      nftRulesInstalled: false,
      interceptionReady: false,
      packetCodecReady: false,
      udpForwarderReady: false,
      ipv6PacketCodecReady: false,
      ipv6UdpForwarderReady: false,
      tcpForwarderReady: false,
      telegramSidecarState: TelegramSidecarState.unavailable,
      degraded: false,
      telegramCompatibilityProxyReady: false,
      telegramCompatibilitySetupRequired: false,
    );
  }

  factory ProxyRuntimeSnapshot.fromMap(Map<Object?, Object?> map) {
    final platform = _parsePlatform(map['platform'] as String?);
    final state = _parseRuntimeState(map['state'] as String?);
    final activeProfileName = map['activeProfileName'] as String?;
    final telegramEndpoint =
        map['telegramCompatibilityProxyEndpoint'] as String?;
    final telegramMessage = map['telegramCompatibilityProxyMessage'] as String?;
    final legacyTunnelActive = map['tunnelActive'] as bool? ?? false;
    final strategyEngineReady = map['strategyEngineReady'] as bool? ?? false;
    final trafficForwarderReady =
        map['trafficForwarderReady'] as bool? ?? false;
    final interceptionMode = _parseTrafficInterceptionMode(
      map['trafficInterceptionMode'] as String?,
      legacyTunnelActive: legacyTunnelActive,
    );
    final interceptionActive =
        map['trafficInterceptionActive'] as bool? ?? legacyTunnelActive;
    final queueRegistered =
        map['queueRegistered'] as bool? ??
        (platform == ProxyPlatform.linux && strategyEngineReady);
    final nftRulesInstalled =
        map['nftRulesInstalled'] as bool? ??
        (platform == ProxyPlatform.linux && trafficForwarderReady);
    final interceptionReady =
        map['interceptionReady'] as bool? ??
        (interceptionActive &&
            trafficForwarderReady &&
            (platform != ProxyPlatform.linux ||
                (queueRegistered && nftRulesInstalled)));
    final telegramProxyReady =
        map['telegramCompatibilityProxyReady'] as bool? ?? false;
    final telegramSidecarState = _parseTelegramSidecarState(
      map['telegramSidecarState'] as String?,
      runtimeState: state,
      legacyProxyReady: telegramProxyReady,
    );
    final partialFailureCode = _nonEmptyString(map['partialFailureCode']);
    final partialFailureMessage = _nonEmptyString(map['partialFailureMessage']);
    final degraded =
        (map['degraded'] as bool? ?? false) ||
        partialFailureCode != null ||
        partialFailureMessage != null ||
        telegramSidecarState == TelegramSidecarState.failed;
    return ProxyRuntimeSnapshot(
      platform: platform,
      state: state,
      message: map['message'] as String? ?? '',
      backendConnected: map['backendConnected'] as bool? ?? false,
      vpnPermissionGranted: map['vpnPermissionGranted'] as bool? ?? false,
      serviceActive: map['serviceActive'] as bool? ?? false,
      strategyEngineReady: strategyEngineReady,
      trafficForwarderReady: trafficForwarderReady,
      tunnelActive: legacyTunnelActive,
      trafficInterceptionMode: interceptionMode,
      trafficInterceptionActive: interceptionActive,
      queueRegistered: queueRegistered,
      nftRulesInstalled: nftRulesInstalled,
      interceptionReady: interceptionReady,
      packetCodecReady: map['packetCodecReady'] as bool? ?? false,
      udpForwarderReady: map['udpForwarderReady'] as bool? ?? false,
      ipv6PacketCodecReady: map['ipv6PacketCodecReady'] as bool? ?? false,
      ipv6UdpForwarderReady: map['ipv6UdpForwarderReady'] as bool? ?? false,
      tcpForwarderReady: map['tcpForwarderReady'] as bool? ?? false,
      activeProfileName: (activeProfileName?.isEmpty ?? true)
          ? null
          : activeProfileName,
      backendVersion: switch (map['backendVersion'] as String?) {
        final value? when value.isNotEmpty => value,
        _ => null,
      },
      runtimeOwnerUid: (map['runtimeOwnerUid'] as num?)?.toInt(),
      telegramSidecarState: telegramSidecarState,
      degraded: degraded,
      partialFailureCode: partialFailureCode,
      partialFailureMessage: partialFailureMessage,
      telegramCompatibilityProxyReady: telegramProxyReady,
      telegramCompatibilitySetupRequired:
          map['telegramCompatibilitySetupRequired'] as bool? ?? false,
      telegramCompatibilityProxyEndpoint: (telegramEndpoint?.isEmpty ?? true)
          ? null
          : telegramEndpoint,
      telegramCompatibilityProxyMessage: (telegramMessage?.isEmpty ?? true)
          ? null
          : telegramMessage,
    );
  }

  ProxyRuntimeSnapshot copyWith({
    ProxyPlatform? platform,
    ProxyRuntimeState? state,
    String? message,
    bool? backendConnected,
    bool? vpnPermissionGranted,
    bool? serviceActive,
    bool? strategyEngineReady,
    bool? trafficForwarderReady,
    bool? tunnelActive,
    TrafficInterceptionMode? trafficInterceptionMode,
    bool? trafficInterceptionActive,
    bool? queueRegistered,
    bool? nftRulesInstalled,
    bool? interceptionReady,
    bool? packetCodecReady,
    bool? udpForwarderReady,
    bool? ipv6PacketCodecReady,
    bool? ipv6UdpForwarderReady,
    bool? tcpForwarderReady,
    String? activeProfileName,
    String? backendVersion,
    int? runtimeOwnerUid,
    TelegramSidecarState? telegramSidecarState,
    bool? degraded,
    String? partialFailureCode,
    String? partialFailureMessage,
    bool clearPartialFailure = false,
    bool? telegramCompatibilityProxyReady,
    bool? telegramCompatibilitySetupRequired,
    String? telegramCompatibilityProxyEndpoint,
    String? telegramCompatibilityProxyMessage,
  }) {
    return ProxyRuntimeSnapshot(
      platform: platform ?? this.platform,
      state: state ?? this.state,
      message: message ?? this.message,
      backendConnected: backendConnected ?? this.backendConnected,
      vpnPermissionGranted: vpnPermissionGranted ?? this.vpnPermissionGranted,
      serviceActive: serviceActive ?? this.serviceActive,
      strategyEngineReady: strategyEngineReady ?? this.strategyEngineReady,
      trafficForwarderReady:
          trafficForwarderReady ?? this.trafficForwarderReady,
      tunnelActive: tunnelActive ?? this.tunnelActive,
      trafficInterceptionMode:
          trafficInterceptionMode ?? this.trafficInterceptionMode,
      trafficInterceptionActive:
          trafficInterceptionActive ?? this.trafficInterceptionActive,
      queueRegistered: queueRegistered ?? this.queueRegistered,
      nftRulesInstalled: nftRulesInstalled ?? this.nftRulesInstalled,
      interceptionReady: interceptionReady ?? this.interceptionReady,
      packetCodecReady: packetCodecReady ?? this.packetCodecReady,
      udpForwarderReady: udpForwarderReady ?? this.udpForwarderReady,
      ipv6PacketCodecReady: ipv6PacketCodecReady ?? this.ipv6PacketCodecReady,
      ipv6UdpForwarderReady:
          ipv6UdpForwarderReady ?? this.ipv6UdpForwarderReady,
      tcpForwarderReady: tcpForwarderReady ?? this.tcpForwarderReady,
      activeProfileName: activeProfileName ?? this.activeProfileName,
      backendVersion: backendVersion ?? this.backendVersion,
      runtimeOwnerUid: runtimeOwnerUid ?? this.runtimeOwnerUid,
      telegramSidecarState: telegramSidecarState ?? this.telegramSidecarState,
      degraded: degraded ?? this.degraded,
      partialFailureCode: clearPartialFailure
          ? null
          : partialFailureCode ?? this.partialFailureCode,
      partialFailureMessage: clearPartialFailure
          ? null
          : partialFailureMessage ?? this.partialFailureMessage,
      telegramCompatibilityProxyReady:
          telegramCompatibilityProxyReady ??
          this.telegramCompatibilityProxyReady,
      telegramCompatibilitySetupRequired:
          telegramCompatibilitySetupRequired ??
          this.telegramCompatibilitySetupRequired,
      telegramCompatibilityProxyEndpoint:
          telegramCompatibilityProxyEndpoint ??
          this.telegramCompatibilityProxyEndpoint,
      telegramCompatibilityProxyMessage:
          telegramCompatibilityProxyMessage ??
          this.telegramCompatibilityProxyMessage,
    );
  }
}

abstract interface class ProxyRuntime {
  ProxyPlatform get platform;

  Stream<ProxyRuntimeEvent> get events;

  Future<ProxyPrepareResult> prepare();

  Future<ProxyRuntimeSnapshot> getSnapshot();

  Future<void> start(ProxyLaunchConfig config);

  Future<void> stop();
}

final class StubProxyRuntime implements ProxyRuntime {
  const StubProxyRuntime(this.platform);

  @override
  final ProxyPlatform platform;

  @override
  Stream<ProxyRuntimeEvent> get events => const Stream.empty();

  @override
  Future<ProxyPrepareResult> prepare() async {
    return const ProxyPrepareResult(
      granted: false,
      message: 'Подготовка VPN пока недоступна на этой платформе.',
    );
  }

  @override
  Future<ProxyRuntimeSnapshot> getSnapshot() async {
    return ProxyRuntimeSnapshot(
      platform: platform,
      state: ProxyRuntimeState.idle,
      message: 'Нативный сервис пока не подключен на этой платформе.',
      backendConnected: false,
      vpnPermissionGranted: false,
      serviceActive: false,
      strategyEngineReady: false,
      trafficForwarderReady: false,
      tunnelActive: false,
      trafficInterceptionMode: TrafficInterceptionMode.none,
      trafficInterceptionActive: false,
      queueRegistered: false,
      nftRulesInstalled: false,
      interceptionReady: false,
      packetCodecReady: false,
      udpForwarderReady: false,
      ipv6PacketCodecReady: false,
      ipv6UdpForwarderReady: false,
      tcpForwarderReady: false,
      telegramSidecarState: TelegramSidecarState.unavailable,
      degraded: false,
      telegramCompatibilityProxyReady: false,
      telegramCompatibilitySetupRequired: false,
    );
  }

  @override
  Future<void> start(ProxyLaunchConfig config) async {}

  @override
  Future<void> stop() async {}
}

TrafficInterceptionMode _parseTrafficInterceptionMode(
  String? rawValue, {
  required bool legacyTunnelActive,
}) {
  if (rawValue == null || rawValue.isEmpty) {
    return legacyTunnelActive
        ? TrafficInterceptionMode.androidVpnTun
        : TrafficInterceptionMode.none;
  }
  return TrafficInterceptionMode.values.firstWhere(
    (value) => value.name == rawValue,
    orElse: () => TrafficInterceptionMode.none,
  );
}

TelegramSidecarState _parseTelegramSidecarState(
  String? rawValue, {
  required ProxyRuntimeState runtimeState,
  required bool legacyProxyReady,
}) {
  if (rawValue != null && rawValue.isNotEmpty) {
    return TelegramSidecarState.values.firstWhere(
      (value) => value.name == rawValue,
      orElse: () => TelegramSidecarState.unavailable,
    );
  }
  if (legacyProxyReady) {
    return TelegramSidecarState.running;
  }
  return switch (runtimeState) {
    ProxyRuntimeState.starting => TelegramSidecarState.starting,
    ProxyRuntimeState.stopping => TelegramSidecarState.stopping,
    ProxyRuntimeState.idle => TelegramSidecarState.idle,
    ProxyRuntimeState.running ||
    ProxyRuntimeState.failed => TelegramSidecarState.unavailable,
  };
}

String? _nonEmptyString(Object? value) {
  return switch (value) {
    final String text when text.isNotEmpty => text,
    _ => null,
  };
}

ProxyRuntimeLogLevel _parseRuntimeLogLevel(String? rawValue) {
  return ProxyRuntimeLogLevel.values.firstWhere(
    (value) => value.name == rawValue,
    orElse: () => ProxyRuntimeLogLevel.info,
  );
}

ProxyPlatform _parsePlatform(String? rawValue) {
  return ProxyPlatform.values.firstWhere(
    (value) => value.name == rawValue,
    orElse: () => ProxyPlatform.android,
  );
}

ProxyRuntimeState _parseRuntimeState(String? rawValue) {
  return ProxyRuntimeState.values.firstWhere(
    (value) => value.name == rawValue,
    orElse: () => ProxyRuntimeState.failed,
  );
}

StrategyProtocol _parseStrategyProtocol(String rawValue) {
  return StrategyProtocol.values.firstWhere(
    (value) => value.name == rawValue,
    orElse: () => StrategyProtocol.http,
  );
}

StrategyActionKind _parseStrategyActionKind(String? rawValue) {
  return StrategyActionKind.values.firstWhere(
    (value) => value.name == rawValue,
    orElse: () => StrategyActionKind.split,
  );
}

StrategyEndpointTransport _parseStrategyEndpointTransport(String? rawValue) {
  return StrategyEndpointTransport.values.firstWhere(
    (value) => value.name == rawValue,
    orElse: () => StrategyEndpointTransport.tcp,
  );
}

StrategyEndpointRouteKind _parseStrategyEndpointRouteKind(String? rawValue) {
  return StrategyEndpointRouteKind.values.firstWhere(
    (value) => value.name == rawValue,
    orElse: () => StrategyEndpointRouteKind.remoteRelay,
  );
}

StrategyRelayProtocol _parseStrategyRelayProtocol(String? rawValue) {
  return StrategyRelayProtocol.values.firstWhere(
    (value) => value.name == rawValue,
    orElse: () => StrategyRelayProtocol.socks5,
  );
}

StrategyEndpointFailureMode _parseStrategyEndpointFailureMode(
  String? rawValue,
) {
  return StrategyEndpointFailureMode.values.firstWhere(
    (value) => value.name == rawValue,
    orElse: () => StrategyEndpointFailureMode.failClosed,
  );
}

UnmatchedTrafficPolicy _parseUnmatchedTrafficPolicy(String? rawValue) {
  return UnmatchedTrafficPolicy.values.firstWhere(
    (value) => value.name == rawValue,
    orElse: () => StrategyProfile.defaultLightweight.unmatchedTrafficPolicy,
  );
}

Map<String, String> _parseStringMap(Object? rawValue) {
  if (rawValue is! Map) {
    return const {};
  }

  return rawValue.map(
    (key, value) => MapEntry(key.toString(), value.toString()),
  );
}

Map<Object?, Object?> _parseObjectMap(Object? rawValue) {
  if (rawValue is Map<Object?, Object?>) {
    return rawValue;
  }
  if (rawValue is Map) {
    return rawValue.map((key, value) => MapEntry(key, value));
  }
  return const {};
}

List<String> _parseStringList(Object? rawValue) {
  if (rawValue is! Iterable) {
    return const [];
  }

  return rawValue.map((value) => value.toString()).toList(growable: false);
}

List<int> _parseIntList(Object? rawValue) {
  if (rawValue is! Iterable) {
    return const [];
  }

  return rawValue
      .whereType<num>()
      .map((value) => value.toInt())
      .toList(growable: false);
}

List<Map<Object?, Object?>> _parseMapList(Object? rawValue) {
  if (rawValue is! Iterable) {
    return const [];
  }

  return rawValue
      .whereType<Map>()
      .map((value) => value.cast<Object?, Object?>())
      .toList(growable: false);
}
