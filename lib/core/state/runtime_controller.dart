import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../backend/runtime_bridge.dart';
import '../backend/runtime_models.dart';
import '../backend/stub_runtime_bridge.dart';
import '../motion/app_motion.dart';

final runtimeBridgeProvider = Provider<RuntimeBridge>((ref) {
  final bridge = StubRuntimeBridge();
  ref.onDispose(bridge.dispose);
  return bridge;
});

final runtimeControllerProvider =
    NotifierProvider<RuntimeController, RuntimeViewState>(
      RuntimeController.new,
    );

@immutable
class RuntimeViewState {
  const RuntimeViewState({
    required this.runtime,
    required this.logs,
    required this.autoScrollEnabled,
    required this.availableScenarios,
    this.latestFailure,
    this.selectedScenario,
  });

  factory RuntimeViewState.initial({
    List<RuntimeLaunchScenario> availableScenarios = const [],
    RuntimeLaunchScenario? selectedScenario,
  }) {
    return RuntimeViewState(
      runtime: CombinedRuntimeState.initial(),
      logs: const [],
      autoScrollEnabled: true,
      availableScenarios: availableScenarios,
      selectedScenario: selectedScenario,
    );
  }

  final CombinedRuntimeState runtime;
  final List<RuntimeLogEntry> logs;
  final bool autoScrollEnabled;
  final RuntimeFailure? latestFailure;
  final List<RuntimeLaunchScenario> availableScenarios;
  final RuntimeLaunchScenario? selectedScenario;

  bool get hasSimulationControls => availableScenarios.isNotEmpty;

  RuntimeViewState copyWith({
    CombinedRuntimeState? runtime,
    List<RuntimeLogEntry>? logs,
    bool? autoScrollEnabled,
    RuntimeFailure? latestFailure,
    bool clearFailure = false,
    List<RuntimeLaunchScenario>? availableScenarios,
    RuntimeLaunchScenario? selectedScenario,
  }) {
    return RuntimeViewState(
      runtime: runtime ?? this.runtime,
      logs: logs ?? this.logs,
      autoScrollEnabled: autoScrollEnabled ?? this.autoScrollEnabled,
      latestFailure: clearFailure ? null : latestFailure ?? this.latestFailure,
      availableScenarios: availableScenarios ?? this.availableScenarios,
      selectedScenario: selectedScenario ?? this.selectedScenario,
    );
  }
}

class RuntimeController extends Notifier<RuntimeViewState> {
  StreamSubscription<CombinedRuntimeState>? _runtimeSubscription;
  StreamSubscription<RuntimeLogEntry>? _logsSubscription;
  StreamSubscription<RuntimeFailure>? _failureSubscription;
  StreamSubscription<RuntimeLaunchScenario>? _scenarioSubscription;
  bool _rollbackScheduled = false;
  bool _disposed = false;

  RuntimeBridge get _bridge => ref.read(runtimeBridgeProvider);

  RuntimeBridgeSimulationControls? get _simulationControls {
    final bridge = _bridge;
    return bridge is RuntimeBridgeSimulationControls
        ? bridge as RuntimeBridgeSimulationControls
        : null;
  }

  @override
  RuntimeViewState build() {
    final simulationControls = _simulationControls;
    _bindStreams(simulationControls);
    ref.onDispose(() {
      _disposed = true;
      _runtimeSubscription?.cancel();
      _logsSubscription?.cancel();
      _failureSubscription?.cancel();
      _scenarioSubscription?.cancel();
    });

    unawaited(_hydrateInitialState(simulationControls));

    return RuntimeViewState.initial(
      availableScenarios: simulationControls?.availableScenarios ?? const [],
      selectedScenario: simulationControls?.currentScenario,
    );
  }

  Future<void> startAllServices() async {
    if (state.runtime.isTransitioning) {
      return;
    }

    state = state.copyWith(clearFailure: true);
    await _bridge.startAllServices();
  }

  Future<void> stopAllServices() async {
    await _bridge.stopAllServices();
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
    final simulationControls = _simulationControls;
    if (simulationControls == null) {
      return;
    }

    await simulationControls.setScenario(scenario);
  }

  void _bindStreams(RuntimeBridgeSimulationControls? simulationControls) {
    _runtimeSubscription = _bridge.watchRuntimeState().listen(
      _handleRuntimeState,
    );
    _logsSubscription = _bridge.watchLogs().listen((entry) {
      final nextLogs = [...state.logs, entry];
      if (nextLogs.length > 240) {
        nextLogs.removeRange(0, nextLogs.length - 240);
      }
      state = state.copyWith(logs: nextLogs);
    });
    _failureSubscription = _bridge.watchFailures().listen((failure) {
      state = state.copyWith(latestFailure: failure);
    });
    if (simulationControls != null) {
      _scenarioSubscription = simulationControls.watchScenario().listen((
        scenario,
      ) {
        state = state.copyWith(
          availableScenarios: simulationControls.availableScenarios,
          selectedScenario: scenario,
        );
      });
    }
  }

  Future<void> _hydrateInitialState(
    RuntimeBridgeSimulationControls? simulationControls,
  ) async {
    final combinedState = await _bridge.getCombinedState();
    final latestFailure = await _bridge.getLatestFailure();

    if (_disposed) {
      return;
    }

    state = state.copyWith(
      runtime: combinedState,
      latestFailure: latestFailure,
      availableScenarios: simulationControls?.availableScenarios ?? const [],
      selectedScenario: simulationControls?.currentScenario,
    );
  }

  void _handleRuntimeState(CombinedRuntimeState nextState) {
    state = state.copyWith(runtime: nextState);
    _scheduleRollbackIfNeeded(nextState);
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
          await _bridge.stopAllServices();
        }
        _rollbackScheduled = false;
      }),
    );
  }
}
