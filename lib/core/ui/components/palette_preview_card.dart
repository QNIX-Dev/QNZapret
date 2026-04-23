import 'package:flutter/material.dart';

import '../../../app/theme/app_theme.dart';
import '../../motion/app_motion.dart';
import '../design_tokens.dart';

class PalettePreviewCard extends StatelessWidget {
  const PalettePreviewCard({
    required this.palette,
    required this.brightness,
    required this.selected,
    required this.onTap,
    super.key,
  });

  final AppPaletteSpec palette;
  final Brightness brightness;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final extras = context.appThemeExtras;
    final theme = Theme.of(context);

    return AnimatedContainer(
      duration: AppMotionDurations.standard,
      curve: AppMotionCurves.standard,
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(AppRadii.md),
        border: Border.all(
          color: selected ? theme.colorScheme.primary : extras.glassStroke,
          width: selected ? 1.6 : 1,
        ),
        boxShadow: selected
            ? AppElevations.floating(theme.colorScheme.primary)
            : AppElevations.card,
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(AppRadii.md),
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.sm),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: _PreviewPanel(
                    label: brightness == Brightness.dark ? 'Тёмная' : 'Светлая',
                    colors: palette.previewFor(brightness),
                  ),
                ),
                const SizedBox(height: AppSpacing.sm),
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        palette.label,
                        style: theme.textTheme.titleMedium,
                      ),
                    ),
                    AnimatedOpacity(
                      duration: AppMotionDurations.fast,
                      opacity: selected ? 1 : 0,
                      child: Icon(
                        Icons.check_circle_rounded,
                        color: theme.colorScheme.primary,
                        size: 20,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  palette.caption,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: extras.mutedForeground,
                  ),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _PreviewPanel extends StatelessWidget {
  const _PreviewPanel({required this.label, required this.colors});

  final String label;
  final List<Color> colors;

  @override
  Widget build(BuildContext context) {
    final contrastColor = _foregroundFor(colors[1]);

    return ClipRRect(
      borderRadius: BorderRadius.circular(20),
      child: Stack(
        fit: StackFit.expand,
        children: [
          DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: colors,
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(10),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                DecoratedBox(
                  decoration: BoxDecoration(
                    color: contrastColor.withValues(alpha: 0.16),
                    borderRadius: BorderRadius.circular(AppRadii.pill),
                    border: Border.all(
                      color: contrastColor.withValues(alpha: 0.18),
                    ),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 4,
                    ),
                    child: Text(
                      label,
                      style: Theme.of(
                        context,
                      ).textTheme.labelSmall?.copyWith(color: contrastColor),
                    ),
                  ),
                ),
                const Spacer(),
                Container(
                  height: 12,
                  decoration: BoxDecoration(
                    color: contrastColor.withValues(alpha: 0.66),
                    borderRadius: BorderRadius.circular(AppRadii.pill),
                  ),
                ),
                const SizedBox(height: 6),
                Container(
                  height: 10,
                  width: 52,
                  decoration: BoxDecoration(
                    color: contrastColor.withValues(alpha: 0.42),
                    borderRadius: BorderRadius.circular(AppRadii.pill),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Color _foregroundFor(Color color) {
    return color.computeLuminance() >= 0.45
        ? const Color(0xFF1A1613)
        : const Color(0xFFF8F4ED);
  }
}
