import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qnzapret/app/app.dart';
import 'package:qnzapret/core/motion/app_scroll_behavior.dart';
import 'package:qnzapret/core/motion/app_smooth_scroll.dart';
import 'package:qnzapret/core/persistence/shared_preferences_provider.dart';
import 'package:qnzapret/core/ui/components/terminal_illustration.dart';
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
    await tester.pump(const Duration(milliseconds: 700));

    expect(find.text('QNZapret'), findsOneWidget);
    expect(find.text('Разрешить запуск'), findsOneWidget);
    expect(find.text('Сервис'), findsOneWidget);
    expect(find.text('Перехват'), findsOneWidget);
    expect(find.text('Telegram'), findsOneWidget);
    expect(find.text('Runtime bridge'), findsNothing);
    expect(find.text('Запустить runtime'), findsNothing);
    expect(find.byType(Scrollbar), findsNothing);

    final app = tester.widget<MaterialApp>(find.byType(MaterialApp));
    expect(app.scrollBehavior, isA<AppScrollBehavior>());
    final homeScroll = tester.widget<SingleChildScrollView>(
      find.byType(SingleChildScrollView),
    );
    expect(homeScroll.physics, isA<AppBouncingScrollPhysics>());
    expect(homeScroll.controller, isA<AppScrollController>());
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
    await tester.pump(const Duration(milliseconds: 700));

    await tester.tap(find.byTooltip('Настройки'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 700));

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
    await tester.pump(const Duration(milliseconds: 700));

    expect(find.text('Логи'), findsOneWidget);
    expect(
      find.text('Здесь появляются последние сообщения сервисов.'),
      findsOneWidget,
    );
    expect(find.textContaining('строк'), findsWidgets);
    expect(find.byType(Scrollbar), findsNothing);

    final terminalBottom = tester
        .getBottomLeft(find.byType(TerminalIllustration))
        .dy;
    final chips = find.byKey(const ValueKey('logs-hero-facts-centered'));
    final chipsTop = tester.getTopLeft(chips).dy;
    final chipsCenter = tester.getCenter(chips).dx;

    expect(chipsTop, greaterThan(terminalBottom));
    expect(chipsCenter, closeTo(195, 36));
  });

  testWidgets('desktop logs hero keeps settings in the left column', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1200, 800);
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
    await tester.pump(const Duration(milliseconds: 700));

    final settingsIcon = find.byIcon(Icons.tune_rounded);
    final title = find.text('Логи');
    final illustration = find.byType(TerminalIllustration);

    expect(settingsIcon, findsOneWidget);
    expect(title, findsOneWidget);
    expect(illustration, findsOneWidget);
    expect(find.byType(Scrollbar), findsNothing);

    final settingsBottom = tester.getBottomLeft(settingsIcon).dy;
    final titleTop = tester.getTopLeft(title).dy;
    final settingsCenter = tester.getCenter(settingsIcon).dx;
    final illustrationLeft = tester.getTopLeft(illustration).dx;

    expect(settingsBottom, lessThan(titleTop));
    expect(settingsCenter, lessThan(illustrationLeft));
  });
}
