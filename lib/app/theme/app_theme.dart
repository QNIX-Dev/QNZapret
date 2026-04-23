import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

final class AppTheme {
  static const _surface = Color(0xFFF6F0E6);
  static const _surfaceDark = Color(0xFF121315);
  static const _ink = Color(0xFF17191C);
  static const _inkSoft = Color(0xFF2E3136);
  static const _mint = Color(0xFF8BF0C7);
  static const _mintDark = Color(0xFF0E8E67);
  static const _amber = Color(0xFFF3B23A);
  static const _slate = Color(0xFF79808A);
  static const _night = Color(0xFF090A0C);

  static ThemeData get lightTheme {
    final scheme =
        ColorScheme.fromSeed(
          seedColor: _mintDark,
          brightness: Brightness.light,
          surface: _surface,
        ).copyWith(
          primary: _ink,
          secondary: _amber,
          tertiary: _mintDark,
          surface: _surface,
        );

    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      scaffoldBackgroundColor: _surface,
      textTheme: GoogleFonts.manropeTextTheme().apply(
        bodyColor: _ink,
        displayColor: _ink,
      ),
      cardTheme: const CardThemeData(
        color: Colors.white,
        elevation: 0,
        margin: EdgeInsets.zero,
      ),
    );
  }

  static ThemeData get darkTheme {
    final scheme =
        ColorScheme.fromSeed(
          seedColor: _mint,
          brightness: Brightness.dark,
          surface: _surfaceDark,
        ).copyWith(
          primary: _surface,
          secondary: _amber,
          tertiary: _mint,
          surface: _surfaceDark,
        );

    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      scaffoldBackgroundColor: _night,
      textTheme: GoogleFonts.manropeTextTheme().apply(
        bodyColor: _surface,
        displayColor: _surface,
      ),
      cardTheme: const CardThemeData(
        color: Color(0xFF15181C),
        elevation: 0,
        margin: EdgeInsets.zero,
      ),
    );
  }

  static Color get ink => _ink;
  static Color get inkSoft => _inkSoft;
  static Color get mint => _mint;
  static Color get amber => _amber;
  static Color get slate => _slate;
}
