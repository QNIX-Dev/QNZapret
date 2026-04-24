enum ProxyPlatform { android, linux, windows }

enum ProxyRuntimeState { idle, starting, running, stopping, failed }

enum StrategyProtocol { http, tls, quic }

enum StrategyActionKind { split, fake, udpFake }

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

class ProxyLaunchConfig {
  const ProxyLaunchConfig({
    required this.localHost,
    required this.localPort,
    required this.poolSize,
    required this.cloudflareEnabled,
    required this.secret,
    this.strategyProfile = StrategyProfile.defaultLightweight,
    this.establishTunnel = false,
    this.tunnelMtu = 8500,
  });

  static const defaultAndroidStrategy = ProxyLaunchConfig(
    localHost: '127.0.0.1',
    localPort: 1080,
    poolSize: 0,
    cloudflareEnabled: false,
    secret: '',
  );

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
        id: 'quic-hostlist-fake',
        name: 'QUIC Initial fake',
        tcpPorts: [],
        udpPorts: [443],
        protocols: [StrategyProtocol.quic],
        hostlists: [
          'qnzapret/lists/list-google.txt',
          'qnzapret/lists/list-user.txt',
        ],
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
  final List<StrategyRule> rules;

  Map<String, Object?> toMap() {
    return {
      'id': id,
      'name': name,
      'description': description,
      'unmatchedTrafficPolicy': unmatchedTrafficPolicy.name,
      'blobs': blobs,
      'rules': rules.map((rule) => rule.toMap()).toList(),
    };
  }

  factory StrategyProfile.fromMap(Map<Object?, Object?> map) {
    final blobs = _parseStringMap(map['blobs']);
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
      rules: rules.isEmpty ? defaultLightweight.rules : rules,
    );
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
    required this.tcpForwarderReady,
    this.activeProfileName,
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
  final bool packetCodecReady;
  final bool udpForwarderReady;
  final bool tcpForwarderReady;
  final String? activeProfileName;

  factory ProxyRuntimeSnapshot.initial(ProxyPlatform platform) {
    return ProxyRuntimeSnapshot(
      platform: platform,
      state: ProxyRuntimeState.idle,
      message: 'Runtime snapshot has not been loaded yet.',
      backendConnected: false,
      vpnPermissionGranted: false,
      serviceActive: false,
      strategyEngineReady: false,
      trafficForwarderReady: false,
      tunnelActive: false,
      packetCodecReady: false,
      udpForwarderReady: false,
      tcpForwarderReady: false,
    );
  }

  factory ProxyRuntimeSnapshot.fromMap(Map<Object?, Object?> map) {
    final activeProfileName = map['activeProfileName'] as String?;
    return ProxyRuntimeSnapshot(
      platform: _parsePlatform(map['platform'] as String?),
      state: _parseRuntimeState(map['state'] as String?),
      message: map['message'] as String? ?? '',
      backendConnected: map['backendConnected'] as bool? ?? false,
      vpnPermissionGranted: map['vpnPermissionGranted'] as bool? ?? false,
      serviceActive: map['serviceActive'] as bool? ?? false,
      strategyEngineReady: map['strategyEngineReady'] as bool? ?? false,
      trafficForwarderReady: map['trafficForwarderReady'] as bool? ?? false,
      tunnelActive: map['tunnelActive'] as bool? ?? false,
      packetCodecReady: map['packetCodecReady'] as bool? ?? false,
      udpForwarderReady: map['udpForwarderReady'] as bool? ?? false,
      tcpForwarderReady: map['tcpForwarderReady'] as bool? ?? false,
      activeProfileName: (activeProfileName?.isEmpty ?? true)
          ? null
          : activeProfileName,
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
    bool? packetCodecReady,
    bool? udpForwarderReady,
    bool? tcpForwarderReady,
    String? activeProfileName,
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
      packetCodecReady: packetCodecReady ?? this.packetCodecReady,
      udpForwarderReady: udpForwarderReady ?? this.udpForwarderReady,
      tcpForwarderReady: tcpForwarderReady ?? this.tcpForwarderReady,
      activeProfileName: activeProfileName ?? this.activeProfileName,
    );
  }
}

abstract interface class ProxyRuntime {
  ProxyPlatform get platform;

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
  Future<ProxyPrepareResult> prepare() async {
    return const ProxyPrepareResult(
      granted: false,
      message: 'Native VPN preparation is not connected yet.',
    );
  }

  @override
  Future<ProxyRuntimeSnapshot> getSnapshot() async {
    return ProxyRuntimeSnapshot(
      platform: platform,
      state: ProxyRuntimeState.idle,
      message: 'Bridge for native strategy runtime is not connected yet.',
      backendConnected: false,
      vpnPermissionGranted: false,
      serviceActive: false,
      strategyEngineReady: false,
      trafficForwarderReady: false,
      tunnelActive: false,
      packetCodecReady: false,
      udpForwarderReady: false,
      tcpForwarderReady: false,
    );
  }

  @override
  Future<void> start(ProxyLaunchConfig config) async {}

  @override
  Future<void> stop() async {}
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
