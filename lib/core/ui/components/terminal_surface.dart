import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

import '../../../app/theme/app_theme.dart';
import '../../motion/app_motion.dart';
import '../../motion/app_scroll_behavior.dart';
import '../../motion/app_scroll_motion.dart';
import '../../motion/app_smooth_scroll.dart';
import '../../state/runtime_view_models.dart';
import '../design_tokens.dart';

class TerminalSurface extends StatelessWidget {
  const TerminalSurface({
    required this.logs,
    required this.scrollController,
    required this.autoScrollEnabled,
    required this.onClear,
    required this.onCopy,
    required this.onToggleAutoScroll,
    super.key,
  });

  final List<RuntimeLogEntry> logs;
  final AppScrollController scrollController;
  final bool autoScrollEnabled;
  final VoidCallback onClear;
  final VoidCallback onCopy;
  final VoidCallback onToggleAutoScroll;

  @override
  Widget build(BuildContext context) {
    scrollController.motionSignal = AppScrollMotionScope.maybeOf(context);
    final extras = context.appThemeExtras;
    final theme = Theme.of(context);

    return DecoratedBox(
      decoration: BoxDecoration(
        color: extras.terminalSurface,
        borderRadius: BorderRadius.circular(AppRadii.lg),
        border: Border.all(color: extras.terminalStroke),
        boxShadow: AppElevations.card,
      ),
      child: Column(
        children: [
          DecoratedBox(
            decoration: BoxDecoration(
              color: extras.terminalToolbar,
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(AppRadii.lg),
              ),
            ),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(
                AppSpacing.md,
                AppSpacing.md,
                AppSpacing.md,
                AppSpacing.sm,
              ),
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final compact = constraints.maxWidth < 380;

                  return Row(
                    children: [
                      DecoratedBox(
                        decoration: BoxDecoration(
                          color: extras.terminalBadgeSurface,
                          borderRadius: BorderRadius.circular(AppRadii.pill),
                          border: Border.all(
                            color: extras.terminalAccent.withValues(
                              alpha: 0.14,
                            ),
                          ),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 12,
                            vertical: 8,
                          ),
                          child: Text(
                            '${logs.length} строк',
                            style: theme.textTheme.labelLarge?.copyWith(
                              color: extras.terminalText,
                            ),
                          ),
                        ),
                      ),
                      if (!compact) ...[
                        const SizedBox(width: AppSpacing.sm),
                        AnimatedSwitcher(
                          duration: AppMotionDurations.fast,
                          child: Text(
                            autoScrollEnabled ? 'Автопрокрутка' : 'Пауза',
                            key: ValueKey(autoScrollEnabled),
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: autoScrollEnabled
                                  ? extras.success
                                  : extras.warning,
                            ),
                          ),
                        ),
                      ],
                      const Spacer(),
                      _TerminalActionButton(
                        tooltip: autoScrollEnabled
                            ? 'Пауза автопрокрутки'
                            : 'Возобновить автопрокрутку',
                        icon: autoScrollEnabled
                            ? Icons.pause_rounded
                            : Icons.play_arrow_rounded,
                        onTap: onToggleAutoScroll,
                      ),
                      const SizedBox(width: 8),
                      _TerminalActionButton(
                        tooltip: 'Копировать',
                        icon: Icons.copy_all_rounded,
                        onTap: onCopy,
                      ),
                      const SizedBox(width: 8),
                      _TerminalActionButton(
                        tooltip: 'Очистить',
                        icon: Icons.cleaning_services_rounded,
                        onTap: onClear,
                      ),
                    ],
                  );
                },
              ),
            ),
          ),
          Divider(
            height: 1,
            color: extras.terminalStroke.withValues(alpha: 0.6),
          ),
          Expanded(
            child: logs.isEmpty
                ? Center(
                    child: Padding(
                      padding: const EdgeInsets.all(AppSpacing.xl),
                      child: Text(
                        'Логи появятся после первого запуска.',
                        style: GoogleFonts.ibmPlexMono(
                          color: extras.terminalMutedText,
                          fontSize: 13,
                          height: 1.55,
                        ),
                        textAlign: TextAlign.center,
                      ),
                    ),
                  )
                : SelectionArea(
                    child: ListView.separated(
                      controller: scrollController,
                      physics: appVerticalScrollPhysics,
                      padding: const EdgeInsets.all(AppSpacing.md),
                      itemCount: logs.length,
                      separatorBuilder: (_, _) => const SizedBox(height: 8),
                      itemBuilder: (context, index) {
                        return _AnimatedLogLine(entry: logs[index]);
                      },
                    ),
                  ),
          ),
        ],
      ),
    );
  }
}

class _AnimatedLogLine extends StatefulWidget {
  const _AnimatedLogLine({required this.entry});

  final RuntimeLogEntry entry;

  @override
  State<_AnimatedLogLine> createState() => _AnimatedLogLineState();
}

class _AnimatedLogLineState extends State<_AnimatedLogLine>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: AppMotionDurations.standard,
  )..forward();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final extras = context.appThemeExtras;
    final levelColor = switch (widget.entry.level) {
      RuntimeLogLevel.system => extras.terminalMutedText,
      RuntimeLogLevel.info => theme.colorScheme.secondary,
      RuntimeLogLevel.success => extras.success,
      RuntimeLogLevel.warning => extras.warning,
      RuntimeLogLevel.error => extras.danger,
    };

    final animation = CurvedAnimation(
      parent: _controller,
      curve: AppMotionCurves.decelerate,
    );
    final timestamp = _formatTime(widget.entry.timestamp);
    final sourceLabel = widget.entry.source?.shortTitle ?? 'Система';

    return FadeTransition(
      opacity: animation,
      child: SlideTransition(
        position: Tween<Offset>(
          begin: const Offset(0, 0.08),
          end: Offset.zero,
        ).animate(animation),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: extras.terminalLineSurface,
            borderRadius: BorderRadius.circular(18),
            border: Border.all(color: levelColor.withValues(alpha: 0.24)),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
            child: RichText(
              text: TextSpan(
                style: GoogleFonts.ibmPlexMono(
                  fontSize: 12.8,
                  height: 1.48,
                  color: extras.terminalText,
                ),
                children: [
                  TextSpan(
                    text: '[$timestamp] ',
                    style: TextStyle(color: extras.terminalMutedText),
                  ),
                  TextSpan(
                    text: '[$sourceLabel] ',
                    style: TextStyle(
                      color: levelColor,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  TextSpan(text: widget.entry.message),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  String _formatTime(DateTime value) {
    final hh = value.hour.toString().padLeft(2, '0');
    final mm = value.minute.toString().padLeft(2, '0');
    final ss = value.second.toString().padLeft(2, '0');
    return '$hh:$mm:$ss';
  }
}

class _TerminalActionButton extends StatelessWidget {
  const _TerminalActionButton({
    required this.tooltip,
    required this.icon,
    required this.onTap,
  });

  final String tooltip;
  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final extras = context.appThemeExtras;

    return Tooltip(
      message: tooltip,
      child: _TerminalTapFeedback(
        onTap: onTap,
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: extras.terminalBadgeSurface,
            borderRadius: BorderRadius.circular(16),
            border: Border.all(
              color: extras.terminalStroke.withValues(alpha: 0.58),
            ),
          ),
          child: Padding(
            padding: const EdgeInsets.all(10),
            child: Icon(icon, size: 18, color: extras.terminalText),
          ),
        ),
      ),
    );
  }
}

class _TerminalTapFeedback extends StatefulWidget {
  const _TerminalTapFeedback({required this.child, required this.onTap});

  final Widget child;
  final VoidCallback onTap;

  @override
  State<_TerminalTapFeedback> createState() => _TerminalTapFeedbackState();
}

class _TerminalTapFeedbackState extends State<_TerminalTapFeedback> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapDown: (_) => setState(() => _pressed = true),
      onTapCancel: () => setState(() => _pressed = false),
      onTapUp: (_) => setState(() => _pressed = false),
      onTap: widget.onTap,
      child: AnimatedScale(
        duration: AppMotionDurations.fast,
        curve: AppMotionCurves.decelerate,
        scale: _pressed ? 0.94 : 1,
        child: AnimatedOpacity(
          duration: AppMotionDurations.fast,
          opacity: _pressed ? 0.74 : 1,
          child: widget.child,
        ),
      ),
    );
  }
}
