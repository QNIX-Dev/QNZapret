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
}
