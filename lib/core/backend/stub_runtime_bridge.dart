import 'dart:async';

import 'runtime_bridge.dart';
import 'runtime_models.dart';

final class StubRuntimeBridge
    implements RuntimeBridge, RuntimeBridgeSimulationControls {
  StubRuntimeBridge({
    RuntimeLaunchScenario initialScenario = RuntimeLaunchScenario.fullSuccess,
  }) : _currentScenario = initialScenario {
    _emitLog(
      level: RuntimeLogLevel.system,
      message: 'Система готова к запуску.',
    );
    _scenarioController.add(_currentScenario);
  }

  final StreamController<CombinedRuntimeState> _runtimeStateController =
      StreamController<CombinedRuntimeState>.broadcast();
  final StreamController<RuntimeLogEntry> _logController =
      StreamController<RuntimeLogEntry>.broadcast();
  final StreamController<RuntimeFailure> _failureController =
      StreamController<RuntimeFailure>.broadcast();
  final StreamController<RuntimeLaunchScenario> _scenarioController =
      StreamController<RuntimeLaunchScenario>.broadcast();

  CombinedRuntimeState _state = CombinedRuntimeState.initial();
  RuntimeFailure? _latestFailure;
  RuntimeLaunchScenario _currentScenario;
  Timer? _heartbeatTimer;
  int _logCounter = 0;
  int _heartbeatCounter = 0;

  @override
  RuntimeBridgeCapabilities get capabilities => const RuntimeBridgeCapabilities(
    supportedServices: {
      BypassServiceType.nfqws,
      BypassServiceType.telegramProxy,
    },
    supportsLogStream: true,
    supportsSimulationControls: true,
  );

  @override
  List<RuntimeLaunchScenario> get availableScenarios =>
      RuntimeLaunchScenario.values;

  @override
  RuntimeLaunchScenario get currentScenario => _currentScenario;

  @override
  Future<ServiceLaunchResult> startService(
    BypassServiceType serviceType,
  ) async {
    final currentState = _state.stateFor(serviceType);
    if (currentState.status == ServiceRuntimeStatus.running) {
      return ServiceLaunchResult(
        serviceType: serviceType,
        status: currentState.status,
        timestamp: DateTime.now(),
      );
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

    final success = _shouldSucceed(serviceType);
    if (success) {
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
      _syncHeartbeat();

      return ServiceLaunchResult(
        serviceType: serviceType,
        status: ServiceRuntimeStatus.running,
        timestamp: DateTime.now(),
      );
    }

    final failure = RuntimeFailure(
      code: 'stub_launch_failed',
      message: '${serviceType.title} не смог завершить запуск.',
      details:
          'Сервис остался в состоянии ошибки для проверки сценария частичного запуска.',
      commandType: RuntimeCommandType.start,
      serviceType: serviceType,
      timestamp: DateTime.now(),
    );
    _latestFailure = failure;
    _updateService(
      serviceType,
      ServiceRuntimeStatus.failed,
      failure: failure,
      clearFailure: true,
    );
    _failureController.add(failure);
    _emitLog(
      level: RuntimeLogLevel.error,
      serviceType: serviceType,
      message: '${serviceType.shortTitle} вернул ошибку запуска.',
    );

    return ServiceLaunchResult(
      serviceType: serviceType,
      status: ServiceRuntimeStatus.failed,
      timestamp: DateTime.now(),
      failure: failure,
    );
  }

  @override
  Future<CombinedRuntimeState> startAllServices() async {
    if (_state.isTransitioning) {
      return _state;
    }

    _latestFailure = null;
    _emitLog(level: RuntimeLogLevel.system, message: 'Запускаем сервисы.');
    await startService(BypassServiceType.nfqws);
    await Future<void>.delayed(const Duration(milliseconds: 220));
    await startService(BypassServiceType.telegramProxy);

    if (_state.isFullyRunning) {
      _emitLog(
        level: RuntimeLogLevel.success,
        message: 'Оба сервиса запущены.',
      );
    } else if (_state.hasPartialFailure) {
      _emitLog(
        level: RuntimeLogLevel.warning,
        message: 'Один сервис запустился, второй завершился с ошибкой.',
      );
    }

    _syncHeartbeat();
    return _state;
  }

  @override
  Future<CombinedRuntimeState> stopAllServices() async {
    if (_state.isIdle) {
      return _state;
    }

    _emitLog(level: RuntimeLogLevel.system, message: 'Останавливаем сервисы.');
    for (final service in BypassServiceType.values) {
      final serviceState = _state.stateFor(service);
      if (serviceState.status != ServiceRuntimeStatus.idle) {
        _updateService(
          service,
          ServiceRuntimeStatus.stopping,
          clearFailure: true,
        );
      }
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
    return _state;
  }

  @override
  Future<ServiceRuntimeStatus> getServiceStatus(
    BypassServiceType serviceType,
  ) async {
    return _state.stateFor(serviceType).status;
  }

  @override
  Future<CombinedRuntimeState> getCombinedState() async => _state;

  @override
  Future<RuntimeFailure?> getLatestFailure() async => _latestFailure;

  @override
  Stream<CombinedRuntimeState> watchRuntimeState() =>
      _runtimeStateController.stream;

  @override
  Stream<RuntimeLogEntry> watchLogs() => _logController.stream;

  @override
  Stream<RuntimeFailure> watchFailures() => _failureController.stream;

  @override
  Stream<RuntimeLaunchScenario> watchScenario() => _scenarioController.stream;

  @override
  Future<void> setScenario(RuntimeLaunchScenario scenario) async {
    if (_currentScenario == scenario) {
      return;
    }

    if (_state.hasActiveServices) {
      _emitLog(
        level: RuntimeLogLevel.warning,
        message: 'Для смены сценария сначала остановим активные сервисы.',
      );
      await stopAllServices();
    }

    _currentScenario = scenario;
    _scenarioController.add(_currentScenario);
    _emitLog(
      level: RuntimeLogLevel.info,
      message: 'Выбран сценарий: ${scenario.title}.',
    );
  }

  @override
  void dispose() {
    _heartbeatTimer?.cancel();
    _runtimeStateController.close();
    _logController.close();
    _failureController.close();
    _scenarioController.close();
  }

  bool _shouldSucceed(BypassServiceType serviceType) {
    return switch (_currentScenario) {
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
    _state = _state.withServiceState(
      type,
      status,
      failure: failure,
      clearFailure: clearFailure,
    );
    _runtimeStateController.add(_state);
  }

  void _emitLog({
    required RuntimeLogLevel level,
    required String message,
    BypassServiceType? serviceType,
  }) {
    _logCounter += 1;
    _logController.add(
      RuntimeLogEntry(
        id: 'log_${_logCounter.toString().padLeft(4, '0')}',
        timestamp: DateTime.now(),
        level: level,
        message: message,
        serviceType: serviceType,
      ),
    );
  }

  void _syncHeartbeat() {
    final runningServices = _state.orderedServices
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
