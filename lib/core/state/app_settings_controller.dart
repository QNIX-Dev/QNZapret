import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/routing/app_destination.dart';
import '../../app/theme/app_theme.dart';
import '../persistence/shared_preferences_provider.dart';

final appSettingsControllerProvider =
    NotifierProvider<AppSettingsController, AppSettingsState>(
      AppSettingsController.new,
    );

@immutable
class AppSettingsState {
  const AppSettingsState({
    required this.themeMode,
    required this.paletteId,
    required this.destination,
  });

  const AppSettingsState.fallback()
    : themeMode = ThemeMode.system,
      paletteId = AppPaletteId.roseOfDune,
      destination = AppDestination.home;

  final ThemeMode themeMode;
  final AppPaletteId paletteId;
  final AppDestination destination;

  AppSettingsState copyWith({
    ThemeMode? themeMode,
    AppPaletteId? paletteId,
    AppDestination? destination,
  }) {
    return AppSettingsState(
      themeMode: themeMode ?? this.themeMode,
      paletteId: paletteId ?? this.paletteId,
      destination: destination ?? this.destination,
    );
  }
}

class AppSettingsController extends Notifier<AppSettingsState> {
  static const _themeModeKey = 'settings.theme_mode';
  static const _paletteKey = 'settings.palette_id';
  static const _destinationKey = 'settings.last_destination';

  @override
  AppSettingsState build() {
    final preferences = ref.read(sharedPreferencesProvider);

    return AppSettingsState(
      themeMode: _readThemeMode(preferences),
      paletteId: _readPaletteId(preferences),
      destination: _readDestination(preferences),
    );
  }

  Future<void> setThemeMode(ThemeMode value) async {
    if (value == state.themeMode) {
      return;
    }

    state = state.copyWith(themeMode: value);
    await ref
        .read(sharedPreferencesProvider)
        .setString(_themeModeKey, value.name);
  }

  Future<void> setPalette(AppPaletteId value) async {
    if (value == state.paletteId) {
      return;
    }

    state = state.copyWith(paletteId: value);
    await ref
        .read(sharedPreferencesProvider)
        .setString(_paletteKey, value.name);
  }

  void setDestination(AppDestination value) {
    if (value == state.destination) {
      return;
    }

    state = state.copyWith(destination: value);
    unawaited(
      ref.read(sharedPreferencesProvider).setInt(_destinationKey, value.index),
    );
  }

  ThemeMode _readThemeMode(dynamic preferences) {
    final rawValue = preferences.getString(_themeModeKey);
    if (rawValue == null) {
      return ThemeMode.system;
    }

    for (final mode in ThemeMode.values) {
      if (mode.name == rawValue) {
        return mode;
      }
    }

    return ThemeMode.system;
  }

  AppPaletteId _readPaletteId(dynamic preferences) {
    final rawValue = preferences.getString(_paletteKey);
    if (rawValue == null) {
      return AppPaletteId.roseOfDune;
    }

    if (rawValue == 'emberwave') {
      return AppPaletteId.everforest;
    }

    for (final palette in AppPaletteId.values) {
      if (palette.name == rawValue) {
        return palette;
      }
    }

    return AppPaletteId.roseOfDune;
  }

  AppDestination _readDestination(dynamic preferences) {
    final rawValue = preferences.getInt(_destinationKey);
    if (rawValue == null) {
      return AppDestination.home;
    }

    return AppDestinationX.fromIndex(rawValue);
  }
}
