import 'dart:ui';

import 'package:flutter/material.dart';

import '../../core/motion/app_motion.dart';
import '../../core/ui/design_tokens.dart';
import '../routing/app_destination.dart';
import '../theme/app_theme.dart';

class FloatingNavigationBar extends StatelessWidget {
  const FloatingNavigationBar({
    required this.destination,
    required this.onSelect,
    super.key,
  });

  final AppDestination destination;
  final ValueChanged<AppDestination> onSelect;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final extras = context.appThemeExtras;

    return ClipRRect(
      borderRadius: BorderRadius.circular(AppRadii.pill),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: extras.navigationSurface,
            borderRadius: BorderRadius.circular(AppRadii.pill),
            border: Border.all(color: extras.navigationStroke),
            boxShadow: [
              BoxShadow(
                color: theme.colorScheme.shadow.withValues(alpha: 0.18),
                blurRadius: 30,
                offset: const Offset(0, 16),
              ),
            ],
          ),
          child: Padding(
            padding: const EdgeInsets.all(6),
            child: SizedBox(
              width: 158,
              height: 50,
              child: LayoutBuilder(
                builder: (context, constraints) {
                  const pillWidth = 58.0;
                  final pillLeft = destination == AppDestination.home
                      ? 5.0
                      : constraints.maxWidth - pillWidth - 5;

                  return Stack(
                    children: [
                      AnimatedPositioned(
                        duration: AppMotionDurations.slow,
                        curve: AppMotionCurves.emphasized,
                        left: pillLeft,
                        top: 4,
                        width: pillWidth,
                        height: constraints.maxHeight - 8,
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            gradient: extras.navigationFocusGradient,
                            borderRadius: BorderRadius.circular(AppRadii.pill),
                            boxShadow: [
                              BoxShadow(
                                color: theme.colorScheme.primary.withValues(
                                  alpha: 0.22,
                                ),
                                blurRadius: 20,
                                offset: const Offset(0, 8),
                              ),
                            ],
                          ),
                        ),
                      ),
                      Row(
                        children: [
                          Expanded(
                            child: _NavItem(
                              icon: Icons.home_rounded,
                              semanticLabel: 'Главная',
                              selected: destination == AppDestination.home,
                              activeColor: extras.navigationFocusForeground,
                              inactiveColor: extras.navigationIcon,
                              splashColor: theme.colorScheme.primary.withValues(
                                alpha: 0.12,
                              ),
                              onTap: () => onSelect(AppDestination.home),
                            ),
                          ),
                          Expanded(
                            child: _NavItem(
                              icon: Icons.terminal_rounded,
                              semanticLabel: 'Логи',
                              selected: destination == AppDestination.logs,
                              activeColor: extras.navigationFocusForeground,
                              inactiveColor: extras.navigationIcon,
                              splashColor: theme.colorScheme.primary.withValues(
                                alpha: 0.12,
                              ),
                              onTap: () => onSelect(AppDestination.logs),
                            ),
                          ),
                        ],
                      ),
                    ],
                  );
                },
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _NavItem extends StatelessWidget {
  const _NavItem({
    required this.icon,
    required this.semanticLabel,
    required this.selected,
    required this.activeColor,
    required this.inactiveColor,
    required this.splashColor,
    required this.onTap,
  });

  final IconData icon;
  final String semanticLabel;
  final bool selected;
  final Color activeColor;
  final Color inactiveColor;
  final Color splashColor;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: semanticLabel,
      selected: selected,
      child: Material(
        color: Colors.transparent,
        child: InkResponse(
          onTap: onTap,
          radius: 30,
          splashColor: splashColor,
          highlightShape: BoxShape.circle,
          child: Center(
            child: AnimatedSlide(
              duration: AppMotionDurations.standard,
              curve: AppMotionCurves.decelerate,
              offset: Offset(0, selected ? -0.04 : 0.02),
              child: AnimatedScale(
                duration: AppMotionDurations.standard,
                curve: AppMotionCurves.decelerate,
                scale: selected ? 1.06 : 0.9,
                child: AnimatedOpacity(
                  duration: AppMotionDurations.fast,
                  opacity: selected ? 1 : 0.84,
                  child: Icon(
                    icon,
                    size: 24,
                    color: selected ? activeColor : inactiveColor,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
