import 'package:flutter/material.dart';

import '../../../app/theme/app_theme.dart';
import '../design_tokens.dart';

class SettingsSectionCard extends StatelessWidget {
  const SettingsSectionCard({
    required this.title,
    required this.child,
    super.key,
    this.description,
  });

  final String title;
  final String? description;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final extras = context.appThemeExtras;
    final theme = Theme.of(context);

    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: extras.glassSurface,
        borderRadius: BorderRadius.circular(AppRadii.md),
        border: Border.all(color: extras.glassStroke),
        boxShadow: AppElevations.card,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: theme.textTheme.titleLarge),
          if (description case final description?) ...[
            const SizedBox(height: AppSpacing.xs),
            Text(
              description,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: extras.mutedForeground,
              ),
            ),
          ],
          const SizedBox(height: AppSpacing.md),
          child,
        ],
      ),
    );
  }
}
