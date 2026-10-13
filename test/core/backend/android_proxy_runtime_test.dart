import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qnzapret/core/backend/backend.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const method = MethodChannel('dev.qnzapret/proxy_runtime');
  const events = MethodChannel('dev.qnzapret/proxy_runtime/events');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(method, null);
    messenger.setMockMethodCallHandler(events, null);
  });

  test(
    'Android receives native snapshot transitions and lifecycle logs',
    () async {
      messenger.setMockMethodCallHandler(events, (call) async => null);
      const runtime = AndroidProxyRuntime();
      final received = <ProxyRuntimeEvent>[];
      final subscription = runtime.events.listen(received.add);
      await pumpEventQueue();
      await messenger.handlePlatformMessage(
        events.name,
        const StandardMethodCodec().encodeSuccessEnvelope({
          'type': 'snapshot',
          'snapshot': {
            'platform': 'android',
            'state': 'running',
            'vpnPermissionGranted': true,
            'tunnelActive': true,
            'trafficForwarderReady': true,
            'telegramSidecarState': 'running',
            'telegramCompatibilityProxyReady': false,
            'telegramCompatibilitySetupRequired': true,
          },
        }),
        (_) {},
      );
      await messenger.handlePlatformMessage(
        events.name,
        const StandardMethodCodec().encodeSuccessEnvelope({
          'type': 'log',
          'log': {
            'timestampMillis': 1000,
            'level': 'info',
            'source': 'android-runtime',
            'code': 'android_state_running',
            'message': 'Сервис запущен.',
          },
        }),
        (_) {},
      );
      await pumpEventQueue();
      expect(received, hasLength(2));
      expect(received.first.snapshot?.tunnelActive, isTrue);
      expect(received.first.snapshot?.telegramCompatibilityProxyReady, isFalse);
      expect(received.last.log?.code, 'android_state_running');
      await subscription.cancel();
    },
  );

  test('Android Telegram setup uses optional adapter capability', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(method, (call) async {
      calls.add(call);
      return null;
    });
    const runtime = AndroidProxyRuntime();
    await runtime.setupTelegramProxy();
    expect(calls.single.method, 'setupTelegram');
    expect(calls.single.arguments, isNull);
  });
}
