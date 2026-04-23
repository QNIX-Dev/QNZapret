import 'package:flutter/services.dart';

import 'proxy_runtime.dart';

final class AndroidProxyRuntime implements ProxyRuntime {
  const AndroidProxyRuntime({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel(_channelName);

  static const _channelName = 'dev.quriee.qnzapret/proxy_runtime';

  final MethodChannel _channel;

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
