import 'package:flutter/services.dart';

import 'proxy_runtime.dart';

final class AndroidProxyRuntime
    implements ProxyRuntime, TelegramProxySetupRuntime {
  const AndroidProxyRuntime({
    MethodChannel? channel,
    EventChannel? eventChannel,
  }) : _channel = channel ?? const MethodChannel(_channelName),
       _eventChannel =
           eventChannel ??
           const EventChannel('dev.qnzapret/proxy_runtime/events');

  static const _channelName = 'dev.qnzapret/proxy_runtime';

  final MethodChannel _channel;
  final EventChannel _eventChannel;

  @override
  Stream<ProxyRuntimeEvent> get events =>
      _eventChannel.receiveBroadcastStream().map(
        (event) => ProxyRuntimeEvent.fromMap(
          (event as Map<Object?, Object?>?) ?? const <Object?, Object?>{},
        ),
      );

  @override
  ProxyPlatform get platform => ProxyPlatform.android;

  @override
  Future<ProxyPrepareResult> prepare() async {
    final result = await _channel.invokeMapMethod<String, Object?>('prepare');
    return ProxyPrepareResult.fromMap(result ?? const <String, Object?>{});
  }

  @override
  Future<ProxyRuntimeSnapshot> getSnapshot() async {
    final result = await _channel.invokeMapMethod<String, Object?>(
      'getSnapshot',
    );
    return ProxyRuntimeSnapshot.fromMap(result ?? const <String, Object?>{});
  }

  @override
  Future<void> start(ProxyLaunchConfig config) {
    return _channel.invokeMethod<void>('start', {'config': config.toMap()});
  }

  @override
  Future<void> stop() {
    return _channel.invokeMethod<void>('stop');
  }

  @override
  Future<void> setupTelegramProxy() =>
      _channel.invokeMethod<void>('setupTelegram');
}
