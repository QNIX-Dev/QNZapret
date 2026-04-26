import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../backend/backend.dart';
import 'runtime_view_models.dart';

final proxyRuntimeProvider = Provider<ProxyRuntime>((ref) {
  return const StubProxyRuntime(ProxyPlatform.android);
});

final runtimeControllerProvider =
    NotifierProvider<RuntimeController, RuntimeViewState>(
      RuntimeController.new,
    );

@immutable
class RuntimeViewState {
  const RuntimeViewState({
    required this.snapshot,
    required this.launchConfig,
    required this.logs,
    required this.autoScrollEnabled,
    required this.isBusy,
    this.lastPrepareResult,
    this.latestFailure,
  });

  factory RuntimeViewState.initial(ProxyRuntime runtime) {
    return RuntimeViewState(
      snapshot: ProxyRuntimeSnapshot.initial(runtime.platform),
      launchConfig: ProxyLaunchConfig.defaultAndroidStrategy,
      logs: const [],
      autoScrollEnabled: true,
      isBusy: false,
    );
  }

  final ProxyRuntimeSnapshot snapshot;
  final ProxyLaunchConfig launchConfig;
  final List<RuntimeLogEntry> logs;
  final bool autoScrollEnabled;
  final bool isBusy;
  final ProxyPrepareResult? lastPrepareResult;
  final ProxyRuntimeFailure? latestFailure;

  bool get needsPrepare =>
      snapshot.platform == ProxyPlatform.android &&
      !snapshot.vpnPermissionGranted;

  bool get canStartCommand {
    return !isBusy &&
        !snapshot.serviceActive &&
        snapshot.state != ProxyRuntimeState.starting &&
        snapshot.state != ProxyRuntimeState.stopping;
  }

  bool get canStopCommand {
    return !isBusy &&
        (snapshot.serviceActive ||
            snapshot.state == ProxyRuntimeState.starting ||
            snapshot.state == ProxyRuntimeState.running);
  }

  bool get isUnavailable {
    return !snapshot.backendConnected &&
        snapshot.platform != ProxyPlatform.android;
  }

  String get primaryStatusLabel => snapshot.honestStatusLabel;

  String? get runtimeMessage {
    if (latestFailure case final failure?) {
      return failure.message;
    }
    if (snapshot.message.isEmpty) {
      return null;
    }
    return snapshot.message;
  }

  List<RuntimeStatusItem> get statusItems {
    return [
      RuntimeStatusItem(
        kind: RuntimeStatusKind.bridge,
        title: 'Связь с системой',
        statusLabel: snapshot.backendConnected ? 'Готова' : 'Ожидает',
        tone: snapshot.backendConnected
            ? RuntimeStatusTone.success
            : RuntimeStatusTone.neutral,
      ),
      RuntimeStatusItem(
        kind: RuntimeStatusKind.service,
        title: 'Сервис',
        statusLabel: snapshot.state == ProxyRuntimeState.running
            ? 'Активен'
            : snapshot.state.label,
        tone: snapshot.statusTone,
        animated: snapshot.isTransitioning || snapshot.serviceActive,
      ),
      RuntimeStatusItem(
        kind: RuntimeStatusKind.engine,
        title: 'Ядро обхода',
        statusLabel: snapshot.strategyEngineReady ? 'Готово' : 'Ожидает',
        tone: snapshot.strategyEngineReady
            ? RuntimeStatusTone.success
            : RuntimeStatusTone.neutral,
      ),
      RuntimeStatusItem(
        kind: RuntimeStatusKind.forwarder,
        title: 'Передача',
        statusLabel: snapshot.trafficForwarderReady
            ? 'Связана с TUN'
            : _capabilityLabel,
        tone: snapshot.trafficForwarderReady
            ? RuntimeStatusTone.success
            : snapshot.packetCodecReady ||
                  snapshot.udpForwarderReady ||
                  snapshot.tcpForwarderReady
            ? RuntimeStatusTone.warning
            : RuntimeStatusTone.neutral,
      ),
      RuntimeStatusItem(
        kind: RuntimeStatusKind.tunnel,
        title: 'Туннель',
        statusLabel: snapshot.tunnelActive ? 'Активен' : 'Выключен',
        tone: snapshot.tunnelActive
            ? RuntimeStatusTone.success
            : RuntimeStatusTone.neutral,
      ),
    ];
  }

  String get _capabilityLabel {
    final readyCount = [
      snapshot.packetCodecReady,
      snapshot.udpForwarderReady,
      snapshot.ipv6PacketCodecReady,
      snapshot.ipv6UdpForwarderReady,
      snapshot.tcpForwarderReady,
    ].where((ready) => ready).length;

    if (readyCount == 0) {
      return 'Ожидает';
    }
    return 'Готовность $readyCount/5';
  }

  RuntimeViewState copyWith({
    ProxyRuntimeSnapshot? snapshot,
    ProxyLaunchConfig? launchConfig,
    List<RuntimeLogEntry>? logs,
    bool? autoScrollEnabled,
    bool? isBusy,
    ProxyPrepareResult? lastPrepareResult,
    ProxyRuntimeFailure? latestFailure,
    bool clearPrepareResult = false,
    bool clearFailure = false,
  }) {
    return RuntimeViewState(
      snapshot: snapshot ?? this.snapshot,
      launchConfig: launchConfig ?? this.launchConfig,
      logs: logs ?? this.logs,
      autoScrollEnabled: autoScrollEnabled ?? this.autoScrollEnabled,
      isBusy: isBusy ?? this.isBusy,
      lastPrepareResult: clearPrepareResult
          ? null
          : lastPrepareResult ?? this.lastPrepareResult,
      latestFailure: clearFailure ? null : latestFailure ?? this.latestFailure,
    );
  }
}

class RuntimeController extends Notifier<RuntimeViewState> {
  ProxyRuntimeController? _runtimeController;
  int _logCounter = 0;
  bool _disposed = false;

  @override
  RuntimeViewState build() {
    final runtime = ref.read(proxyRuntimeProvider);
    final controller = ProxyRuntimeController(runtime: runtime);
    _runtimeController = controller;
    controller.addListener(_syncFromRuntimeController);

    ref.onDispose(() {
      _disposed = true;
      controller.removeListener(_syncFromRuntimeController);
      controller.dispose();
    });

    unawaited(Future<void>.microtask(initialize));
    return RuntimeViewState.initial(runtime);
  }

  Future<void> initialize() async {
    final controller = _runtimeController;
    if (controller == null) {
      return;
    }

    final previous = state.snapshot;
    final loaded = await controller.initialize();
    _syncFromRuntimeController();
    if (loaded) {
      _emitSnapshotChanges(previous, controller.snapshot);
    } else {
      _emitFailure(controller.lastFailure);
    }
  }

  Future<void> refreshRuntime() async {
    final controller = _runtimeController;
    if (controller == null) {
      return;
    }

    final previous = state.snapshot;
    final refreshed = await controller.refresh();
    _syncFromRuntimeController();
    if (refreshed) {
      _emitSnapshotChanges(previous, controller.snapshot);
      _emitLog(
        level: RuntimeLogLevel.system,
        source: RuntimeLogSource.bridge,
        message: 'Состояние обновлено.',
      );
    } else {
      _emitFailure(controller.lastFailure);
    }
  }

  Future<void> startRuntime() async {
    final controller = _runtimeController;
    if (controller == null || !state.canStartCommand) {
      return;
    }

    _emitLog(
      level: RuntimeLogLevel.system,
      source: RuntimeLogSource.app,
      message: state.needsPrepare
          ? 'Запрашиваем разрешение VPN.'
          : 'Передаем команду запуска сервисов.',
    );

    if (state.needsPrepare) {
      final prepare = await controller.prepare();
      _syncFromRuntimeController();
      if (prepare == null) {
        _emitFailure(controller.lastFailure);
        return;
      }

      _emitLog(
        level: prepare.granted
            ? RuntimeLogLevel.success
            : RuntimeLogLevel.warning,
        source: RuntimeLogSource.bridge,
        message: prepare.message,
      );
      if (!prepare.granted) {
        return;
      }
    }

    final previous = state.snapshot;
    final started = await controller.start();
    _syncFromRuntimeController();
    if (!started) {
      _emitFailure(controller.lastFailure);
      return;
    }

    _emitLog(
      level: RuntimeLogLevel.system,
      source: RuntimeLogSource.runtime,
      message: 'Команда запуска принята системой.',
    );
    _emitSnapshotChanges(previous, controller.snapshot);
  }

  Future<void> stopRuntime() async {
    final controller = _runtimeController;
    if (controller == null) {
      return;
    }

    _emitLog(
      level: RuntimeLogLevel.system,
      source: RuntimeLogSource.app,
      message: 'Передаем команду остановки сервисов.',
    );

    final previous = state.snapshot;
    final stopped = await controller.stop();
    _syncFromRuntimeController();
    if (!stopped) {
      _emitFailure(controller.lastFailure);
      return;
    }

    _emitLog(
      level: RuntimeLogLevel.system,
      source: RuntimeLogSource.runtime,
      message: 'Сервисы остановлены.',
    );
    _emitSnapshotChanges(previous, controller.snapshot);
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

  void _syncFromRuntimeController() {
    final controller = _runtimeController;
    if (_disposed || controller == null) {
      return;
    }

    state = state.copyWith(
      snapshot: controller.snapshot,
      launchConfig: controller.launchConfig,
      isBusy: controller.isBusy,
      lastPrepareResult: controller.lastPrepareResult,
      latestFailure: controller.lastFailure,
      clearFailure: controller.lastFailure == null,
    );
  }

  void _emitFailure(ProxyRuntimeFailure? failure) {
    if (failure == null) {
      return;
    }

    _emitLog(
      level: RuntimeLogLevel.error,
      source: RuntimeLogSource.bridge,
      message: failure.message,
    );
  }

  void _emitSnapshotChanges(
    ProxyRuntimeSnapshot previous,
    ProxyRuntimeSnapshot next,
  ) {
    if (previous.state != next.state) {
      _emitLog(
        level: next.hasFailure ? RuntimeLogLevel.error : RuntimeLogLevel.info,
        source: RuntimeLogSource.runtime,
        message: 'Состояние сервиса: ${next.honestStatusLabel}.',
      );
    }

    if (!previous.strategyEngineReady && next.strategyEngineReady) {
      _emitLog(
        level: RuntimeLogLevel.success,
        source: RuntimeLogSource.runtime,
        message: 'Ядро обхода готово.',
      );
    }

    if (!previous.trafficForwarderReady && next.trafficForwarderReady) {
      _emitLog(
        level: RuntimeLogLevel.success,
        source: RuntimeLogSource.runtime,
        message: 'Передача трафика связана с TUN.',
      );
    }

    if (!previous.tunnelActive && next.tunnelActive) {
      _emitLog(
        level: RuntimeLogLevel.success,
        source: RuntimeLogSource.runtime,
        message: 'TUN-туннель активен.',
      );
    }

    if (next.state == ProxyRuntimeState.running &&
        next.serviceActive &&
        !next.tunnelActive &&
        !previous.serviceActive) {
      _emitLog(
        level: RuntimeLogLevel.info,
        source: RuntimeLogSource.runtime,
        message: 'Системный сервис активен; туннель не поднят.',
      );
    }
  }

  void _emitLog({
    required RuntimeLogLevel level,
    required String message,
    RuntimeLogSource? source,
  }) {
    _logCounter += 1;
    final nextLogs = [
      ...state.logs,
      RuntimeLogEntry(
        id: 'log_${_logCounter.toString().padLeft(4, '0')}',
        timestamp: DateTime.now(),
        level: level,
        message: message,
        source: source,
      ),
    ];
    if (nextLogs.length > 240) {
      nextLogs.removeRange(0, nextLogs.length - 240);
    }
    state = state.copyWith(logs: nextLogs);
  }
}
