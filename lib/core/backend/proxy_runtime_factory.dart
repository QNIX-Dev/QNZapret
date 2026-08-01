import 'package:flutter/foundation.dart';

import 'android_proxy_runtime.dart';
import 'linux_proxy_runtime.dart';
import 'proxy_runtime.dart';

ProxyRuntime createDefaultProxyRuntime({TargetPlatform? targetPlatform}) {
  final platform = targetPlatform ?? defaultTargetPlatform;

  return switch (platform) {
    TargetPlatform.android => const AndroidProxyRuntime(),
    TargetPlatform.linux => const LinuxProxyRuntime(),
    TargetPlatform.windows => const StubProxyRuntime(ProxyPlatform.windows),
    TargetPlatform.fuchsia ||
    TargetPlatform.iOS ||
    TargetPlatform.macOS => const StubProxyRuntime(ProxyPlatform.android),
  };
}
