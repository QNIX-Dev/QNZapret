import 'package:flutter/services.dart';

import 'proxy_runtime.dart';

final class LinuxProxyRuntime implements ProxyRuntime {
  const LinuxProxyRuntime({MethodChannel? channel, EventChannel? eventChannel})
    : _channel = channel ?? const MethodChannel(_methodChannelName),
      _eventChannel = eventChannel ?? const EventChannel(_eventChannelName);

  static const _methodChannelName = 'dev.qnzapret/proxy_runtime';
  static const _eventChannelName = 'dev.qnzapret/proxy_runtime/events';

  final MethodChannel _channel;
  final EventChannel _eventChannel;

  @override
  ProxyPlatform get platform => ProxyPlatform.linux;

  @override
  Stream<ProxyRuntimeEvent> get events {
    return _eventChannel.receiveBroadcastStream().map((Object? event) {
      return ProxyRuntimeEvent.fromMap(
        (event as Map<Object?, Object?>?) ?? const <Object?, Object?>{},
      );
    });
  }

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
}
