import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../backend/proxy_runtime.dart';
import '../motion/app_motion.dart';
import 'runtime_view_models.dart';

final proxyRuntimeProvider = Provider<ProxyRuntime>((ref) {
  return StubProxyRuntime(_currentProxyPlatform());
});

final runtimeControllerProvider =
    NotifierProvider<RuntimeController, RuntimeViewState>(
      RuntimeController.new,
    );

@immutable
class RuntimeViewState {
  const RuntimeViewState({
    required this.runtime,
    required this.proxySnapshot,
    required this.logs,
    required this.autoScrollEnabled,
    required this.availableScenarios,
    required this.selectedScenario,
    this.latestFailure,
  });

  factory RuntimeViewState.initial() {
    return RuntimeViewState(
      runtime: CombinedRuntimeState.initial(),
      proxySnapshot: ProxyRuntimeSnapshot.disconnected(_currentProxyPlatform()),
      logs: const [],
      autoScrollEnabled: true,
      availableScenarios: RuntimeLaunchScenario.values,
      selectedScenario: RuntimeLaunchScenario.fullSuccess,
    );
  }

  final CombinedRuntimeState runtime;
  final ProxyRuntimeSnapshot proxySnapshot;
  final List<RuntimeLogEntry> logs;
  final bool autoScrollEnabled;
  final RuntimeFailure? latestFailure;
  final List<RuntimeLaunchScenario> availableScenarios;
  final RuntimeLaunchScenario selectedScenario;

  bool get hasSimulationControls => availableScenarios.isNotEmpty;

  RuntimeViewState copyWith({
    CombinedRuntimeState? runtime,
    ProxyRuntimeSnapshot? proxySnapshot,
    List<RuntimeLogEntry>? logs,
    bool? autoScrollEnabled,
    RuntimeFailure? latestFailure,
    bool clearFailure = false,
    List<RuntimeLaunchScenario>? availableScenarios,
    RuntimeLaunchScenario? selectedScenario,
  }) {
    return RuntimeViewState(
      runtime: runtime ?? this.runtime,
      proxySnapshot: proxySnapshot ?? this.proxySnapshot,
      logs: logs ?? this.logs,
      autoScrollEnabled: autoScrollEnabled ?? this.autoScrollEnabled,
      latestFailure: clearFailure ? null : latestFailure ?? this.latestFailure,
      availableScenarios: availableScenarios ?? this.availableScenarios,
      selectedScenario: selectedScenario ?? this.selectedScenario,
    );
  }
}

class RuntimeController extends Notifier<RuntimeViewState> {
  Timer? _heartbeatTimer;
  int _logCounter = 0;
  int _heartbeatCounter = 0;
  bool _rollbackScheduled = false;
  bool _disposed = false;

  ProxyRuntime get _proxyRuntime => ref.read(proxyRuntimeProvider);

  @override
  RuntimeViewState build() {
    ref.onDispose(() {
      _disposed = true;
      _heartbeatTimer?.cancel();
    });

    unawaited(_hydrateInitialSnapshot());
    return RuntimeViewState.initial();
  }

  Future<void> startAllServices() async {
    if (state.runtime.isTransitioning) {
      return;
    }

    state = state.copyWith(clearFailure: true);

    try {
      final prepareResult = await _proxyRuntime.prepare();
      _emitLog(level: RuntimeLogLevel.system, message: prepareResult.message);
      if (!prepareResult.granted) {
        _recordFailure(
          RuntimeFailure(
            code: 'proxy_prepare_denied',
            message: 'Runtime не получил разрешение на запуск.',
            commandType: RuntimeCommandType.start,
            timestamp: DateTime.now(),
          ),
        );
        return;
      }

      await _proxyRuntime.start(_defaultLaunchConfig);
      await _refreshProxySnapshot();
    } catch (error) {
      _recordFailure(
        RuntimeFailure(
          code: 'proxy_start_failed',
          message: 'Не удалось передать команду запуска.',
          details: error.toString(),
          commandType: RuntimeCommandType.start,
          timestamp: DateTime.now(),
        ),
      );
      return;
    }

    _emitLog(level: RuntimeLogLevel.system, message: 'Запускаем сервисы.');
    await _startService(BypassServiceType.nfqws);
    await Future<void>.delayed(const Duration(milliseconds: 220));
    await _startService(BypassServiceType.telegramProxy);

    if (state.runtime.isFullyRunning) {
      _emitLog(
        level: RuntimeLogLevel.success,
        message: 'Оба сервиса запущены.',
      );
    } else if (state.runtime.hasPartialFailure) {
      _emitLog(
        level: RuntimeLogLevel.warning,
        message: 'Один сервис запустился, второй завершился с ошибкой.',
      );
    }

    _syncHeartbeat();
  }

  Future<void> stopAllServices() async {
    if (state.runtime.isIdle) {
      return;
    }

    _emitLog(level: RuntimeLogLevel.system, message: 'Останавливаем сервисы.');
    for (final service in BypassServiceType.values) {
      final serviceState = state.runtime.stateFor(service);
      if (serviceState.status != ServiceRuntimeStatus.idle) {
        _updateService(
          service,
          ServiceRuntimeStatus.stopping,
          clearFailure: true,
        );
      }
    }

    try {
      await _proxyRuntime.stop();
      await _refreshProxySnapshot();
    } catch (error) {
      _recordFailure(
        RuntimeFailure(
          code: 'proxy_stop_failed',
          message: 'Не удалось передать команду остановки.',
          details: error.toString(),
          commandType: RuntimeCommandType.stop,
          timestamp: DateTime.now(),
        ),
      );
    }

    await Future<void>.delayed(const Duration(milliseconds: 360));
    _heartbeatTimer?.cancel();
    _heartbeatTimer = null;

    for (final service in BypassServiceType.values) {
      _updateService(service, ServiceRuntimeStatus.idle, clearFailure: true);
    }

    _emitLog(
      level: RuntimeLogLevel.system,
      message: 'Все сервисы остановлены.',
    );
  }

  void clearLogs() {
    state = state.copyWith(logs: const []);
  }

  void setAutoScrollEnabled(bool value) {
    if (state.autoScrollEnabled == value) {
      return;
    }

    state = state.copyWith(autoScrollEnabled: value);
  }

  Future<void> setSimulationScenario(RuntimeLaunchScenario scenario) async {
    if (state.selectedScenario == scenario) {
      return;
    }

    if (state.runtime.hasActiveServices) {
      _emitLog(
        level: RuntimeLogLevel.warning,
        message: 'Для смены сценария сначала остановим активные сервисы.',
      );
      await stopAllServices();
    }

    state = state.copyWith(selectedScenario: scenario);
    _emitLog(
      level: RuntimeLogLevel.info,
      message: 'Выбран сценарий: ${scenario.title}.',
    );
  }

  Future<void> _hydrateInitialSnapshot() async {
    try {
      final snapshot = await _proxyRuntime.getSnapshot();
      if (!_disposed) {
        state = state.copyWith(proxySnapshot: snapshot);
      }
    } catch (_) {
      if (!_disposed) {
        state = state.copyWith(
          proxySnapshot: ProxyRuntimeSnapshot.disconnected(
            _currentProxyPlatform(),
          ),
        );
      }
    }

    _emitLog(
      level: RuntimeLogLevel.system,
      message: 'Система готова к запуску.',
    );
  }

  Future<void> _refreshProxySnapshot() async {
    final snapshot = await _proxyRuntime.getSnapshot();
    if (!_disposed) {
      state = state.copyWith(proxySnapshot: snapshot);
    }
  }

  Future<void> _startService(BypassServiceType serviceType) async {
    final currentState = state.runtime.stateFor(serviceType);
    if (currentState.status == ServiceRuntimeStatus.running) {
      return;
    }

    _updateService(
      serviceType,
      ServiceRuntimeStatus.starting,
      clearFailure: true,
    );
    _emitLog(
      level: RuntimeLogLevel.info,
      serviceType: serviceType,
      message: 'Подготавливаем ${serviceType.title.toLowerCase()}.',
    );
    await Future<void>.delayed(const Duration(milliseconds: 420));

    if (_shouldSucceed(serviceType)) {
      _updateService(
        serviceType,
        ServiceRuntimeStatus.running,
        clearFailure: true,
      );
      _emitLog(
        level: RuntimeLogLevel.success,
        serviceType: serviceType,
        message: '${serviceType.shortTitle} вошёл в рабочий режим.',
      );
      return;
    }

    final failure = RuntimeFailure(
      code: 'stub_launch_failed',
      message: '${serviceType.title} не смог завершить запуск.',
      details: 'Сервис остался в состоянии ошибки для проверки UX.',
      commandType: RuntimeCommandType.start,
      serviceType: serviceType,
      timestamp: DateTime.now(),
    );
    _recordFailure(failure);
    _updateService(
      serviceType,
      ServiceRuntimeStatus.failed,
      failure: failure,
      clearFailure: true,
    );
    _emitLog(
      level: RuntimeLogLevel.error,
      serviceType: serviceType,
      message: '${serviceType.shortTitle} вернул ошибку запуска.',
    );
  }

  bool _shouldSucceed(BypassServiceType serviceType) {
    return switch (state.selectedScenario) {
      RuntimeLaunchScenario.fullSuccess => true,
      RuntimeLaunchScenario.nfqwsOnly => serviceType == BypassServiceType.nfqws,
      RuntimeLaunchScenario.telegramOnly =>
        serviceType == BypassServiceType.telegramProxy,
    };
  }

  void _updateService(
    BypassServiceType type,
    ServiceRuntimeStatus status, {
    RuntimeFailure? failure,
    bool clearFailure = false,
  }) {
    final nextState = state.runtime.withServiceState(
      type,
      status,
      failure: failure,
      clearFailure: clearFailure,
    );
    state = state.copyWith(runtime: nextState);
    _scheduleRollbackIfNeeded(nextState);
  }

  void _recordFailure(RuntimeFailure failure) {
    state = state.copyWith(latestFailure: failure);
    _emitLog(
      level: RuntimeLogLevel.error,
      serviceType: failure.serviceType,
      message: failure.message,
    );
  }

  void _scheduleRollbackIfNeeded(CombinedRuntimeState nextState) {
    if (_rollbackScheduled || !nextState.hasPartialFailure) {
      return;
    }

    _rollbackScheduled = true;
    unawaited(
      Future<void>.delayed(
        AppMotionDurations.slow + const Duration(milliseconds: 240),
      ).then((_) async {
        if (_disposed) {
          return;
        }

        if (state.runtime.hasPartialFailure) {
          await stopAllServices();
        }
        _rollbackScheduled = false;
      }),
    );
  }

  void _emitLog({
    required RuntimeLogLevel level,
    required String message,
    BypassServiceType? serviceType,
  }) {
    _logCounter += 1;
    final nextLogs = [
      ...state.logs,
      RuntimeLogEntry(
        id: 'log_${_logCounter.toString().padLeft(4, '0')}',
        timestamp: DateTime.now(),
        level: level,
        message: message,
        serviceType: serviceType,
      ),
    ];
    if (nextLogs.length > 240) {
      nextLogs.removeRange(0, nextLogs.length - 240);
    }
    state = state.copyWith(logs: nextLogs);
  }

  void _syncHeartbeat() {
    final runningServices = state.runtime.orderedServices
        .where((service) => service.status == ServiceRuntimeStatus.running)
        .toList(growable: false);
    if (runningServices.isEmpty) {
      _heartbeatTimer?.cancel();
      _heartbeatTimer = null;
      return;
    }

    _heartbeatTimer?.cancel();
    _heartbeatTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      final service =
          runningServices[_heartbeatCounter % runningServices.length];
      final payload = switch (_heartbeatCounter % 4) {
        0 => 'Соединение держится стабильно.',
        1 => 'Сервис отвечает без задержек.',
        2 => 'Трафик проходит через активный маршрут.',
        _ => 'Сервис подтверждает готовность.',
      };

      _heartbeatCounter += 1;
      _emitLog(
        level: RuntimeLogLevel.info,
        serviceType: service.type,
        message: payload,
      );
    });
  }
}

const _defaultLaunchConfig = ProxyLaunchConfig(
  localHost: '127.0.0.1',
  localPort: 1080,
  poolSize: 8,
  cloudflareEnabled: true,
  secret: 'qnzapret-preview',
);

ProxyPlatform _currentProxyPlatform() {
  return switch (defaultTargetPlatform) {
    TargetPlatform.android => ProxyPlatform.android,
    TargetPlatform.linux => ProxyPlatform.linux,
    TargetPlatform.windows => ProxyPlatform.windows,
    _ => ProxyPlatform.android,
  };
}
