import 'package:flutter/material.dart';

import '../features/home/presentation/home_screen.dart';
import 'theme/app_theme.dart';

class QnzapretApp extends StatelessWidget {
  const QnzapretApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'QNZapret',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      home: const HomeScreen(),
    );
  }
}
