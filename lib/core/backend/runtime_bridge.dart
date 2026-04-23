import 'runtime_models.dart';

abstract interface class RuntimeBridge {
  RuntimeBridgeCapabilities get capabilities;

  Future<ServiceLaunchResult> startService(BypassServiceType serviceType);

  Future<CombinedRuntimeState> startAllServices();

  Future<CombinedRuntimeState> stopAllServices();

  Future<ServiceRuntimeStatus> getServiceStatus(BypassServiceType serviceType);

  Future<CombinedRuntimeState> getCombinedState();

  Future<RuntimeFailure?> getLatestFailure();

  Stream<CombinedRuntimeState> watchRuntimeState();

  Stream<RuntimeLogEntry> watchLogs();

  Stream<RuntimeFailure> watchFailures();

  void dispose();
}

enum RuntimeLaunchScenario { fullSuccess, nfqwsOnly, telegramOnly }

extension RuntimeLaunchScenarioX on RuntimeLaunchScenario {
  String get title => switch (this) {
    RuntimeLaunchScenario.fullSuccess => 'Оба сервиса',
    RuntimeLaunchScenario.nfqwsOnly => 'Только nfqws',
    RuntimeLaunchScenario.telegramOnly => 'Только Telegram',
  };

  String get description => switch (this) {
    RuntimeLaunchScenario.fullSuccess =>
      'Демонстрация полностью успешного запуска.',
    RuntimeLaunchScenario.nfqwsOnly =>
      'Проверка UX при частичном запуске без Telegram-портала.',
    RuntimeLaunchScenario.telegramOnly =>
      'Проверка UX при частичном запуске без nfqws.',
  };
}

abstract interface class RuntimeBridgeSimulationControls {
  List<RuntimeLaunchScenario> get availableScenarios;

  RuntimeLaunchScenario get currentScenario;

  Stream<RuntimeLaunchScenario> watchScenario();

  Future<void> setScenario(RuntimeLaunchScenario scenario);
}
