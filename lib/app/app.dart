import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/app_metadata.dart';
import '../core/motion/app_motion.dart';
import '../core/state/app_settings_controller.dart';
import 'navigation/app_shell.dart';
import 'theme/app_theme.dart';

class QnzapretApp extends ConsumerWidget {
  const QnzapretApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settings = ref.watch(appSettingsControllerProvider);

    return MaterialApp(
      title: appDisplayName,
      debugShowCheckedModeBanner: false,
      themeMode: settings.themeMode,
      theme: AppTheme.createTheme(
        paletteId: settings.paletteId,
        brightness: Brightness.light,
      ),
      darkTheme: AppTheme.createTheme(
        paletteId: settings.paletteId,
        brightness: Brightness.dark,
      ),
      themeAnimationCurve: AppMotionCurves.standard,
      themeAnimationDuration: AppMotionDurations.page,
      home: const AppShell(),
    );
  }
}
