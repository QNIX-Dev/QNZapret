import 'package:flutter/foundation.dart';

import '../backend/backend.dart';

enum RuntimeLogLevel { system, info, success, warning, error }

enum RuntimeLogSource { app, bridge, runtime }

extension RuntimeLogSourceX on RuntimeLogSource {
  String get title => switch (this) {
    RuntimeLogSource.app => 'Интерфейс',
    RuntimeLogSource.bridge => 'Bridge',
    RuntimeLogSource.runtime => 'Runtime',
  };

  String get shortTitle => switch (this) {
    RuntimeLogSource.app => 'UI',
    RuntimeLogSource.bridge => 'Bridge',
    RuntimeLogSource.runtime => 'Runtime',
  };
}

enum RuntimeStatusTone { neutral, info, success, warning, danger }

enum RuntimeStatusKind { bridge, service, engine, forwarder, tunnel }

@immutable
class RuntimeStatusItem {
  const RuntimeStatusItem({
    required this.kind,
    required this.title,
    required this.statusLabel,
    required this.tone,
    this.animated = false,
  });

  final RuntimeStatusKind kind;
  final String title;
  final String statusLabel;
  final RuntimeStatusTone tone;
  final bool animated;
}

@immutable
class RuntimeLogEntry {
  const RuntimeLogEntry({
    required this.id,
    required this.timestamp,
    required this.level,
    required this.message,
    this.source,
  });

  final String id;
  final DateTime timestamp;
  final RuntimeLogLevel level;
  final String message;
  final RuntimeLogSource? source;
}

extension ProxyRuntimeStateUi on ProxyRuntimeState {
  bool get isBusy =>
      this == ProxyRuntimeState.starting || this == ProxyRuntimeState.stopping;

  String get label => switch (this) {
    ProxyRuntimeState.idle => 'Готов',
    ProxyRuntimeState.starting => 'Запуск',
    ProxyRuntimeState.running => 'Service on',
    ProxyRuntimeState.stopping => 'Остановка',
    ProxyRuntimeState.failed => 'Сбой',
  };
}

extension ProxyPlatformUi on ProxyPlatform {
  String get label => switch (this) {
    ProxyPlatform.android => 'Android',
    ProxyPlatform.linux => 'Linux',
    ProxyPlatform.windows => 'Windows',
  };
}

extension ProxyRuntimeSnapshotUi on ProxyRuntimeSnapshot {
  bool get isTransitioning => state.isBusy;

  bool get hasFailure => state == ProxyRuntimeState.failed;

  bool get hasRuntimeActivity {
    return serviceActive ||
        state == ProxyRuntimeState.starting ||
        state == ProxyRuntimeState.running ||
        state == ProxyRuntimeState.stopping;
  }

  String get honestStatusLabel {
    if (hasFailure) {
      return 'Сбой';
    }

    if (state == ProxyRuntimeState.running) {
      if (tunnelActive && trafficForwarderReady) {
        return 'Туннель активен';
      }
      if (serviceActive && strategyEngineReady) {
        return 'Engine ready';
      }
      if (serviceActive) {
        return 'Service on';
      }
    }

    return state.label;
  }

  RuntimeStatusTone get statusTone {
    if (hasFailure) {
      return RuntimeStatusTone.danger;
    }
    if (tunnelActive && trafficForwarderReady) {
      return RuntimeStatusTone.success;
    }
    if (state == ProxyRuntimeState.starting ||
        state == ProxyRuntimeState.stopping) {
      return RuntimeStatusTone.info;
    }
    if (serviceActive || strategyEngineReady) {
      return RuntimeStatusTone.warning;
    }
    return RuntimeStatusTone.neutral;
  }
}
