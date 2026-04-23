import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

import '../../../app/theme/app_theme.dart';
import '../../motion/app_motion.dart';
import '../design_tokens.dart';

class TerminalIllustration extends StatelessWidget {
  const TerminalIllustration({super.key, this.compact = false});

  final bool compact;

  @override
  Widget build(BuildContext context) {
    final extras = context.appThemeExtras;
    final theme = Theme.of(context);

    return AspectRatio(
      aspectRatio: compact ? 1.48 : 1.68,
      child: TweenAnimationBuilder<double>(
        tween: Tween(end: 1),
        duration: AppMotionDurations.page,
        curve: AppMotionCurves.decelerate,
        builder: (context, progress, _) {
          return DecoratedBox(
            decoration: BoxDecoration(
              color: extras.terminalSurface.withValues(alpha: 0.9),
              borderRadius: BorderRadius.circular(AppRadii.lg),
              border: Border.all(color: extras.terminalStroke),
              boxShadow: [
                BoxShadow(
                  color: theme.colorScheme.primary.withValues(alpha: 0.16),
                  blurRadius: 30,
                  offset: const Offset(0, 18),
                ),
              ],
            ),
            child: CustomPaint(
              painter: _TerminalIllustrationPainter(
                progress: progress,
                accent: theme.colorScheme.primary,
                secondary: theme.colorScheme.secondary,
                tertiary: theme.colorScheme.tertiary,
                surface: extras.terminalSurface,
                lineSurface: extras.terminalLineSurface,
                stroke: extras.terminalStroke,
                text: extras.terminalText,
                muted: extras.terminalMutedText,
                success: extras.success,
                warning: extras.warning,
              ),
            ),
          );
        },
      ),
    );
  }
}

class _TerminalIllustrationPainter extends CustomPainter {
  const _TerminalIllustrationPainter({
    required this.progress,
    required this.accent,
    required this.secondary,
    required this.tertiary,
    required this.surface,
    required this.lineSurface,
    required this.stroke,
    required this.text,
    required this.muted,
    required this.success,
    required this.warning,
  });

  final double progress;
  final Color accent;
  final Color secondary;
  final Color tertiary;
  final Color surface;
  final Color lineSurface;
  final Color stroke;
  final Color text;
  final Color muted;
  final Color success;
  final Color warning;

  @override
  void paint(Canvas canvas, Size size) {
    final radius = Radius.circular(size.shortestSide * 0.07);
    final rect = Offset.zero & size;
    final headerHeight = size.height * 0.22;

    final headerPaint = Paint()
      ..shader = LinearGradient(
        colors: [
          accent.withValues(alpha: 0.22),
          secondary.withValues(alpha: 0.14),
          surface.withValues(alpha: 0.28),
        ],
      ).createShader(Rect.fromLTWH(0, 0, size.width, headerHeight));
    canvas.drawRRect(
      RRect.fromRectAndRadius(rect, radius),
      Paint()..color = surface,
    );
    canvas.drawRRect(
      RRect.fromRectAndCorners(
        Rect.fromLTWH(0, 0, size.width, headerHeight),
        topLeft: radius,
        topRight: radius,
      ),
      headerPaint,
    );

    final dotPaint = Paint()..style = PaintingStyle.fill;
    for (var i = 0; i < 3; i += 1) {
      dotPaint.color = [warning, tertiary, success][i].withValues(alpha: 0.9);
      canvas.drawCircle(
        Offset(size.width * (0.09 + i * 0.055), headerHeight * 0.48),
        5.2,
        dotPaint,
      );
    }

    final mono = GoogleFonts.ibmPlexMono(
      fontSize: size.width < 300 ? 10.5 : 12,
      fontWeight: FontWeight.w600,
      color: text,
    );
    _paintText(
      canvas,
      '> status',
      Offset(size.width * 0.08, headerHeight + size.height * 0.12),
      mono.copyWith(color: accent),
    );

    final rows = <_IllustrationRow>[
      _IllustrationRow('Основной сервис', success, 0.72),
      _IllustrationRow('Telegram-портал', secondary, 0.58),
      _IllustrationRow('Последнее сообщение', muted, 0.42),
    ];

    for (var i = 0; i < rows.length; i += 1) {
      final rowTop = headerHeight + size.height * (0.22 + i * 0.18);
      final reveal = (progress - i * 0.12).clamp(0.0, 1.0);
      final rowRect = Rect.fromLTWH(
        size.width * 0.07,
        rowTop,
        size.width * (0.84 * reveal),
        size.height * 0.12,
      );
      final rowPaint = Paint()..color = lineSurface.withValues(alpha: 0.78);
      canvas.drawRRect(
        RRect.fromRectAndRadius(rowRect, Radius.circular(size.height * 0.04)),
        rowPaint,
      );

      final markerPaint = Paint()..color = rows[i].color;
      canvas.drawCircle(
        Offset(size.width * 0.12, rowTop + size.height * 0.06),
        4.5,
        markerPaint,
      );
      _paintText(
        canvas,
        rows[i].label,
        Offset(size.width * 0.17, rowTop + size.height * 0.034),
        mono.copyWith(color: rows[i].color.withValues(alpha: rows[i].opacity)),
      );
    }

    final glowPaint = Paint()
      ..shader =
          RadialGradient(
            colors: [
              accent.withValues(alpha: 0.18 * progress),
              accent.withValues(alpha: 0),
            ],
          ).createShader(
            Rect.fromCircle(
              center: Offset(size.width * 0.78, size.height * 0.28),
              radius: size.width * 0.35,
            ),
          );
    canvas.drawCircle(
      Offset(size.width * 0.78, size.height * 0.28),
      size.width * 0.34,
      glowPaint,
    );

    final borderPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1
      ..color = stroke.withValues(alpha: 0.9);
    canvas.drawRRect(
      RRect.fromRectAndRadius(rect.deflate(0.5), radius),
      borderPaint,
    );
  }

  void _paintText(Canvas canvas, String text, Offset offset, TextStyle style) {
    final painter = TextPainter(
      text: TextSpan(text: text, style: style),
      textDirection: TextDirection.ltr,
      maxLines: 1,
    )..layout();
    painter.paint(canvas, offset);
  }

  @override
  bool shouldRepaint(covariant _TerminalIllustrationPainter oldDelegate) {
    return oldDelegate.progress != progress ||
        oldDelegate.accent != accent ||
        oldDelegate.secondary != secondary ||
        oldDelegate.tertiary != tertiary ||
        oldDelegate.surface != surface ||
        oldDelegate.lineSurface != lineSurface ||
        oldDelegate.stroke != stroke ||
        oldDelegate.text != text ||
        oldDelegate.muted != muted ||
        oldDelegate.success != success ||
        oldDelegate.warning != warning;
  }
}

class _IllustrationRow {
  const _IllustrationRow(this.label, this.color, this.opacity);

  final String label;
  final Color color;
  final double opacity;
}
