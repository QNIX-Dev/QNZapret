import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qnzapret/core/backend/backend.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('dev.qnzapret/proxy_runtime');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('factory returns LinuxProxyRuntime on Linux', () {
    final runtime = createDefaultProxyRuntime(
      targetPlatform: TargetPlatform.linux,
    );

    expect(runtime, isA<LinuxProxyRuntime>());
    expect(runtime.platform, ProxyPlatform.linux);
  });

  test('Linux adapter maps prepare and NFQUEUE snapshot', () async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      return switch (call.method) {
        'prepare' => <String, Object?>{
          'granted': true,
          'message': 'preflight ok',
        },
        'getSnapshot' => <String, Object?>{
          'platform': 'linux',
          'state': 'running',
          'message': 'active',
          'backendConnected': true,
          'vpnPermissionGranted': true,
          'serviceActive': true,
          'strategyEngineReady': true,
          'trafficForwarderReady': true,
          'tunnelActive': false,
          'trafficInterceptionMode': 'linuxNfqueue',
          'trafficInterceptionActive': true,
          'queueRegistered': true,
          'nftRulesInstalled': true,
          'interceptionReady': true,
          'backendVersion': '1.0.0',
          'runtimeOwnerUid': 1000,
          'telegramSidecarState': 'running',
          'degraded': false,
        },
        _ => null,
      };
    });
    const runtime = LinuxProxyRuntime(channel: channel);

    final prepare = await runtime.prepare();
    final snapshot = await runtime.getSnapshot();

    expect(prepare.granted, isTrue);
    expect(snapshot.platform, ProxyPlatform.linux);
    expect(
      snapshot.trafficInterceptionMode,
      TrafficInterceptionMode.linuxNfqueue,
    );
    expect(snapshot.trafficInterceptionActive, isTrue);
    expect(snapshot.queueRegistered, isTrue);
    expect(snapshot.nftRulesInstalled, isTrue);
    expect(snapshot.interceptionReady, isTrue);
    expect(snapshot.tunnelActive, isFalse);
    expect(snapshot.runtimeOwnerUid, 1000);
    expect(snapshot.telegramSidecarState, TelegramSidecarState.running);
    expect(snapshot.degraded, isFalse);
  });

  test('runtime event maps snapshot and redacted log payloads', () {
    final snapshotEvent = ProxyRuntimeEvent.fromMap({
      'type': 'snapshot',
      'snapshot': {'platform': 'linux', 'state': 'idle', 'message': 'ready'},
    });
    final logEvent = ProxyRuntimeEvent.fromMap({
      'type': 'log',
      'log': {
        'timestampMillis': 1000,
        'level': 'warning',
        'source': 'linux-runtime',
        'code': 'linux_queue_conflict',
        'message': 'Queue is busy',
      },
    });

    expect(snapshotEvent.kind, ProxyRuntimeEventKind.snapshot);
    expect(snapshotEvent.snapshot?.platform, ProxyPlatform.linux);
    expect(logEvent.kind, ProxyRuntimeEventKind.log);
    expect(logEvent.log?.level, ProxyRuntimeLogLevel.warning);
    expect(logEvent.log?.code, 'linux_queue_conflict');
  });
}
