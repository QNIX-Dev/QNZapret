import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/motion/app_motion.dart';
import '../../core/state/app_settings_controller.dart';
import '../../core/ui/app_backdrop.dart';
import '../../core/ui/design_tokens.dart';
import '../../features/home/presentation/home_screen.dart';
import '../../features/logs/presentation/logs_screen.dart';
import '../../features/settings/presentation/settings_screen.dart';
import '../routing/app_destination.dart';
import 'floating_navigation_bar.dart';

class AppShell extends ConsumerStatefulWidget {
  const AppShell({super.key});

  @override
  ConsumerState<AppShell> createState() => _AppShellState();
}

class _AppShellState extends ConsumerState<AppShell> {
  late final PageController _pageController;
  late AppDestination _activeDestination;
  late final Map<AppDestination, int> _visitTokens;
  AppDestination? _pendingDestination;

  @override
  void initState() {
    super.initState();
    final initialDestination = ref
        .read(appSettingsControllerProvider)
        .destination;
    _activeDestination = initialDestination;
    _pageController = PageController(initialPage: initialDestination.index);
    _visitTokens = {
      for (final destination in AppDestination.values)
        destination: destination == initialDestination ? 1 : 0,
    };
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final settings = ref.watch(appSettingsControllerProvider);
    _syncDestination(settings.destination);

    return Scaffold(
      extendBody: true,
      backgroundColor: Colors.transparent,
      body: AppBackdrop(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final safePadding = MediaQuery.paddingOf(context);
            final pagePadding = AppBreakpoints.pagePadding(
              constraints.maxWidth,
            );
            final navBottom = safePadding.bottom + 14;
            final navClearance = safePadding.bottom + 102;

            return Stack(
              children: [
                Padding(
                  padding: EdgeInsets.fromLTRB(
                    safePadding.left + pagePadding,
                    safePadding.top + pagePadding,
                    safePadding.right + pagePadding,
                    0,
                  ),
                  child: Align(
                    alignment: Alignment.topCenter,
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(
                        maxWidth: AppBreakpoints.contentMaxWidth,
                      ),
                      child: ClipRect(
                        child: PageView(
                          controller: _pageController,
                          physics: const PageScrollPhysics(),
                          onPageChanged: _handlePageChanged,
                          children: [
                            _SharedAxisPage(
                              controller: _pageController,
                              index: AppDestination.home.index,
                              child: HomeScreen(
                                onOpenSettings: () => _openSettings(context),
                                bottomInset: navClearance,
                              ),
                            ),
                            _SharedAxisPage(
                              controller: _pageController,
                              index: AppDestination.logs.index,
                              child: LogsScreen(
                                onOpenSettings: () => _openSettings(context),
                                bottomInset: navClearance,
                                visitToken:
                                    _visitTokens[AppDestination.logs] ?? 0,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: navBottom,
                  child: Center(
                    child: FloatingNavigationBar(
                      destination: settings.destination,
                      onSelect: (destination) {
                        if (destination != settings.destination) {
                          HapticFeedback.selectionClick();
                        }
                        ref
                            .read(appSettingsControllerProvider.notifier)
                            .setDestination(destination);
                      },
                    ),
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }

  void _syncDestination(AppDestination destination) {
    if (destination == _activeDestination ||
        destination == _pendingDestination) {
      return;
    }

    _pendingDestination = destination;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      final target = _pendingDestination;
      _pendingDestination = null;
      if (!mounted || target == null || target == _activeDestination) {
        return;
      }

      setState(() {
        _activeDestination = target;
        _visitTokens[target] = (_visitTokens[target] ?? 0) + 1;
      });

      if (!_pageController.hasClients) {
        return;
      }

      await _pageController.animateToPage(
        target.index,
        duration: AppMotionDurations.slow,
        curve: AppMotionCurves.decelerate,
      );
    });
  }

  void _handlePageChanged(int index) {
    final destination = AppDestinationX.fromIndex(index);
    if (destination == _activeDestination) {
      return;
    }

    HapticFeedback.selectionClick();
    setState(() {
      _activeDestination = destination;
      _visitTokens[destination] = (_visitTokens[destination] ?? 0) + 1;
    });

    ref
        .read(appSettingsControllerProvider.notifier)
        .setDestination(destination);
  }

  Future<void> _openSettings(BuildContext context) {
    final width = MediaQuery.sizeOf(context).width;
    final route = AppBreakpoints.useSettingsPage(width)
        ? MaterialPageRoute<void>(
            builder: (context) => const SettingsScreen(
              presentation: SettingsScreenPresentation.page,
            ),
            fullscreenDialog: false,
          )
        : _settingsDialogRoute();

    return Navigator.of(context).push(route);
  }

  Route<void> _settingsDialogRoute() {
    return PageRouteBuilder<void>(
      opaque: false,
      barrierDismissible: true,
      barrierColor: Colors.black.withValues(alpha: 0.36),
      pageBuilder: (context, animation, secondaryAnimation) {
        return const SettingsScreen(
          presentation: SettingsScreenPresentation.dialog,
        );
      },
      transitionsBuilder: (context, animation, secondaryAnimation, child) {
        final curved = CurvedAnimation(
          parent: animation,
          curve: AppMotionCurves.decelerate,
          reverseCurve: AppMotionCurves.accelerate,
        );

        return FadeTransition(
          opacity: curved,
          child: ScaleTransition(
            scale: Tween<double>(begin: 0.98, end: 1).animate(curved),
            child: SlideTransition(
              position: Tween<Offset>(
                begin: const Offset(0, 0.03),
                end: Offset.zero,
              ).animate(curved),
              child: child,
            ),
          ),
        );
      },
    );
  }
}

class _SharedAxisPage extends StatelessWidget {
  const _SharedAxisPage({
    required this.controller,
    required this.index,
    required this.child,
  });

  final PageController controller;
  final int index;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      child: child,
      builder: (context, child) {
        var page = index.toDouble();
        if (controller.hasClients && controller.position.hasContentDimensions) {
          page = controller.page ?? page;
        }

        final distance = (page - index).clamp(-1.0, 1.0);
        final t = distance.abs();
        final opacity = (1 - t * 0.12).clamp(0.0, 1.0);
        final scale = 1 - t * 0.012;

        return Opacity(
          opacity: opacity,
          child: Transform.scale(
            scale: scale,
            alignment: Alignment.center,
            child: child,
          ),
        );
      },
    );
  }
}
