import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme/app_theme.dart';
import '../../../core/motion/app_motion.dart';
import '../../../core/state/runtime_controller.dart';
import '../../../core/state/runtime_view_models.dart';
import '../../../core/ui/components/staggered_reveal.dart';
import '../../../core/ui/components/terminal_illustration.dart';
import '../../../core/ui/components/terminal_surface.dart';
import '../../../core/ui/design_tokens.dart';

class LogsScreen extends ConsumerStatefulWidget {
  const LogsScreen({
    required this.onOpenSettings,
    required this.bottomInset,
    required this.visitToken,
    super.key,
  });

  final VoidCallback onOpenSettings;
  final double bottomInset;
  final int visitToken;

  @override
  ConsumerState<LogsScreen> createState() => _LogsScreenState();
}

class _LogsScreenState extends ConsumerState<LogsScreen> {
  final ScrollController _scrollController = ScrollController();
  int _seenLogCount = 0;

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final runtimeView = ref.watch(runtimeControllerProvider);
    _maybeAutoScroll(runtimeView);

    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= AppBreakpoints.compact;
        final mobile = AppBreakpoints.isMobile(constraints.maxWidth);
        final terminalHeight = mobile
            ? 360.0
            : constraints.maxHeight > 760
            ? constraints.maxHeight - (wide ? 300 : 340)
            : 540.0;

        return SingleChildScrollView(
          physics: const BouncingScrollPhysics(
            parent: AlwaysScrollableScrollPhysics(),
          ),
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: constraints.maxHeight),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _LogsHero(
                  runtimeView: runtimeView,
                  onOpenSettings: widget.onOpenSettings,
                  wide: wide,
                  mobile: mobile,
                ),
                const SizedBox(height: AppSpacing.lg),
                StaggeredReveal(
                  visitToken: widget.visitToken,
                  delay: const Duration(milliseconds: 40),
                  child: SizedBox(
                    height: terminalHeight,
                    child: TerminalSurface(
                      logs: runtimeView.logs,
                      scrollController: _scrollController,
                      autoScrollEnabled: runtimeView.autoScrollEnabled,
                      onClear: () {
                        ref
                            .read(runtimeControllerProvider.notifier)
                            .clearLogs();
                        _seenLogCount = 0;
                      },
                      onCopy: () async {
                        if (runtimeView.logs.isEmpty) {
                          _showSnackBar('Пока нечего копировать.');
                          return;
                        }

                        await Clipboard.setData(
                          ClipboardData(text: _composeLogDump(runtimeView)),
                        );
                        _showSnackBar('Логи скопированы.');
                      },
                      onToggleAutoScroll: () {
                        ref
                            .read(runtimeControllerProvider.notifier)
                            .setAutoScrollEnabled(
                              !runtimeView.autoScrollEnabled,
                            );
                      },
                    ),
                  ),
                ),
                SizedBox(height: widget.bottomInset),
              ],
            ),
          ),
        );
      },
    );
  }

  void _maybeAutoScroll(RuntimeViewState runtimeView) {
    if (_seenLogCount == runtimeView.logs.length) {
      return;
    }

    _seenLogCount = runtimeView.logs.length;
    if (!runtimeView.autoScrollEnabled) {
      return;
    }

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollController.hasClients) {
        return;
      }

      _scrollController.animateTo(
        _scrollController.position.maxScrollExtent,
        duration: AppMotionDurations.standard,
        curve: AppMotionCurves.decelerate,
      );
    });
  }

  String _composeLogDump(RuntimeViewState runtimeView) {
    final buffer = StringBuffer();
    for (final entry in runtimeView.logs) {
      final hh = entry.timestamp.hour.toString().padLeft(2, '0');
      final mm = entry.timestamp.minute.toString().padLeft(2, '0');
      final ss = entry.timestamp.second.toString().padLeft(2, '0');
      final source = entry.source?.shortTitle ?? 'Система';
      buffer.writeln('[$hh:$mm:$ss] [$source] ${entry.message}');
    }
    return buffer.toString().trimRight();
  }

  void _showSnackBar(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }
}

class _LogsHero extends StatelessWidget {
  const _LogsHero({
    required this.runtimeView,
    required this.onOpenSettings,
    required this.wide,
    required this.mobile,
  });

  final RuntimeViewState runtimeView;
  final VoidCallback onOpenSettings;
  final bool wide;
  final bool mobile;

  @override
  Widget build(BuildContext context) {
    final extras = context.appThemeExtras;

    return DecoratedBox(
      decoration: BoxDecoration(
        color: extras.glassSurface,
        borderRadius: BorderRadius.circular(mobile ? AppRadii.md : AppRadii.lg),
        border: Border.all(color: extras.glassStroke),
      ),
      child: Padding(
        padding: EdgeInsets.all(mobile ? AppSpacing.md : AppSpacing.lg),
        child: wide
            ? Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  Expanded(
                    flex: 6,
                    child: _LogsHeroDetails(
                      runtimeView: runtimeView,
                      onOpenSettings: onOpenSettings,
                    ),
                  ),
                  const SizedBox(width: AppSpacing.xl),
                  const Expanded(flex: 5, child: TerminalIllustration()),
                ],
              )
            : Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _LogsHeroHeader(onOpenSettings: onOpenSettings),
                  const SizedBox(height: AppSpacing.lg),
                  const TerminalIllustration(compact: true),
                  const SizedBox(height: AppSpacing.lg),
                  _LogFacts(runtimeView: runtimeView, centered: true),
                ],
              ),
      ),
    );
  }
}

class _LogsHeroDetails extends StatelessWidget {
  const _LogsHeroDetails({
    required this.runtimeView,
    required this.onOpenSettings,
  });

  final RuntimeViewState runtimeView;
  final VoidCallback onOpenSettings;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Align(
          alignment: Alignment.centerLeft,
          child: _SettingsButton(onOpenSettings: onOpenSettings),
        ),
        const SizedBox(height: AppSpacing.sm),
        const _LogsHeroTitle(),
        const SizedBox(height: AppSpacing.lg),
        _LogFacts(runtimeView: runtimeView, centered: false),
      ],
    );
  }
}

class _LogsHeroHeader extends StatelessWidget {
  const _LogsHeroHeader({required this.onOpenSettings});

  final VoidCallback onOpenSettings;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Expanded(child: _LogsHeroTitle()),
        const SizedBox(width: AppSpacing.md),
        _SettingsButton(onOpenSettings: onOpenSettings),
      ],
    );
  }
}

class _LogsHeroTitle extends StatelessWidget {
  const _LogsHeroTitle();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final extras = context.appThemeExtras;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Логи', style: theme.textTheme.displayMedium),
        const SizedBox(height: AppSpacing.xs),
        Text(
          'Здесь появляются последние сообщения сервисов.',
          style: theme.textTheme.bodyLarge?.copyWith(
            color: extras.mutedForeground,
          ),
        ),
      ],
    );
  }
}

class _LogFacts extends StatelessWidget {
  const _LogFacts({required this.runtimeView, required this.centered});

  final RuntimeViewState runtimeView;
  final bool centered;

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: centered ? Alignment.center : Alignment.centerLeft,
      child: Wrap(
        key: ValueKey(
          centered ? 'logs-hero-facts-centered' : 'logs-hero-facts-start',
        ),
        alignment: centered ? WrapAlignment.center : WrapAlignment.start,
        runAlignment: centered ? WrapAlignment.center : WrapAlignment.start,
        spacing: AppSpacing.sm,
        runSpacing: AppSpacing.sm,
        children: [
          _LogFact(title: '${runtimeView.logs.length}', subtitle: 'строк'),
          _LogFact(
            title: runtimeView.autoScrollEnabled ? 'Включена' : 'Пауза',
            subtitle: 'прокрутка',
          ),
          _LogFact(
            title: runtimeView.primaryStatusLabel,
            subtitle: 'состояние',
          ),
        ],
      ),
    );
  }
}

class _SettingsButton extends StatelessWidget {
  const _SettingsButton({required this.onOpenSettings});

  final VoidCallback onOpenSettings;

  @override
  Widget build(BuildContext context) {
    return IconButton(
      tooltip: 'Настройки',
      onPressed: () {
        HapticFeedback.selectionClick();
        onOpenSettings();
      },
      icon: const Icon(Icons.tune_rounded),
    );
  }
}

class _LogFact extends StatelessWidget {
  const _LogFact({required this.title, required this.subtitle});

  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return DecoratedBox(
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHigh.withValues(alpha: 0.74),
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: context.appThemeExtras.glassStroke),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.md,
          vertical: AppSpacing.sm,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: theme.textTheme.titleMedium),
            Text(
              subtitle,
              style: theme.textTheme.bodySmall?.copyWith(
                color: context.appThemeExtras.mutedForeground,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
