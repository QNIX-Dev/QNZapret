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
    });
  });

  test('runtime snapshot parses Android VPN bridge payload', () {
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
    expect(snapshot.message, 'Android VPN service base is active.');
    expect(snapshot.backendConnected, isTrue);
    expect(snapshot.vpnPermissionGranted, isTrue);
    expect(snapshot.serviceActive, isTrue);
  });

  test('prepare result parses native response', () {
    final result = ProxyPrepareResult.fromMap({
      'granted': true,
      'message': 'VPN permission granted.',
    });

    expect(result.granted, isTrue);
    expect(result.message, 'VPN permission granted.');
  });
}
