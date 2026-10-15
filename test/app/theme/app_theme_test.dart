import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:qnzapret/app/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  GoogleFonts.config.allowRuntimeFetching = false;

  test('main glass surface stays translucent and readable in every theme', () {
    for (final palette in AppTheme.palettes) {
      for (final brightness in Brightness.values) {
        final theme = AppTheme.createTheme(
          paletteId: palette.id,
          brightness: brightness,
        );
        final extras = theme.extension<AppThemeExtras>()!;
        final composite = Color.alphaBlend(
          extras.mainGlassSurface,
          theme.colorScheme.surface,
        );
        final contrast = _contrastRatio(theme.colorScheme.onSurface, composite);

        expect(
          extras.mainGlassSurface.a,
          inInclusiveRange(0.8, 0.92),
          reason: '${palette.id} $brightness must remain subtly translucent',
        );
        expect(
          contrast,
          greaterThan(7),
          reason: '${palette.id} $brightness must keep body text readable',
        );
      }
    }
  });
}

double _contrastRatio(Color first, Color second) {
  final firstLuminance = first.computeLuminance();
  final secondLuminance = second.computeLuminance();
  final lighter = firstLuminance > secondLuminance
      ? firstLuminance
      : secondLuminance;
  final darker = firstLuminance > secondLuminance
      ? secondLuminance
      : firstLuminance;
  return (lighter + 0.05) / (darker + 0.05);
}
