import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/app_metadata.dart';
import '../../../core/state/runtime_controller.dart';
import '../../../core/state/runtime_view_models.dart';
import '../../../core/ui/components/connected_flow_illustration.dart';
import '../../../core/ui/components/premium_cta_button.dart';
import '../../../core/ui/components/status_chip.dart';
import '../../../core/ui/design_tokens.dart';
import '../../../app/theme/app_theme.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({
    required this.onOpenSettings,
    required this.bottomInset,
    super.key,
  });

  final VoidCallback onOpenSettings;
  final double bottomInset;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final runtimeView = ref.watch(runtimeControllerProvider);
    final runtimeState = runtimeView.runtime;
    final controller = ref.read(runtimeControllerProvider.notifier);

    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= AppBreakpoints.compact;
        final mobile = AppBreakpoints.isMobile(constraints.maxWidth);
        final horizontalPadding = mobile
            ? AppSpacing.sm
            : AppBreakpoints.isExpanded(constraints.maxWidth)
            ? AppSpacing.xxl
            : AppSpacing.xl;
        final verticalPadding = mobile ? AppSpacing.lg : AppSpacing.xxl;
        final heroRadius = mobile ? AppRadii.lg : AppRadii.xl;
        final heroMinHeight = constraints.maxHeight > bottomInset
            ? constraints.maxHeight - bottomInset
            : 0.0;

        return SingleChildScrollView(
          physics: const BouncingScrollPhysics(
            parent: AlwaysScrollableScrollPhysics(),
          ),
          child: Column(
            children: [
              ConstrainedBox(
                constraints: BoxConstraints(minHeight: heroMinHeight),
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: context.appThemeExtras.glassSurface,
                    borderRadius: BorderRadius.circular(heroRadius),
                    border: Border.all(
                      color: context.appThemeExtras.glassStroke,
                    ),
                    boxShadow: mobile ? const [] : AppElevations.card,
                  ),
                  child: Padding(
                    padding: EdgeInsets.fromLTRB(
                      horizontalPadding,
                      verticalPadding,
                      horizontalPadding,
                      verticalPadding,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        _HeroTopBar(onOpenSettings: onOpenSettings),
                        SizedBox(
                          height: mobile ? AppSpacing.lg : AppSpacing.xl,
                        ),
                        if (wide)
                          ConstrainedBox(
                            constraints: const BoxConstraints(minHeight: 430),
                            child: Row(
                              crossAxisAlignment: CrossAxisAlignment.center,
                              children: [
                                Expanded(
                                  flex: 6,
                                  child: _HeroDetails(
                                    runtimeView: runtimeView,
                                    centered: false,
                                    onPrimaryAction: () async {
                                      HapticFeedback.lightImpact();
                                      if (runtimeState.isFullyRunning) {
                                        await controller.stopAllServices();
                                      } else {
                                        await controller.startAllServices();
                                      }
                                    },
                                  ),
                                ),
                                const SizedBox(width: AppSpacing.xxl),
                                Expanded(
                                  flex: 5,
                                  child: _HeroIllustration(
                                    runtimeState: runtimeState,
                                    framed: true,
                                  ),
                                ),
                              ],
                            ),
                          )
                        else ...[
                          _HeroIllustration(
                            runtimeState: runtimeState,
                            framed: false,
                          ),
                          SizedBox(
                            height: mobile ? AppSpacing.lg : AppSpacing.xl,
                          ),
                          _HeroDetails(
                            runtimeView: runtimeView,
                            centered: true,
                            onPrimaryAction: () async {
                              HapticFeedback.lightImpact();
                              if (runtimeState.isFullyRunning) {
                                await controller.stopAllServices();
                              } else {
                                await controller.startAllServices();
                              }
                            },
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
              SizedBox(height: bottomInset),
            ],
          ),
        );
      },
    );
  }
}

class _HeroTopBar extends StatelessWidget {
  const _HeroTopBar({required this.onOpenSettings});

  final VoidCallback onOpenSettings;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(appDisplayName, style: theme.textTheme.displayMedium),
              const SizedBox(height: AppSpacing.xs),
              Text(
                'Запуск и остановка сервисов в одном месте.',
                style: theme.textTheme.bodyLarge?.copyWith(
                  color: context.appThemeExtras.mutedForeground,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: AppSpacing.md),
        IconButton(
          tooltip: 'Настройки',
          onPressed: () {
            HapticFeedback.selectionClick();
            onOpenSettings();
          },
          icon: const Icon(Icons.tune_rounded),
        ),
      ],
    );
  }
}

class _HeroDetails extends StatelessWidget {
  const _HeroDetails({
    required this.runtimeView,
    required this.centered,
    required this.onPrimaryAction,
  });

  final RuntimeViewState runtimeView;
  final bool centered;
  final Future<void> Function() onPrimaryAction;

  @override
  Widget build(BuildContext context) {
    final runtimeState = runtimeView.runtime;
    final message = _statusMessage(runtimeView);
    final tone = _toneForMessage(context, runtimeView);

    return Column(
      crossAxisAlignment: centered
          ? CrossAxisAlignment.center
          : CrossAxisAlignment.start,
      children: [
        Align(
          alignment: centered ? Alignment.center : Alignment.centerLeft,
          child: ConstrainedBox(
            constraints: BoxConstraints(maxWidth: centered ? 520 : 480),
            child: RuntimeStatusChip(
              serviceState: runtimeState.stateFor(BypassServiceType.nfqws),
              expanded: false,
            ),
          ),
        ),
        const SizedBox(height: AppSpacing.sm),
        Align(
          alignment: centered ? Alignment.center : Alignment.centerLeft,
          child: ConstrainedBox(
            constraints: BoxConstraints(maxWidth: centered ? 520 : 480),
            child: RuntimeStatusChip(
              serviceState: runtimeState.stateFor(
                BypassServiceType.telegramProxy,
              ),
              expanded: false,
            ),
          ),
        ),
        SizedBox(height: centered ? AppSpacing.lg : AppSpacing.xl),
        ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: PremiumCtaButton(
            runtimeState: runtimeState,
            onPressed: runtimeState.hasPartialFailure ? null : onPrimaryAction,
          ),
        ),
        if (message case final message?) ...[
          const SizedBox(height: AppSpacing.md),
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 200),
            child: Container(
              key: ValueKey(message),
              padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.md,
                vertical: AppSpacing.sm,
              ),
              decoration: BoxDecoration(
                color: tone.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(22),
                border: Border.all(color: tone.withValues(alpha: 0.18)),
              ),
              child: Text(
                message,
                style: Theme.of(
                  context,
                ).textTheme.bodyMedium?.copyWith(color: tone),
              ),
            ),
          ),
        ],
      ],
    );
  }

  String? _statusMessage(RuntimeViewState runtimeView) {
    final runtimeState = runtimeView.runtime;
    final failure = runtimeView.latestFailure;

    if (runtimeState.hasPartialFailure) {
      return 'Не все сервисы удалось запустить. Возвращаем состояние назад.';
    }

    if (failure != null &&
        runtimeState.summaryStatus == ServiceRuntimeStatus.failed) {
      return failure.message;
    }

    return switch (runtimeState.summaryStatus) {
      ServiceRuntimeStatus.idle => null,
      ServiceRuntimeStatus.starting => 'Запускаем сервисы по очереди.',
      ServiceRuntimeStatus.running => 'Оба сервиса работают.',
      ServiceRuntimeStatus.stopping => 'Останавливаем сервисы.',
      ServiceRuntimeStatus.failed => 'Не удалось завершить запуск.',
    };
  }

  Color _toneForMessage(BuildContext context, RuntimeViewState runtimeView) {
    final extras = context.appThemeExtras;

    if (runtimeView.runtime.hasPartialFailure ||
        runtimeView.latestFailure != null) {
      return extras.danger;
    }

    return switch (runtimeView.runtime.summaryStatus) {
      ServiceRuntimeStatus.idle => Theme.of(context).colorScheme.primary,
      ServiceRuntimeStatus.starting => Theme.of(context).colorScheme.secondary,
      ServiceRuntimeStatus.running => extras.success,
      ServiceRuntimeStatus.stopping => extras.warning,
      ServiceRuntimeStatus.failed => extras.danger,
    };
  }
}

class _HeroIllustration extends StatelessWidget {
  const _HeroIllustration({required this.runtimeState, required this.framed});

  final CombinedRuntimeState runtimeState;
  final bool framed;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    final illustration = Padding(
      padding: EdgeInsets.all(framed ? AppSpacing.xl : AppSpacing.sm),
      child: ConnectedFlowIllustration(runtimeState: runtimeState),
    );

    if (!framed) {
      return illustration;
    }

    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            theme.colorScheme.surfaceContainerHigh.withValues(alpha: 0.76),
            context.appThemeExtras.glassSurface.withValues(alpha: 0.74),
          ],
        ),
        borderRadius: BorderRadius.circular(AppRadii.lg),
        border: Border.all(color: context.appThemeExtras.glassStroke),
      ),
      child: illustration,
    );
  }
}
