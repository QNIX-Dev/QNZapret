import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app/app.dart';
import 'core/backend/backend.dart';
import 'core/persistence/shared_preferences_provider.dart';
import 'core/state/runtime_controller.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final preferences = await SharedPreferences.getInstance();
  final runtime = createDefaultProxyRuntime();

  runApp(
    ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(preferences),
        proxyRuntimeProvider.overrideWithValue(runtime),
      ],
      child: const QnzapretApp(),
    ),
  );
}
