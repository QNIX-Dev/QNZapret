import 'dart:collection';

import 'package:flutter/foundation.dart';

enum BypassServiceType { nfqws, telegramProxy }

extension BypassServiceTypeX on BypassServiceType {
  String get technicalName => switch (this) {
    BypassServiceType.nfqws => 'nfqws',
    BypassServiceType.telegramProxy => 'Proxy',
  };

  String get title => switch (this) {
    BypassServiceType.nfqws => 'Основной сервис',
    BypassServiceType.telegramProxy => 'Telegram-портал',
  };

  String get shortTitle => switch (this) {
    BypassServiceType.nfqws => 'Основной',
    BypassServiceType.telegramProxy => 'Telegram',
  };
}

enum ServiceRuntimeStatus { idle, starting, running, stopping, failed }

extension ServiceRuntimeStatusX on ServiceRuntimeStatus {
  bool get isBusy =>
      this == ServiceRuntimeStatus.starting ||
      this == ServiceRuntimeStatus.stopping;

  String get label => switch (this) {
    ServiceRuntimeStatus.idle => 'Готов',
    ServiceRuntimeStatus.starting => 'Запуск',
    ServiceRuntimeStatus.running => 'Активен',
    ServiceRuntimeStatus.stopping => 'Остановка',
    ServiceRuntimeStatus.failed => 'Сбой',
  };
}

enum RuntimeCommandType { start, stop }

enum RuntimeLogLevel { system, info, success, warning, error }

enum RuntimeLaunchScenario { fullSuccess, nfqwsOnly, telegramOnly }

extension RuntimeLaunchScenarioX on RuntimeLaunchScenario {
  String get title => switch (this) {
    RuntimeLaunchScenario.fullSuccess => 'Оба сервиса',
    RuntimeLaunchScenario.nfqwsOnly => 'Только nfqws',
    RuntimeLaunchScenario.telegramOnly => 'Только Telegram',
  };
}

@immutable
class RuntimeFailure {
  const RuntimeFailure({
    required this.code,
    required this.message,
    required this.commandType,
    required this.timestamp,
    this.serviceType,
    this.details,
    this.recoverable = true,
  });

  final String code;
  final String message;
  final RuntimeCommandType commandType;
  final DateTime timestamp;
  final BypassServiceType? serviceType;
  final String? details;
  final bool recoverable;
}

@immutable
class ServiceRuntimeState {
  const ServiceRuntimeState({
    required this.type,
    required this.status,
    required this.updatedAt,
    this.failure,
  });

  factory ServiceRuntimeState.idle(
    BypassServiceType type, {
    DateTime? updatedAt,
  }) {
    return ServiceRuntimeState(
      type: type,
      status: ServiceRuntimeStatus.idle,
      updatedAt: updatedAt ?? DateTime.now(),
    );
  }

  final BypassServiceType type;
  final ServiceRuntimeStatus status;
  final DateTime updatedAt;
  final RuntimeFailure? failure;

  bool get isActive => status == ServiceRuntimeStatus.running;

  ServiceRuntimeState copyWith({
    ServiceRuntimeStatus? status,
    DateTime? updatedAt,
    RuntimeFailure? failure,
    bool clearFailure = false,
  }) {
    return ServiceRuntimeState(
      type: type,
      status: status ?? this.status,
      updatedAt: updatedAt ?? this.updatedAt,
      failure: clearFailure ? null : failure ?? this.failure,
    );
  }
}

@immutable
class CombinedRuntimeState {
  CombinedRuntimeState({
    required Map<BypassServiceType, ServiceRuntimeState> services,
    required this.updatedAt,
  }) : services = UnmodifiableMapView(services);

  factory CombinedRuntimeState.initial({DateTime? updatedAt}) {
    final timestamp = updatedAt ?? DateTime.now();

    return CombinedRuntimeState(
      services: {
        for (final service in BypassServiceType.values)
          service: ServiceRuntimeState.idle(service, updatedAt: timestamp),
      },
      updatedAt: timestamp,
    );
  }

  final UnmodifiableMapView<BypassServiceType, ServiceRuntimeState> services;
  final DateTime updatedAt;

  Iterable<ServiceRuntimeState> get orderedServices =>
      BypassServiceType.values.map(stateFor);

  bool get isIdle => orderedServices.every(
    (service) => service.status == ServiceRuntimeStatus.idle,
  );

  bool get isFullyRunning => orderedServices.every(
    (service) => service.status == ServiceRuntimeStatus.running,
  );

  bool get hasRunningServices => orderedServices.any(
    (service) => service.status == ServiceRuntimeStatus.running,
  );

  bool get hasFailure => orderedServices.any(
    (service) => service.status == ServiceRuntimeStatus.failed,
  );

  bool get hasActiveServices => orderedServices.any(
    (service) => service.status != ServiceRuntimeStatus.idle,
  );

  bool get isTransitioning =>
      orderedServices.any((service) => service.status.isBusy);

  bool get hasPartialFailure => hasFailure && hasRunningServices;

  ServiceRuntimeStatus get summaryStatus {
    if (orderedServices.any(
      (service) => service.status == ServiceRuntimeStatus.stopping,
    )) {
      return ServiceRuntimeStatus.stopping;
    }

    if (orderedServices.any(
      (service) => service.status == ServiceRuntimeStatus.starting,
    )) {
      return ServiceRuntimeStatus.starting;
    }

    if (hasFailure) {
      return ServiceRuntimeStatus.failed;
    }

    if (isFullyRunning) {
      return ServiceRuntimeStatus.running;
    }

    return ServiceRuntimeStatus.idle;
  }

  ServiceRuntimeState stateFor(BypassServiceType type) {
    return services[type] ?? ServiceRuntimeState.idle(type);
  }

  CombinedRuntimeState withServiceState(
    BypassServiceType type,
    ServiceRuntimeStatus status, {
    RuntimeFailure? failure,
    bool clearFailure = false,
    DateTime? updatedAt,
  }) {
    final timestamp = updatedAt ?? DateTime.now();
    final nextServices = Map<BypassServiceType, ServiceRuntimeState>.from(
      services,
    );
    final currentState = stateFor(type);
    nextServices[type] = currentState.copyWith(
      status: status,
      updatedAt: timestamp,
      failure: failure,
      clearFailure: clearFailure,
    );

    return CombinedRuntimeState(services: nextServices, updatedAt: timestamp);
  }
}

@immutable
class RuntimeLogEntry {
  const RuntimeLogEntry({
    required this.id,
    required this.timestamp,
    required this.level,
    required this.message,
    this.serviceType,
  });

  final String id;
  final DateTime timestamp;
  final RuntimeLogLevel level;
  final String message;
  final BypassServiceType? serviceType;
}
