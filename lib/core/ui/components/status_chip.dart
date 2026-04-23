import 'package:flutter/material.dart';

import '../../../app/theme/app_theme.dart';
import '../../backend/runtime_models.dart';
import '../../motion/app_motion.dart';
import '../design_tokens.dart';

class RuntimeStatusChip extends StatelessWidget {
  const RuntimeStatusChip({
    required this.serviceState,
    super.key,
    this.expanded = false,
  });

  final ServiceRuntimeState serviceState;
  final bool expanded;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final extras = context.appThemeExtras;
    final tone = _toneForState(theme, extras);
    final icon = switch (serviceState.type) {
      BypassServiceType.nfqws => Icons.shield_rounded,
      BypassServiceType.telegramProxy => Icons.send_rounded,
    };

    final chip = AnimatedContainer(
      duration: AppMotionDurations.standard,
      curve: AppMotionCurves.standard,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHigh.withValues(alpha: 0.82),
        borderRadius: BorderRadius.circular(AppRadii.pill),
        border: Border.all(color: tone.withValues(alpha: 0.22)),
      ),
      child: Row(
        mainAxisSize: expanded ? MainAxisSize.max : MainAxisSize.min,
        children: [
          AnimatedContainer(
            duration: AppMotionDurations.fast,
            curve: AppMotionCurves.standard,
            width: 32,
            height: 32,
            decoration: BoxDecoration(
              color: tone.withValues(alpha: 0.16),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Icon(icon, size: 18, color: tone),
          ),
          const SizedBox(width: AppSpacing.sm),
          if (expanded)
            Expanded(
              child: Text(
                serviceState.type.title,
                style: theme.textTheme.titleMedium,
                overflow: TextOverflow.ellipsis,
              ),
            )
          else
            Flexible(
              child: Text(
                serviceState.type.title,
                style: theme.textTheme.titleMedium,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          const SizedBox(width: AppSpacing.md),
          AnimatedContainer(
            duration: AppMotionDurations.fast,
            curve: AppMotionCurves.standard,
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
            decoration: BoxDecoration(
              color: tone.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(AppRadii.pill),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                _StatusDot(
                  color: tone,
                  animated: serviceState.status.isBusy || serviceState.isActive,
                ),
                const SizedBox(width: 8),
                Text(
                  serviceState.status.label,
                  style: theme.textTheme.labelLarge?.copyWith(color: tone),
                ),
              ],
            ),
          ),
        ],
      ),
    );

    return expanded ? SizedBox(width: double.infinity, child: chip) : chip;
  }

  Color _toneForState(ThemeData theme, AppThemeExtras extras) {
    return switch (serviceState.status) {
      ServiceRuntimeStatus.idle => theme.colorScheme.primary,
      ServiceRuntimeStatus.starting => theme.colorScheme.secondary,
      ServiceRuntimeStatus.running => extras.success,
      ServiceRuntimeStatus.stopping => extras.warning,
      ServiceRuntimeStatus.failed => extras.danger,
    };
  }
}

class _StatusDot extends StatefulWidget {
  const _StatusDot({required this.color, required this.animated});

  final Color color;
  final bool animated;

  @override
  State<_StatusDot> createState() => _StatusDotState();
}

class _StatusDotState extends State<_StatusDot>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1200),
  )..repeat(reverse: true);

  @override
  void didUpdateWidget(covariant _StatusDot oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.animated && !_controller.isAnimating) {
      _controller.repeat(reverse: true);
    } else if (!widget.animated && _controller.isAnimating) {
      _controller.stop();
      _controller.value = 1;
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FadeTransition(
      opacity: widget.animated
          ? Tween<double>(begin: 0.5, end: 1).animate(
              CurvedAnimation(parent: _controller, curve: Curves.easeInOut),
            )
          : const AlwaysStoppedAnimation(1),
      child: ScaleTransition(
        scale: widget.animated
            ? Tween<double>(begin: 0.86, end: 1.08).animate(
                CurvedAnimation(parent: _controller, curve: Curves.easeInOut),
              )
            : const AlwaysStoppedAnimation(1),
        child: Container(
          width: 9,
          height: 9,
          decoration: BoxDecoration(
            color: widget.color,
            shape: BoxShape.circle,
          ),
        ),
      ),
    );
  }
}
