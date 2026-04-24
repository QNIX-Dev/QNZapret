import 'package:flutter/foundation.dart';

enum ProxyPlatform { android, linux, windows }

extension ProxyPlatformX on ProxyPlatform {
  String get wireName => switch (this) {
    ProxyPlatform.android => 'android',
    ProxyPlatform.linux => 'linux',
    ProxyPlatform.windows => 'windows',
  };

  static ProxyPlatform fromWireName(Object? value) {
    return switch (value) {
      'linux' => ProxyPlatform.linux,
      'windows' => ProxyPlatform.windows,
      _ => ProxyPlatform.android,
    };
  }
}

enum ProxyRuntimeState { idle, starting, running, stopping, failed }

extension ProxyRuntimeStateX on ProxyRuntimeState {
  String get wireName => switch (this) {
    ProxyRuntimeState.idle => 'idle',
    ProxyRuntimeState.starting => 'starting',
    ProxyRuntimeState.running => 'running',
    ProxyRuntimeState.stopping => 'stopping',
    ProxyRuntimeState.failed => 'failed',
  };

  static ProxyRuntimeState fromWireName(Object? value) {
    return switch (value) {
      'starting' => ProxyRuntimeState.starting,
      'running' => ProxyRuntimeState.running,
      'stopping' => ProxyRuntimeState.stopping,
      'failed' => ProxyRuntimeState.failed,
      _ => ProxyRuntimeState.idle,
    };
  }
}

@immutable
class ProxyPrepareResult {
  const ProxyPrepareResult({required this.granted, required this.message});

  factory ProxyPrepareResult.fromMap(Map<Object?, Object?> map) {
    return ProxyPrepareResult(
      granted: map['granted'] as bool? ?? false,
      message: map['message'] as String? ?? 'Runtime prepare finished.',
    );
  }

  final bool granted;
  final String message;
}

@immutable
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

@immutable
class ProxyRuntimeSnapshot {
  const ProxyRuntimeSnapshot({
    required this.platform,
    required this.state,
    required this.message,
    required this.backendConnected,
    required this.vpnPermissionGranted,
    required this.serviceActive,
  });

  factory ProxyRuntimeSnapshot.fromMap(Map<Object?, Object?> map) {
    return ProxyRuntimeSnapshot(
      platform: ProxyPlatformX.fromWireName(map['platform']),
      state: ProxyRuntimeStateX.fromWireName(map['state']),
      message: map['message'] as String? ?? 'Runtime state is unknown.',
      backendConnected: map['backendConnected'] as bool? ?? false,
      vpnPermissionGranted: map['vpnPermissionGranted'] as bool? ?? false,
      serviceActive: map['serviceActive'] as bool? ?? false,
    );
  }

  factory ProxyRuntimeSnapshot.disconnected(ProxyPlatform platform) {
    return ProxyRuntimeSnapshot(
      platform: platform,
      state: ProxyRuntimeState.idle,
      message: 'Сервис пока работает в режиме предварительного интерфейса.',
      backendConnected: false,
      vpnPermissionGranted: false,
      serviceActive: false,
    );
  }

  final ProxyPlatform platform;
  final ProxyRuntimeState state;
  final String message;
  final bool backendConnected;
  final bool vpnPermissionGranted;
  final bool serviceActive;
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
      granted: true,
      message: 'Подготовка завершена.',
    );
  }

  @override
  Future<ProxyRuntimeSnapshot> getSnapshot() async {
    return ProxyRuntimeSnapshot.disconnected(platform);
  }

  @override
  Future<void> start(ProxyLaunchConfig config) async {}

  @override
  Future<void> stop() async {}
}
