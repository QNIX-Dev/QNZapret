import 'package:flutter/foundation.dart';

import '../backend/backend.dart';

enum RuntimeLogLevel { system, info, success, warning, error }

enum RuntimeLogSource { app, bridge, runtime }

extension RuntimeLogSourceX on RuntimeLogSource {
  String get title => switch (this) {
    RuntimeLogSource.app => 'Интерфейс',
    RuntimeLogSource.bridge => 'Мост',
    RuntimeLogSource.runtime => 'Сервис',
  };

  String get shortTitle => switch (this) {
    RuntimeLogSource.app => 'UI',
    RuntimeLogSource.bridge => 'Мост',
    RuntimeLogSource.runtime => 'Сервис',
  };
}

enum RuntimeStatusTone { neutral, info, success, warning, danger }

enum RuntimeStatusKind {
  bridge,
  service,
  engine,
  forwarder,
  interception,
  telegram,
}

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
    ProxyRuntimeState.running => 'Работает',
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

  bool get hasPartialFailure {
    return degraded ||
        partialFailureCode != null ||
        partialFailureMessage != null;
  }

  bool get hasVerifiedInterception {
    return interceptionReady &&
        (platform != ProxyPlatform.linux ||
            (queueRegistered && nftRulesInstalled));
  }

  bool get isOperational {
    return state == ProxyRuntimeState.running &&
        serviceActive &&
        hasVerifiedInterception &&
        !hasPartialFailure;
  }

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

    if (hasPartialFailure) {
      return 'Частичный сбой';
    }

    if (state == ProxyRuntimeState.running) {
      if (isOperational) {
        return 'Защита активна';
      }
      if (trafficInterceptionActive && !hasVerifiedInterception) {
        return 'Перехват не готов';
      }
      if (serviceActive && strategyEngineReady) {
        return 'Ядро готово';
      }
      if (serviceActive) {
        return 'Сервис активен';
      }
    }

    return state.label;
  }

  RuntimeStatusTone get statusTone {
    if (hasFailure) {
      return RuntimeStatusTone.danger;
    }
    if (hasPartialFailure) {
      return RuntimeStatusTone.warning;
    }
    if (isOperational) {
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

extension TelegramSidecarStateUi on TelegramSidecarState {
  String get label => switch (this) {
    TelegramSidecarState.unavailable => 'Недоступен',
    TelegramSidecarState.idle => 'Ожидает',
    TelegramSidecarState.starting => 'Запуск',
    TelegramSidecarState.running => 'Активен',
    TelegramSidecarState.stopping => 'Остановка',
    TelegramSidecarState.failed => 'Сбой',
  };

  RuntimeStatusTone get tone => switch (this) {
    TelegramSidecarState.running => RuntimeStatusTone.success,
    TelegramSidecarState.starting ||
    TelegramSidecarState.stopping => RuntimeStatusTone.info,
    TelegramSidecarState.failed => RuntimeStatusTone.danger,
    TelegramSidecarState.unavailable ||
    TelegramSidecarState.idle => RuntimeStatusTone.neutral,
  };

  bool get isTransitioning {
    return this == TelegramSidecarState.starting ||
        this == TelegramSidecarState.stopping;
  }
}
