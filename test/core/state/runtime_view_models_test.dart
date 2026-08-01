import 'package:flutter_test/flutter_test.dart';
import 'package:qnzapret/core/backend/backend.dart';
import 'package:qnzapret/core/state/runtime_view_models.dart';

void main() {
  test('running is successful only when interception is ready', () {
    final snapshot = ProxyRuntimeSnapshot.fromMap({
      'platform': 'linux',
      'state': 'running',
      'serviceActive': true,
      'strategyEngineReady': true,
      'trafficForwarderReady': true,
      'trafficInterceptionMode': 'linuxNfqueue',
      'trafficInterceptionActive': true,
      'queueRegistered': true,
      'nftRulesInstalled': true,
      'interceptionReady': false,
      'telegramSidecarState': 'running',
    });

    expect(snapshot.isOperational, isFalse);
    expect(snapshot.honestStatusLabel, 'Перехват не готов');
    expect(snapshot.statusTone, RuntimeStatusTone.warning);
  });

  test('degraded runtime never receives successful running semantics', () {
    final snapshot = ProxyRuntimeSnapshot.fromMap({
      'platform': 'linux',
      'state': 'running',
      'serviceActive': true,
      'trafficInterceptionActive': true,
      'trafficForwarderReady': true,
      'interceptionReady': true,
      'telegramSidecarState': 'failed',
      'partialFailureCode': 'linux_telegram_start_failed',
    });

    expect(snapshot.isOperational, isFalse);
    expect(snapshot.hasPartialFailure, isTrue);
    expect(snapshot.honestStatusLabel, 'Частичный сбой');
    expect(snapshot.statusTone, RuntimeStatusTone.warning);
  });

  test('Linux readiness also requires queue and nft confirmations', () {
    final snapshot = ProxyRuntimeSnapshot.fromMap({
      'platform': 'linux',
      'state': 'running',
      'serviceActive': true,
      'trafficInterceptionActive': true,
      'trafficForwarderReady': true,
      'queueRegistered': false,
      'nftRulesInstalled': true,
      'interceptionReady': true,
      'telegramSidecarState': 'running',
    });

    expect(snapshot.hasVerifiedInterception, isFalse);
    expect(snapshot.isOperational, isFalse);
    expect(snapshot.honestStatusLabel, 'Перехват не готов');
  });

  test('ready interception without partial failure is operational', () {
    final snapshot = ProxyRuntimeSnapshot.fromMap({
      'platform': 'android',
      'state': 'running',
      'serviceActive': true,
      'trafficForwarderReady': true,
      'tunnelActive': true,
      'telegramCompatibilityProxyReady': true,
    });

    expect(snapshot.isOperational, isTrue);
    expect(snapshot.honestStatusLabel, 'Защита активна');
    expect(snapshot.statusTone, RuntimeStatusTone.success);
  });
}
