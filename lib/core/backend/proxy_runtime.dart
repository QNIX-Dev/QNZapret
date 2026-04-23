enum ProxyPlatform { android, linux, windows }

enum ProxyRuntimeState { idle, starting, running, stopping, failed }

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
}

class ProxyRuntimeSnapshot {
  const ProxyRuntimeSnapshot({
    required this.platform,
    required this.state,
    required this.message,
    required this.backendConnected,
  });

  final ProxyPlatform platform;
  final ProxyRuntimeState state;
  final String message;
  final bool backendConnected;
}

abstract interface class ProxyRuntime {
  Future<ProxyRuntimeSnapshot> getSnapshot();

  Future<void> start(ProxyLaunchConfig config);

  Future<void> stop();
}

final class StubProxyRuntime implements ProxyRuntime {
  const StubProxyRuntime(this.platform);

  final ProxyPlatform platform;

  @override
  Future<ProxyRuntimeSnapshot> getSnapshot() async {
    return ProxyRuntimeSnapshot(
      platform: platform,
      state: ProxyRuntimeState.idle,
      message: 'Bridge for native Go backend is not connected yet.',
      backendConnected: false,
    );
  }

  @override
  Future<void> start(ProxyLaunchConfig config) async {}

  @override
  Future<void> stop() async {}
}
