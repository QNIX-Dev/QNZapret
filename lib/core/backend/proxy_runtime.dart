enum ProxyPlatform { android, linux, windows }

enum ProxyRuntimeState { idle, starting, running, stopping, failed }

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
  });

  final String localHost;
  final int localPort;
  final int poolSize;
  final bool cloudflareEnabled;
  final String secret;

  Map<String, Object?> toMap() {
    return {
      'localHost': localHost,
      'localPort': localPort,
      'poolSize': poolSize,
      'cloudflareEnabled': cloudflareEnabled,
      'secret': secret,
    };
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
  });

  final ProxyPlatform platform;
  final ProxyRuntimeState state;
  final String message;
  final bool backendConnected;
  final bool vpnPermissionGranted;
  final bool serviceActive;

  factory ProxyRuntimeSnapshot.fromMap(Map<Object?, Object?> map) {
    return ProxyRuntimeSnapshot(
      platform: _parsePlatform(map['platform'] as String?),
      state: _parseRuntimeState(map['state'] as String?),
      message: map['message'] as String? ?? '',
      backendConnected: map['backendConnected'] as bool? ?? false,
      vpnPermissionGranted: map['vpnPermissionGranted'] as bool? ?? false,
      serviceActive: map['serviceActive'] as bool? ?? false,
    );
  }
}

abstract interface class ProxyRuntime {
  Future<ProxyPrepareResult> prepare();

  Future<ProxyRuntimeSnapshot> getSnapshot();

  Future<void> start(ProxyLaunchConfig config);

  Future<void> stop();
}

final class StubProxyRuntime implements ProxyRuntime {
  const StubProxyRuntime(this.platform);

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
      message: 'Bridge for native Go backend is not connected yet.',
      backendConnected: false,
      vpnPermissionGranted: false,
      serviceActive: false,
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
