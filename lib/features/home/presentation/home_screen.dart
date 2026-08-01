import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/app_metadata.dart';
import '../../../core/backend/backend.dart';
import '../../../core/motion/app_scroll_behavior.dart';
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
          physics: appVerticalScrollPhysics,
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
                                      if (runtimeView.canStopCommand) {
                                        await controller.stopRuntime();
                                      } else {
                                        await controller.startRuntime();
                                      }
                                    },
                                  ),
                                ),
                                const SizedBox(width: AppSpacing.xxl),
                                Expanded(
                                  flex: 5,
                                  child: _HeroIllustration(
                                    snapshot: runtimeView.snapshot,
                                    framed: true,
                                  ),
                                ),
                              ],
                            ),
                          )
                        else ...[
                          _HeroIllustration(
                            snapshot: runtimeView.snapshot,
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
                              if (runtimeView.canStopCommand) {
                                await controller.stopRuntime();
                              } else {
                                await controller.startRuntime();
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
    final message = _statusMessage(runtimeView);
    final tone = _toneForMessage(context, runtimeView);
    final visibleItems = runtimeView.statusItems
        .take(3)
        .toList(growable: false);

    return Column(
      crossAxisAlignment: centered
          ? CrossAxisAlignment.center
          : CrossAxisAlignment.start,
      children: [
        for (var index = 0; index < visibleItems.length; index += 1) ...[
          Align(
            alignment: centered ? Alignment.center : Alignment.centerLeft,
            child: ConstrainedBox(
              constraints: BoxConstraints(maxWidth: centered ? 520 : 480),
              child: RuntimeStatusChip(
                statusItem: visibleItems[index],
                expanded: false,
              ),
            ),
          ),
          if (index != visibleItems.length - 1)
            const SizedBox(height: AppSpacing.sm),
        ],
        SizedBox(height: centered ? AppSpacing.lg : AppSpacing.xl),
        ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: PremiumCtaButton(
            runtimeView: runtimeView,
            onPressed: runtimeView.canStartCommand || runtimeView.canStopCommand
                ? onPrimaryAction
                : null,
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
    final failure = runtimeView.latestFailure;
    final snapshot = runtimeView.snapshot;

    if (failure != null) {
      return failure.message;
    }

    if (snapshot.hasPartialFailure) {
      return snapshot.partialFailureMessage ??
          'Основной сервис активен, но часть компонентов недоступна.';
    }

    if (snapshot.isOperational) {
      return 'Перехват трафика готов.';
    }

    if (snapshot.state == ProxyRuntimeState.running &&
        snapshot.serviceActive &&
        !snapshot.hasVerifiedInterception) {
      return 'Системный сервис активен, но перехват еще не готов.';
    }

    if (snapshot.strategyEngineReady) {
      return 'Ядро обхода готово; готовность передачи и перехвата показана отдельно.';
    }

    return switch (snapshot.state) {
      ProxyRuntimeState.idle => null,
      ProxyRuntimeState.starting => 'Запускаем сервисы.',
      ProxyRuntimeState.running => 'Запуск сервиса не завершен.',
      ProxyRuntimeState.stopping => 'Останавливаем сервисы.',
      ProxyRuntimeState.failed => 'Сервис сообщил сбой.',
    };
  }

  Color _toneForMessage(BuildContext context, RuntimeViewState runtimeView) {
    final extras = context.appThemeExtras;

    if (runtimeView.latestFailure != null || runtimeView.snapshot.hasFailure) {
      return extras.danger;
    }

    return switch (runtimeView.snapshot.statusTone) {
      RuntimeStatusTone.neutral => Theme.of(context).colorScheme.primary,
      RuntimeStatusTone.info => Theme.of(context).colorScheme.secondary,
      RuntimeStatusTone.success => extras.success,
      RuntimeStatusTone.warning => extras.warning,
      RuntimeStatusTone.danger => extras.danger,
    };
  }
}

class _HeroIllustration extends StatelessWidget {
  const _HeroIllustration({required this.snapshot, required this.framed});

  final ProxyRuntimeSnapshot snapshot;
  final bool framed;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    final illustration = Padding(
      padding: EdgeInsets.all(framed ? AppSpacing.xl : AppSpacing.sm),
      child: ConnectedFlowIllustration(snapshot: snapshot),
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
