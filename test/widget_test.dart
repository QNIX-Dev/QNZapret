import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qnzapret/app/app.dart';
import 'package:qnzapret/core/persistence/shared_preferences_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets('renders home shell with primary action', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final preferences = await SharedPreferences.getInstance();

    await tester.pumpWidget(
      ProviderScope(
        overrides: [sharedPreferencesProvider.overrideWithValue(preferences)],
        child: const QnzapretApp(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('QNZapret'), findsOneWidget);
    expect(find.text('Запустить сервисы'), findsOneWidget);
    expect(find.text('Основной сервис'), findsOneWidget);
  });

  testWidgets('mobile settings opens as a page with top blocks visible', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    SharedPreferences.setMockInitialValues({});
    final preferences = await SharedPreferences.getInstance();

    await tester.pumpWidget(
      ProviderScope(
        overrides: [sharedPreferencesProvider.overrideWithValue(preferences)],
        child: const QnzapretApp(),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('Настройки'));
    await tester.pumpAndSettle();

    expect(find.text('Настройки'), findsOneWidget);
    expect(find.text('QNZapret'), findsOneWidget);
    expect(find.text('Режим темы'), findsOneWidget);
    expect(find.text('Цветовая схема'), findsOneWidget);
  });

  testWidgets('mobile logs layout renders at compact width', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    SharedPreferences.setMockInitialValues({'settings.last_destination': 1});
    final preferences = await SharedPreferences.getInstance();

    await tester.pumpWidget(
      ProviderScope(
        overrides: [sharedPreferencesProvider.overrideWithValue(preferences)],
        child: const QnzapretApp(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Логи'), findsOneWidget);
    expect(
      find.text('Здесь появляются последние сообщения сервисов.'),
      findsOneWidget,
    );
    expect(find.textContaining('строк'), findsWidgets);
  });
}
