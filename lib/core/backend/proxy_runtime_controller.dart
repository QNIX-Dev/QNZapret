import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'proxy_runtime.dart';

final class ProxyRuntimeFailure {
  const ProxyRuntimeFailure({
    required this.code,
    required this.message,
    this.details,
  });

  factory ProxyRuntimeFailure.fromError(Object error) {
    if (error is PlatformException) {
      return ProxyRuntimeFailure(
        code: error.code,
        message: error.message ?? 'Platform runtime command failed.',
        details: error.details?.toString(),
      );
    }

    if (error is MissingPluginException) {
      return ProxyRuntimeFailure(
        code: 'missing_plugin',
        message: error.message ?? 'Native runtime bridge is not registered.',
      );
    }

    return ProxyRuntimeFailure(
      code: 'runtime_error',
      message: error.toString(),
    );
  }

  final String code;
  final String message;
  final String? details;
}

final class ProxyRuntimeController extends ChangeNotifier {
  ProxyRuntimeController({
    required this.runtime,
    ProxyLaunchConfig initialConfig = ProxyLaunchConfig.defaultAndroidStrategy,
  }) : _launchConfig = initialConfig,
       _snapshot = ProxyRuntimeSnapshot.initial(runtime.platform);

  final ProxyRuntime runtime;

  ProxyRuntimeSnapshot _snapshot;
  ProxyLaunchConfig _launchConfig;
  ProxyPrepareResult? _lastPrepareResult;
  ProxyRuntimeFailure? _lastFailure;
  bool _busy = false;
  bool _disposed = false;

  ProxyRuntimeSnapshot get snapshot => _snapshot;

  ProxyLaunchConfig get launchConfig => _launchConfig;

  ProxyPrepareResult? get lastPrepareResult => _lastPrepareResult;

  ProxyRuntimeFailure? get lastFailure => _lastFailure;

  bool get isBusy => _busy;

  bool get needsPrepare {
    return _snapshot.platform == ProxyPlatform.android &&
        !_snapshot.vpnPermissionGranted;
  }

  bool get canStart {
    return !_busy && _snapshot.vpnPermissionGranted && !_snapshot.serviceActive;
  }

  bool get canStop {
    return !_busy && _snapshot.serviceActive;
  }

  bool get isActive {
    return _snapshot.serviceActive ||
        _snapshot.state == ProxyRuntimeState.starting ||
        _snapshot.state == ProxyRuntimeState.running ||
        _snapshot.state == ProxyRuntimeState.stopping;
  }

  void updateLaunchConfig(ProxyLaunchConfig config) {
    _launchConfig = config;
    _emit();
  }

  Future<bool> initialize() {
    return refresh();
  }

  Future<bool> refresh() async {
    final result = await _capture(() async {
      await _refreshSnapshot();
      return true;
    });
    return result ?? false;
  }

  Future<ProxyPrepareResult?> prepare() {
    return _capture(() async {
      final result = await runtime.prepare();
      _lastPrepareResult = result;
      await _refreshSnapshot();
      return result;
    });
  }

  Future<bool> start([ProxyLaunchConfig? config]) async {
    if (config != null) {
      _launchConfig = config;
    }

    final result = await _capture(() async {
      await runtime.start(_launchConfig);
      await _refreshSnapshot();
      return true;
    });
    return result ?? false;
  }

  Future<bool> stop() async {
    final result = await _capture(() async {
      await runtime.stop();
      await _refreshSnapshot();
      return true;
    });
    return result ?? false;
  }

  Future<void> _refreshSnapshot() async {
    _snapshot = await runtime.getSnapshot();
  }

  Future<T?> _capture<T>(Future<T> Function() action) async {
    if (_busy) {
      _lastFailure = const ProxyRuntimeFailure(
        code: 'runtime_busy',
        message: 'Runtime command is already in progress.',
      );
      _emit();
      return null;
    }

    _busy = true;
    _lastFailure = null;
    _emit();

    try {
      return await action();
    } catch (error) {
      _lastFailure = ProxyRuntimeFailure.fromError(error);
      return null;
    } finally {
      _busy = false;
      _emit();
    }
  }

  void _emit() {
    if (!_disposed) {
      notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
