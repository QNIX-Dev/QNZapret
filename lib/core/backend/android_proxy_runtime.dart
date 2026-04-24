import 'package:flutter/services.dart';

import 'proxy_runtime.dart';

final class AndroidProxyRuntime implements ProxyRuntime {
  AndroidProxyRuntime({
    MethodChannel channel = const MethodChannel(
      'dev.quriee.qnzapret/proxy_runtime',
    ),
  }) : _channel = channel;

  final MethodChannel _channel;

  @override
  Future<ProxyPrepareResult> prepare() async {
    final result = await _channel.invokeMapMethod<Object?, Object?>('prepare');
    return ProxyPrepareResult.fromMap(result ?? const {});
  }

  @override
  Future<ProxyRuntimeSnapshot> getSnapshot() async {
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'getSnapshot',
    );
    return ProxyRuntimeSnapshot.fromMap(result ?? const {});
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
