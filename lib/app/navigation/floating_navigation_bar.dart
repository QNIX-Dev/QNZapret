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
        filter: ImageFilter.blur(sigmaX: 22, sigmaY: 22),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: extras.navigationSurface,
            borderRadius: BorderRadius.circular(AppRadii.pill),
            border: Border.all(color: extras.navigationStroke),
            boxShadow: [
              BoxShadow(
                color: theme.colorScheme.shadow.withValues(alpha: 0.16),
                blurRadius: 34,
                offset: const Offset(0, 16),
              ),
              BoxShadow(
                color: theme.colorScheme.primary.withValues(alpha: 0.12),
                blurRadius: 22,
                offset: const Offset(0, 6),
              ),
            ],
          ),
          child: Padding(
            padding: const EdgeInsets.all(6),
            child: SizedBox(
              width: 150,
              height: 50,
              child: LayoutBuilder(
                builder: (context, constraints) {
                  const pillWidth = 54.0;
                  const pillHeight = 40.0;
                  final itemWidth =
                      constraints.maxWidth / AppDestination.values.length;
                  final selectedIndex = destination.index;
                  final pillLeft =
                      itemWidth * selectedIndex + (itemWidth - pillWidth) / 2;
                  final pillTop = (constraints.maxHeight - pillHeight) / 2;

                  return Stack(
                    children: [
                      AnimatedPositioned(
                        duration: AppMotionDurations.page,
                        curve: AppMotionCurves.spring,
                        left: pillLeft,
                        top: pillTop,
                        width: pillWidth,
                        height: pillHeight,
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            gradient: extras.navigationFocusGradient,
                            borderRadius: BorderRadius.circular(AppRadii.pill),
                            boxShadow: [
                              BoxShadow(
                                color: theme.colorScheme.primary.withValues(
                                  alpha: 0.24,
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

class _NavItem extends StatefulWidget {
  const _NavItem({
    required this.icon,
    required this.semanticLabel,
    required this.selected,
    required this.activeColor,
    required this.inactiveColor,
    required this.onTap,
  });

  final IconData icon;
  final String semanticLabel;
  final bool selected;
  final Color activeColor;
  final Color inactiveColor;
  final VoidCallback onTap;

  @override
  State<_NavItem> createState() => _NavItemState();
}

class _NavItemState extends State<_NavItem> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    final selected = widget.selected;
    final scale = selected ? (_pressed ? 0.98 : 1.08) : (_pressed ? 0.9 : 0.94);
    final opacity = selected ? 1.0 : (_pressed ? 0.58 : 0.76);

    return Semantics(
      button: true,
      label: widget.semanticLabel,
      selected: selected,
      child: Tooltip(
        message: widget.semanticLabel,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTapDown: (_) => setState(() => _pressed = true),
          onTapCancel: () => setState(() => _pressed = false),
          onTapUp: (_) => setState(() => _pressed = false),
          onTap: widget.onTap,
          child: Center(
            child: AnimatedSlide(
              duration: AppMotionDurations.standard,
              curve: AppMotionCurves.decelerate,
              offset: Offset(0, selected ? -0.05 : 0.02),
              child: AnimatedScale(
                duration: AppMotionDurations.standard,
                curve: AppMotionCurves.spring,
                scale: scale,
                child: AnimatedOpacity(
                  duration: AppMotionDurations.fast,
                  opacity: opacity,
                  child: Icon(
                    widget.icon,
                    size: 24,
                    color: selected ? widget.activeColor : widget.inactiveColor,
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
