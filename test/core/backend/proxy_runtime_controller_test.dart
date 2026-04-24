import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qnzapret/core/backend/backend.dart';

void main() {
  test('controller initializes from runtime snapshot', () async {
    final runtime = _FakeRuntime(
      snapshot: const ProxyRuntimeSnapshot(
        platform: ProxyPlatform.android,
        state: ProxyRuntimeState.idle,
        message: 'Ready',
        backendConnected: true,
        vpnPermissionGranted: true,
        serviceActive: false,
        strategyEngineReady: false,
        trafficForwarderReady: false,
        tunnelActive: false,
        packetCodecReady: false,
        udpForwarderReady: false,
        ipv6PacketCodecReady: false,
        ipv6UdpForwarderReady: false,
        tcpForwarderReady: false,
      ),
    );
    final controller = ProxyRuntimeController(runtime: runtime);

    addTearDown(controller.dispose);

    expect(controller.snapshot.message, contains('not been loaded'));

    final ok = await controller.initialize();

    expect(ok, isTrue);
    expect(controller.snapshot.message, 'Ready');
    expect(controller.canStart, isTrue);
    expect(controller.needsPrepare, isFalse);
  });

  test(
    'controller starts with default launch config and refreshes snapshot',
    () async {
      final runtime = _FakeRuntime(
        snapshot: const ProxyRuntimeSnapshot(
          platform: ProxyPlatform.android,
          state: ProxyRuntimeState.idle,
          message: 'Ready',
          backendConnected: true,
          vpnPermissionGranted: true,
          serviceActive: false,
          strategyEngineReady: false,
          trafficForwarderReady: false,
          tunnelActive: false,
          packetCodecReady: false,
          udpForwarderReady: false,
          ipv6PacketCodecReady: false,
          ipv6UdpForwarderReady: false,
          tcpForwarderReady: false,
        ),
      );
      final controller = ProxyRuntimeController(runtime: runtime);

      addTearDown(controller.dispose);

      final ok = await controller.start();

      expect(ok, isTrue);
      expect(runtime.startedConfig, ProxyLaunchConfig.defaultAndroidStrategy);
      expect(controller.snapshot.state, ProxyRuntimeState.running);
      expect(controller.snapshot.serviceActive, isTrue);
    },
  );

  test('controller captures platform failures for UI', () async {
    final runtime = _FakeRuntime(
      snapshot: ProxyRuntimeSnapshot.initial(ProxyPlatform.android),
      startError: PlatformException(
        code: 'vpn_permission_required',
        message: 'VPN permission must be granted.',
      ),
    );
    final controller = ProxyRuntimeController(runtime: runtime);

    addTearDown(controller.dispose);

    final ok = await controller.start();

    expect(ok, isFalse);
    expect(controller.lastFailure?.code, 'vpn_permission_required');
    expect(controller.lastFailure?.message, 'VPN permission must be granted.');
  });
}

final class _FakeRuntime implements ProxyRuntime {
  _FakeRuntime({required ProxyRuntimeSnapshot snapshot, this.startError})
    : _snapshot = snapshot;

  ProxyRuntimeSnapshot _snapshot;
  final Object? startError;
  ProxyLaunchConfig? startedConfig;

  @override
  ProxyPlatform get platform => _snapshot.platform;

  @override
  Future<ProxyRuntimeSnapshot> getSnapshot() async => _snapshot;

  @override
  Future<ProxyPrepareResult> prepare() async {
    _snapshot = _snapshot.copyWith(vpnPermissionGranted: true);
    return const ProxyPrepareResult(granted: true, message: 'Prepared');
  }

  @override
  Future<void> start(ProxyLaunchConfig config) async {
    final error = startError;
    if (error != null) {
      throw error;
    }

    startedConfig = config;
    _snapshot = _snapshot.copyWith(
      state: ProxyRuntimeState.running,
      message: 'Running',
      serviceActive: true,
    );
  }

  @override
  Future<void> stop() async {
    _snapshot = _snapshot.copyWith(
      state: ProxyRuntimeState.idle,
      message: 'Stopped',
      serviceActive: false,
    );
  }
}
