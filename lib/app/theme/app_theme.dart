import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

enum AppPaletteId { monokai, gruvbox, catppuccin, nord, everforest, roseOfDune }

@immutable
class AppPaletteSpec {
  const AppPaletteSpec({
    required this.id,
    required this.label,
    required this.caption,
    required this.lightSurface,
    required this.darkSurface,
    required this.lightAccent,
    required this.darkAccent,
    required this.lightSecondary,
    required this.darkSecondary,
    required this.lightTertiary,
    required this.darkTertiary,
    required this.lightPreview,
    required this.darkPreview,
  });

  final AppPaletteId id;
  final String label;
  final String caption;
  final Color lightSurface;
  final Color darkSurface;
  final Color lightAccent;
  final Color darkAccent;
  final Color lightSecondary;
  final Color darkSecondary;
  final Color lightTertiary;
  final Color darkTertiary;
  final List<Color> lightPreview;
  final List<Color> darkPreview;

  Color surfaceFor(Brightness brightness) =>
      brightness == Brightness.dark ? darkSurface : lightSurface;

  Color accentFor(Brightness brightness) =>
      brightness == Brightness.dark ? darkAccent : lightAccent;

  Color secondaryFor(Brightness brightness) =>
      brightness == Brightness.dark ? darkSecondary : lightSecondary;

  Color tertiaryFor(Brightness brightness) =>
      brightness == Brightness.dark ? darkTertiary : lightTertiary;

  List<Color> previewFor(Brightness brightness) =>
      brightness == Brightness.dark ? darkPreview : lightPreview;
}

@immutable
class AppThemeExtras extends ThemeExtension<AppThemeExtras> {
  const AppThemeExtras({
    required this.backgroundGradient,
    required this.accentGradient,
    required this.glowColor,
    required this.glassSurface,
    required this.mainGlassSurface,
    required this.glassStroke,
    required this.navigationSurface,
    required this.navigationStroke,
    required this.navigationFocusGradient,
    required this.navigationFocusForeground,
    required this.navigationIcon,
    required this.terminalSurface,
    required this.terminalToolbar,
    required this.terminalStroke,
    required this.terminalLineSurface,
    required this.terminalBadgeSurface,
    required this.terminalText,
    required this.terminalMutedText,
    required this.terminalAccent,
    required this.success,
    required this.warning,
    required this.danger,
    required this.mutedForeground,
  });

  final LinearGradient backgroundGradient;
  final LinearGradient accentGradient;
  final Color glowColor;
  final Color glassSurface;
  final Color mainGlassSurface;
  final Color glassStroke;
  final Color navigationSurface;
  final Color navigationStroke;
  final LinearGradient navigationFocusGradient;
  final Color navigationFocusForeground;
  final Color navigationIcon;
  final Color terminalSurface;
  final Color terminalToolbar;
  final Color terminalStroke;
  final Color terminalLineSurface;
  final Color terminalBadgeSurface;
  final Color terminalText;
  final Color terminalMutedText;
  final Color terminalAccent;
  final Color success;
  final Color warning;
  final Color danger;
  final Color mutedForeground;

  @override
  AppThemeExtras copyWith({
    LinearGradient? backgroundGradient,
    LinearGradient? accentGradient,
    Color? glowColor,
    Color? glassSurface,
    Color? mainGlassSurface,
    Color? glassStroke,
    Color? navigationSurface,
    Color? navigationStroke,
    LinearGradient? navigationFocusGradient,
    Color? navigationFocusForeground,
    Color? navigationIcon,
    Color? terminalSurface,
    Color? terminalToolbar,
    Color? terminalStroke,
    Color? terminalLineSurface,
    Color? terminalBadgeSurface,
    Color? terminalText,
    Color? terminalMutedText,
    Color? terminalAccent,
    Color? success,
    Color? warning,
    Color? danger,
    Color? mutedForeground,
  }) {
    return AppThemeExtras(
      backgroundGradient: backgroundGradient ?? this.backgroundGradient,
      accentGradient: accentGradient ?? this.accentGradient,
      glowColor: glowColor ?? this.glowColor,
      glassSurface: glassSurface ?? this.glassSurface,
      mainGlassSurface: mainGlassSurface ?? this.mainGlassSurface,
      glassStroke: glassStroke ?? this.glassStroke,
      navigationSurface: navigationSurface ?? this.navigationSurface,
      navigationStroke: navigationStroke ?? this.navigationStroke,
      navigationFocusGradient:
          navigationFocusGradient ?? this.navigationFocusGradient,
      navigationFocusForeground:
          navigationFocusForeground ?? this.navigationFocusForeground,
      navigationIcon: navigationIcon ?? this.navigationIcon,
      terminalSurface: terminalSurface ?? this.terminalSurface,
      terminalToolbar: terminalToolbar ?? this.terminalToolbar,
      terminalStroke: terminalStroke ?? this.terminalStroke,
      terminalLineSurface: terminalLineSurface ?? this.terminalLineSurface,
      terminalBadgeSurface: terminalBadgeSurface ?? this.terminalBadgeSurface,
      terminalText: terminalText ?? this.terminalText,
      terminalMutedText: terminalMutedText ?? this.terminalMutedText,
      terminalAccent: terminalAccent ?? this.terminalAccent,
      success: success ?? this.success,
      warning: warning ?? this.warning,
      danger: danger ?? this.danger,
      mutedForeground: mutedForeground ?? this.mutedForeground,
    );
  }

  @override
  AppThemeExtras lerp(ThemeExtension<AppThemeExtras>? other, double t) {
    if (other is! AppThemeExtras) {
      return this;
    }

    return AppThemeExtras(
      backgroundGradient:
          LinearGradient.lerp(
            backgroundGradient,
            other.backgroundGradient,
            t,
          ) ??
          backgroundGradient,
      accentGradient:
          LinearGradient.lerp(accentGradient, other.accentGradient, t) ??
          accentGradient,
      glowColor: Color.lerp(glowColor, other.glowColor, t) ?? glowColor,
      glassSurface:
          Color.lerp(glassSurface, other.glassSurface, t) ?? glassSurface,
      mainGlassSurface:
          Color.lerp(mainGlassSurface, other.mainGlassSurface, t) ??
          mainGlassSurface,
      glassStroke: Color.lerp(glassStroke, other.glassStroke, t) ?? glassStroke,
      navigationSurface:
          Color.lerp(navigationSurface, other.navigationSurface, t) ??
          navigationSurface,
      navigationStroke:
          Color.lerp(navigationStroke, other.navigationStroke, t) ??
          navigationStroke,
      navigationFocusGradient:
          LinearGradient.lerp(
            navigationFocusGradient,
            other.navigationFocusGradient,
            t,
          ) ??
          navigationFocusGradient,
      navigationFocusForeground:
          Color.lerp(
            navigationFocusForeground,
            other.navigationFocusForeground,
            t,
          ) ??
          navigationFocusForeground,
      navigationIcon:
          Color.lerp(navigationIcon, other.navigationIcon, t) ?? navigationIcon,
      terminalSurface:
          Color.lerp(terminalSurface, other.terminalSurface, t) ??
          terminalSurface,
      terminalToolbar:
          Color.lerp(terminalToolbar, other.terminalToolbar, t) ??
          terminalToolbar,
      terminalStroke:
          Color.lerp(terminalStroke, other.terminalStroke, t) ?? terminalStroke,
      terminalLineSurface:
          Color.lerp(terminalLineSurface, other.terminalLineSurface, t) ??
          terminalLineSurface,
      terminalBadgeSurface:
          Color.lerp(terminalBadgeSurface, other.terminalBadgeSurface, t) ??
          terminalBadgeSurface,
      terminalText:
          Color.lerp(terminalText, other.terminalText, t) ?? terminalText,
      terminalMutedText:
          Color.lerp(terminalMutedText, other.terminalMutedText, t) ??
          terminalMutedText,
      terminalAccent:
          Color.lerp(terminalAccent, other.terminalAccent, t) ?? terminalAccent,
      success: Color.lerp(success, other.success, t) ?? success,
      warning: Color.lerp(warning, other.warning, t) ?? warning,
      danger: Color.lerp(danger, other.danger, t) ?? danger,
      mutedForeground:
          Color.lerp(mutedForeground, other.mutedForeground, t) ??
          mutedForeground,
    );
  }
}

extension AppThemeContextX on BuildContext {
  AppThemeExtras get appThemeExtras =>
      Theme.of(this).extension<AppThemeExtras>()!;
}

final class AppTheme {
  static final List<AppPaletteSpec> palettes = [
    const AppPaletteSpec(
      id: AppPaletteId.monokai,
      label: 'Monokai',
      caption: 'Pink, yellow и green в духе классики.',
      lightSurface: Color(0xFFF8F0DF),
      darkSurface: Color(0xFF272822),
      lightAccent: Color(0xFFC0275B),
      darkAccent: Color(0xFFF92672),
      lightSecondary: Color(0xFF6F8F13),
      darkSecondary: Color(0xFFA6E22E),
      lightTertiary: Color(0xFFC98A11),
      darkTertiary: Color(0xFFE6DB74),
      lightPreview: [
        Color(0xFFF8F0DF),
        Color(0xFFE9D7B8),
        Color(0xFFC0275B),
        Color(0xFF6F8F13),
        Color(0xFFC98A11),
      ],
      darkPreview: [
        Color(0xFF272822),
        Color(0xFF3A3B34),
        Color(0xFFF92672),
        Color(0xFFA6E22E),
        Color(0xFFE6DB74),
      ],
    ),
    const AppPaletteSpec(
      id: AppPaletteId.gruvbox,
      label: 'Gruvbox',
      caption: 'Warm bg, orange, green, aqua и red.',
      lightSurface: Color(0xFFFBF1C7),
      darkSurface: Color(0xFF282828),
      lightAccent: Color(0xFFD65D0E),
      darkAccent: Color(0xFFFABD2F),
      lightSecondary: Color(0xFF98971A),
      darkSecondary: Color(0xFF8EC07C),
      lightTertiary: Color(0xFF458588),
      darkTertiary: Color(0xFF83A598),
      lightPreview: [
        Color(0xFFFBF1C7),
        Color(0xFFF2E5BC),
        Color(0xFFD65D0E),
        Color(0xFF98971A),
        Color(0xFF458588),
      ],
      darkPreview: [
        Color(0xFF282828),
        Color(0xFF3C3836),
        Color(0xFFFABD2F),
        Color(0xFF8EC07C),
        Color(0xFF83A598),
      ],
    ),
    const AppPaletteSpec(
      id: AppPaletteId.catppuccin,
      label: 'Catppuccin',
      caption: 'Мягкая пастель с лавандовым центром.',
      lightSurface: Color(0xFFEFF1F5),
      darkSurface: Color(0xFF1E1E2E),
      lightAccent: Color(0xFF8839EF),
      darkAccent: Color(0xFFCBA6F7),
      lightSecondary: Color(0xFF1E66F5),
      darkSecondary: Color(0xFF89B4FA),
      lightTertiary: Color(0xFFEA76CB),
      darkTertiary: Color(0xFFF5C2E7),
      lightPreview: [
        Color(0xFFEFF1F5),
        Color(0xFFDCE0E8),
        Color(0xFF8839EF),
        Color(0xFF1E66F5),
      ],
      darkPreview: [
        Color(0xFF1E1E2E),
        Color(0xFF313244),
        Color(0xFFCBA6F7),
        Color(0xFF89B4FA),
      ],
    ),
    const AppPaletteSpec(
      id: AppPaletteId.nord,
      label: 'Nord',
      caption: 'Сдержанный северный тон и чистый ледяной акцент.',
      lightSurface: Color(0xFFECEFF4),
      darkSurface: Color(0xFF2E3440),
      lightAccent: Color(0xFF5E81AC),
      darkAccent: Color(0xFF88C0D0),
      lightSecondary: Color(0xFF88C0D0),
      darkSecondary: Color(0xFF81A1C1),
      lightTertiary: Color(0xFF81A1C1),
      darkTertiary: Color(0xFFB48EAD),
      lightPreview: [
        Color(0xFFECEFF4),
        Color(0xFFD8DEE9),
        Color(0xFF5E81AC),
        Color(0xFF88C0D0),
        Color(0xFF81A1C1),
      ],
      darkPreview: [
        Color(0xFF2E3440),
        Color(0xFF3B4252),
        Color(0xFF88C0D0),
        Color(0xFF81A1C1),
      ],
    ),
    const AppPaletteSpec(
      id: AppPaletteId.everforest,
      label: 'Everforest',
      caption: 'Natural green/yellow с мягким контрастом.',
      lightSurface: Color(0xFFFDF6E3),
      darkSurface: Color(0xFF2D353B),
      lightAccent: Color(0xFF8DA101),
      darkAccent: Color(0xFFA7C080),
      lightSecondary: Color(0xFFDFA000),
      darkSecondary: Color(0xFFDBBC7F),
      lightTertiary: Color(0xFF35A77C),
      darkTertiary: Color(0xFF83C092),
      lightPreview: [
        Color(0xFFFDF6E3),
        Color(0xFFF2EFDF),
        Color(0xFF8DA101),
        Color(0xFFDFA000),
        Color(0xFF35A77C),
      ],
      darkPreview: [
        Color(0xFF2D353B),
        Color(0xFF3A464C),
        Color(0xFFA7C080),
        Color(0xFFDBBC7F),
        Color(0xFF83C092),
      ],
    ),
    const AppPaletteSpec(
      id: AppPaletteId.roseOfDune,
      label: 'Rose of Dune',
      caption: 'Песочно-золотая схема с розовым теплом.',
      lightSurface: Color(0xFFFFEED6),
      darkSurface: Color(0xFF2B2119),
      lightAccent: Color(0xFFC88B2C),
      darkAccent: Color(0xFFE8B965),
      lightSecondary: Color(0xFFC45F68),
      darkSecondary: Color(0xFFE08F87),
      lightTertiary: Color(0xFF9E7B38),
      darkTertiary: Color(0xFFCFA76A),
      lightPreview: [
        Color(0xFFFFEED6),
        Color(0xFFF2C982),
        Color(0xFFC88B2C),
        Color(0xFFC45F68),
        Color(0xFF9E7B38),
      ],
      darkPreview: [
        Color(0xFF2B2119),
        Color(0xFF463424),
        Color(0xFFE8B965),
        Color(0xFFE08F87),
        Color(0xFFCFA76A),
      ],
    ),
  ];

  static AppPaletteSpec paletteFor(AppPaletteId id) {
    return palettes.firstWhere((palette) => palette.id == id);
  }

  static ThemeData createTheme({
    required AppPaletteId paletteId,
    required Brightness brightness,
  }) {
    final palette = paletteFor(paletteId);
    final isDark = brightness == Brightness.dark;
    final surface = palette.surfaceFor(brightness);
    final accent = palette.accentFor(brightness);
    final secondary = palette.secondaryFor(brightness);
    final tertiary = palette.tertiaryFor(brightness);
    final onSurface = isDark
        ? const Color(0xFFF6F1E8)
        : const Color(0xFF1C1714);
    final success = isDark ? const Color(0xFF8DE4B5) : const Color(0xFF2B8A5B);
    final warning = isDark ? const Color(0xFFF4C46A) : const Color(0xFFB17318);
    final danger = isDark ? const Color(0xFFF0A2A3) : const Color(0xFFB44E4C);

    final scheme =
        ColorScheme.fromSeed(
          seedColor: accent,
          brightness: brightness,
        ).copyWith(
          primary: accent,
          onPrimary: _foregroundFor(accent),
          primaryContainer: _blend(accent, surface, isDark ? 0.28 : 0.14),
          onPrimaryContainer: onSurface,
          secondary: secondary,
          onSecondary: _foregroundFor(secondary),
          secondaryContainer: _blend(secondary, surface, isDark ? 0.22 : 0.12),
          onSecondaryContainer: onSurface,
          tertiary: tertiary,
          onTertiary: _foregroundFor(tertiary),
          tertiaryContainer: _blend(tertiary, surface, isDark ? 0.2 : 0.14),
          onTertiaryContainer: onSurface,
          surface: surface,
          onSurface: onSurface,
          surfaceContainerLowest: _blend(
            onSurface,
            surface,
            isDark ? 0.02 : 0.01,
          ),
          surfaceContainerLow: _blend(onSurface, surface, isDark ? 0.04 : 0.02),
          surfaceContainer: _blend(accent, surface, isDark ? 0.1 : 0.04),
          surfaceContainerHigh: _blend(
            secondary,
            surface,
            isDark ? 0.14 : 0.08,
          ),
          surfaceContainerHighest: _blend(
            tertiary,
            surface,
            isDark ? 0.18 : 0.12,
          ),
          surfaceDim: _blend(Colors.black, surface, isDark ? 0.16 : 0.05),
          surfaceBright: _blend(Colors.white, surface, isDark ? 0.08 : 0.16),
          outline: _blend(onSurface, surface, isDark ? 0.3 : 0.18),
          outlineVariant: _blend(accent, surface, isDark ? 0.24 : 0.1),
          error: danger,
          onError: _foregroundFor(danger),
          errorContainer: _blend(danger, surface, isDark ? 0.18 : 0.12),
          onErrorContainer: onSurface,
          shadow: Colors.black.withValues(alpha: isDark ? 0.42 : 0.14),
          scrim: Colors.black.withValues(alpha: 0.42),
          surfaceTint: accent,
        );

    final navigationFocusColor = _mix(accent, secondary, 0.45);
    final terminalSurface = isDark
        ? _blend(Colors.black, surface, 0.18)
        : _blend(accent, surface, 0.055);
    final terminalText = _foregroundFor(
      terminalSurface,
    ).withValues(alpha: 0.94);
    final terminalToolbar = isDark
        ? _blend(accent, terminalSurface, 0.12)
        : _blend(Colors.white, _blend(accent, terminalSurface, 0.10), 0.48);
    final terminalLineSurface = isDark
        ? _blend(Colors.white, terminalSurface, 0.05)
        : _blend(Colors.white, terminalSurface, 0.56);
    final terminalBadgeSurface = isDark
        ? _blend(Colors.white, terminalSurface, 0.09)
        : _blend(accent, terminalSurface, 0.09);

    final extras = AppThemeExtras(
      backgroundGradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [
          _blend(accent, surface, isDark ? 0.16 : 0.08),
          surface,
          _blend(secondary, surface, isDark ? 0.08 : 0.05),
          _blend(tertiary, surface, isDark ? 0.12 : 0.04),
        ],
      ),
      accentGradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [accent, _mix(accent, secondary, 0.48), tertiary],
      ),
      glowColor: accent.withValues(alpha: isDark ? 0.36 : 0.22),
      glassSurface: _blend(Colors.white, surface, isDark ? 0.06 : 0.76),
      mainGlassSurface: _blend(
        Colors.white,
        surface,
        isDark ? 0.08 : 0.72,
      ).withValues(alpha: isDark ? 0.88 : 0.84),
      glassStroke: _blend(accent, surface, isDark ? 0.22 : 0.1),
      navigationSurface: _blend(
        Colors.white,
        surface,
        isDark ? 0.08 : 0.42,
      ).withValues(alpha: isDark ? 0.62 : 0.7),
      navigationStroke: _blend(
        accent,
        surface,
        isDark ? 0.28 : 0.16,
      ).withValues(alpha: 0.78),
      navigationFocusGradient: LinearGradient(
        begin: Alignment.centerLeft,
        end: Alignment.centerRight,
        colors: [accent, _mix(accent, secondary, 0.52)],
      ),
      navigationFocusForeground: _foregroundFor(navigationFocusColor),
      navigationIcon: onSurface.withValues(alpha: isDark ? 0.72 : 0.68),
      terminalSurface: terminalSurface,
      terminalToolbar: terminalToolbar,
      terminalStroke: _blend(accent, terminalSurface, isDark ? 0.28 : 0.2),
      terminalLineSurface: terminalLineSurface,
      terminalBadgeSurface: terminalBadgeSurface,
      terminalText: terminalText,
      terminalMutedText: terminalText.withValues(alpha: 0.58),
      terminalAccent: accent,
      success: success,
      warning: warning,
      danger: danger,
      mutedForeground: onSurface.withValues(alpha: isDark ? 0.72 : 0.64),
    );

    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: scheme,
      scaffoldBackgroundColor: surface,
      textTheme: _buildTextTheme(onSurface),
      dividerColor: scheme.outlineVariant,
      pageTransitionsTheme: const PageTransitionsTheme(),
      cardTheme: CardThemeData(
        color: extras.glassSurface,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(28),
          side: BorderSide(color: extras.glassStroke),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(26),
          ),
          textStyle: GoogleFonts.manrope(
            fontSize: 15,
            fontWeight: FontWeight.w700,
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
          foregroundColor: onSurface,
          backgroundColor: extras.glassSurface.withValues(alpha: 0.72),
          side: BorderSide(color: extras.glassStroke),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(24),
          ),
        ),
      ),
      segmentedButtonTheme: SegmentedButtonThemeData(
        style: ButtonStyle(
          padding: const WidgetStatePropertyAll(
            EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          ),
          shape: WidgetStatePropertyAll(
            RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
          ),
          side: WidgetStatePropertyAll(
            BorderSide(color: scheme.outlineVariant),
          ),
          backgroundColor: WidgetStateProperty.resolveWith((states) {
            if (states.contains(WidgetState.selected)) {
              return scheme.primaryContainer;
            }
            return extras.glassSurface;
          }),
        ),
      ),
      chipTheme: ChipThemeData(
        labelStyle: GoogleFonts.manrope(
          fontWeight: FontWeight.w700,
          color: onSurface,
        ),
        backgroundColor: extras.glassSurface,
        side: BorderSide(color: extras.glassStroke),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(999)),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(
          backgroundColor: extras.glassSurface,
          foregroundColor: onSurface,
          padding: const EdgeInsets.all(12),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(20),
            side: BorderSide(color: extras.glassStroke),
          ),
        ),
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: scheme.surfaceContainerHigh,
        contentTextStyle: GoogleFonts.manrope(
          color: onSurface,
          fontWeight: FontWeight.w600,
        ),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(22),
          side: BorderSide(color: extras.glassStroke),
        ),
      ),
      extensions: [extras],
    );
  }

  static TextTheme _buildTextTheme(Color textColor) {
    final base = GoogleFonts.manropeTextTheme();

    return base.copyWith(
      displayLarge: GoogleFonts.spaceGrotesk(
        color: textColor,
        fontSize: 56,
        fontWeight: FontWeight.w700,
        height: 0.94,
        letterSpacing: 0,
      ),
      displayMedium: GoogleFonts.spaceGrotesk(
        color: textColor,
        fontSize: 42,
        fontWeight: FontWeight.w700,
        height: 0.98,
        letterSpacing: 0,
      ),
      headlineLarge: GoogleFonts.spaceGrotesk(
        color: textColor,
        fontSize: 30,
        fontWeight: FontWeight.w700,
        height: 1.04,
      ),
      headlineMedium: GoogleFonts.spaceGrotesk(
        color: textColor,
        fontSize: 24,
        fontWeight: FontWeight.w700,
      ),
      titleLarge: GoogleFonts.manrope(
        color: textColor,
        fontSize: 18,
        fontWeight: FontWeight.w800,
        height: 1.18,
      ),
      titleMedium: GoogleFonts.manrope(
        color: textColor,
        fontSize: 15,
        fontWeight: FontWeight.w700,
      ),
      bodyLarge: GoogleFonts.manrope(
        color: textColor,
        fontSize: 16,
        fontWeight: FontWeight.w500,
        height: 1.5,
      ),
      bodyMedium: GoogleFonts.manrope(
        color: textColor,
        fontSize: 14,
        fontWeight: FontWeight.w500,
        height: 1.45,
      ),
      bodySmall: GoogleFonts.manrope(
        color: textColor,
        fontSize: 12,
        fontWeight: FontWeight.w500,
        height: 1.45,
      ),
      labelLarge: GoogleFonts.manrope(
        color: textColor,
        fontSize: 13,
        fontWeight: FontWeight.w700,
        letterSpacing: 0.1,
      ),
      labelSmall: GoogleFonts.manrope(
        color: textColor,
        fontSize: 11,
        fontWeight: FontWeight.w700,
        letterSpacing: 0.12,
      ),
    );
  }

  static Color _blend(Color foreground, Color background, double opacity) {
    return Color.alphaBlend(foreground.withValues(alpha: opacity), background);
  }

  static Color _mix(Color a, Color b, double t) {
    return Color.lerp(a, b, t) ?? a;
  }

  static Color _foregroundFor(Color color) {
    return color.computeLuminance() >= 0.48
        ? const Color(0xFF191512)
        : const Color(0xFFF8F4ED);
  }
}
