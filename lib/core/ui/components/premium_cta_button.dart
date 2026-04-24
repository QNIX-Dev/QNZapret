import 'package:flutter/material.dart';

import '../../../app/theme/app_theme.dart';
import '../../backend/backend.dart';
import '../../motion/app_motion.dart';
import '../../state/runtime_controller.dart';
import '../design_tokens.dart';

class PremiumCtaButton extends StatelessWidget {
  const PremiumCtaButton({
    required this.runtimeView,
    required this.onPressed,
    super.key,
  });

  final RuntimeViewState runtimeView;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final extras = context.appThemeExtras;
    final viewModel = _resolveViewModel(theme, extras);

    return AnimatedContainer(
      duration: AppMotionDurations.standard,
      curve: AppMotionCurves.standard,
      decoration: BoxDecoration(
        gradient: viewModel.gradient,
        borderRadius: BorderRadius.circular(30),
        boxShadow: AppElevations.floating(viewModel.shadowColor),
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: viewModel.enabled ? onPressed : null,
          borderRadius: BorderRadius.circular(30),
          child: AnimatedOpacity(
            duration: AppMotionDurations.fast,
            opacity: viewModel.enabled ? 1 : 0.92,
            child: Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.lg,
                vertical: AppSpacing.md,
              ),
              child: Row(
                children: [
                  AnimatedSwitcher(
                    duration: AppMotionDurations.fast,
                    child: viewModel.loading
                        ? SizedBox(
                            key: const ValueKey('spinner'),
                            width: 22,
                            height: 22,
                            child: CircularProgressIndicator(
                              strokeWidth: 2.4,
                              color: viewModel.foreground,
                            ),
                          )
                        : Icon(
                            viewModel.icon,
                            key: ValueKey(viewModel.icon),
                            color: viewModel.foreground,
                            size: 22,
                          ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: AnimatedSwitcher(
                      duration: AppMotionDurations.fast,
                      child: Text(
                        viewModel.title,
                        key: ValueKey(viewModel.title),
                        style: theme.textTheme.titleLarge?.copyWith(
                          color: viewModel.foreground,
                        ),
                      ),
                    ),
                  ),
                  AnimatedOpacity(
                    duration: AppMotionDurations.fast,
                    opacity: viewModel.loading ? 0 : 1,
                    child: Icon(
                      Icons.arrow_forward_rounded,
                      size: 20,
                      color: viewModel.foreground.withValues(alpha: 0.84),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  _CtaViewModel _resolveViewModel(ThemeData theme, AppThemeExtras extras) {
    final snapshot = runtimeView.snapshot;
    if (runtimeView.latestFailure != null) {
      return _CtaViewModel(
        title: 'Повторить команду',
        icon: Icons.refresh_rounded,
        foreground: theme.colorScheme.onError,
        loading: false,
        enabled: onPressed != null,
        shadowColor: extras.danger,
        gradient: LinearGradient(colors: [extras.danger, extras.warning]),
      );
    }

    if (runtimeView.isBusy) {
      final stopping = snapshot.state == ProxyRuntimeState.stopping;
      return _CtaViewModel(
        title: stopping ? 'Останавливаем...' : 'Выполняем команду...',
        icon: stopping
            ? Icons.stop_circle_rounded
            : Icons.rocket_launch_rounded,
        foreground: theme.colorScheme.onPrimary,
        loading: true,
        enabled: false,
        shadowColor: theme.colorScheme.secondary,
        gradient: LinearGradient(
          colors: [theme.colorScheme.primary, theme.colorScheme.secondary],
        ),
      );
    }

    switch (snapshot.state) {
      case ProxyRuntimeState.idle:
        return _CtaViewModel(
          title: runtimeView.needsPrepare
              ? 'Разрешить запуск'
              : 'Запустить сервисы',
          icon: runtimeView.needsPrepare
              ? Icons.verified_user_rounded
              : Icons.rocket_launch_rounded,
          foreground: theme.colorScheme.onPrimary,
          loading: false,
          enabled: onPressed != null,
          shadowColor: theme.colorScheme.primary,
          gradient: LinearGradient(
            colors: [
              theme.colorScheme.primary,
              theme.colorScheme.secondary,
              theme.colorScheme.tertiary,
            ],
          ),
        );
      case ProxyRuntimeState.starting:
        return _CtaViewModel(
          title: 'Запуск...',
          icon: Icons.rocket_launch_rounded,
          foreground: theme.colorScheme.onPrimary,
          loading: true,
          enabled: false,
          shadowColor: theme.colorScheme.secondary,
          gradient: LinearGradient(
            colors: [theme.colorScheme.primary, theme.colorScheme.secondary],
          ),
        );
      case ProxyRuntimeState.running:
        return _CtaViewModel(
          title: 'Остановить сервисы',
          icon: Icons.stop_circle_rounded,
          foreground: theme.colorScheme.onError,
          loading: false,
          enabled: onPressed != null,
          shadowColor: extras.warning,
          gradient: LinearGradient(colors: [extras.warning, extras.danger]),
        );
      case ProxyRuntimeState.stopping:
        return _CtaViewModel(
          title: 'Остановка...',
          icon: Icons.stop_circle_rounded,
          foreground: theme.colorScheme.onError,
          loading: true,
          enabled: false,
          shadowColor: extras.warning,
          gradient: LinearGradient(
            colors: [extras.warning, theme.colorScheme.tertiary],
          ),
        );
      case ProxyRuntimeState.failed:
        return _CtaViewModel(
          title: 'Повторить запуск runtime',
          icon: Icons.refresh_rounded,
          foreground: theme.colorScheme.onError,
          loading: false,
          enabled: onPressed != null,
          shadowColor: extras.danger,
          gradient: LinearGradient(
            colors: [extras.danger, theme.colorScheme.secondary],
          ),
        );
    }
  }
}

class _CtaViewModel {
  const _CtaViewModel({
    required this.title,
    required this.icon,
    required this.foreground,
    required this.loading,
    required this.enabled,
    required this.shadowColor,
    required this.gradient,
  });

  final String title;
  final IconData icon;
  final Color foreground;
  final bool loading;
  final bool enabled;
  final Color shadowColor;
  final Gradient gradient;
}
