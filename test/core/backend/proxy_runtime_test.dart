import 'package:flutter_test/flutter_test.dart';
import 'package:qnzapret/core/backend/proxy_runtime.dart';

void main() {
  test('ProxyLaunchConfig serializes to documented wire payload', () {
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
    });
  });

  test('ProxyRuntimeSnapshot parses Android wire payload', () {
    final snapshot = ProxyRuntimeSnapshot.fromMap({
      'platform': 'android',
      'state': 'running',
      'message': 'Android VPN service base is active.',
      'backendConnected': true,
      'vpnPermissionGranted': true,
      'serviceActive': true,
    });

    expect(snapshot.platform, ProxyPlatform.android);
    expect(snapshot.state, ProxyRuntimeState.running);
    expect(snapshot.backendConnected, isTrue);
    expect(snapshot.vpnPermissionGranted, isTrue);
    expect(snapshot.serviceActive, isTrue);
  });

  test('StubProxyRuntime keeps native bridge disconnected', () async {
    const runtime = StubProxyRuntime(ProxyPlatform.linux);

    final prepare = await runtime.prepare();
    final snapshot = await runtime.getSnapshot();

    expect(prepare.granted, isTrue);
    expect(snapshot.platform, ProxyPlatform.linux);
    expect(snapshot.state, ProxyRuntimeState.idle);
    expect(snapshot.backendConnected, isFalse);
  });
}
