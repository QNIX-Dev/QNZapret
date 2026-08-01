import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../app/theme/app_theme.dart';
import '../../backend/backend.dart';
import '../../motion/app_motion.dart';
import '../../state/runtime_view_models.dart';

class ConnectedFlowIllustration extends StatelessWidget {
  const ConnectedFlowIllustration({
    required this.snapshot,
    super.key,
    this.compact = false,
  });

  final ProxyRuntimeSnapshot snapshot;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final progress = _progressFor(snapshot);
    final theme = Theme.of(context);
    final highlight =
        Color.lerp(
          theme.colorScheme.surfaceBright,
          theme.colorScheme.secondary,
          0.34,
        ) ??
        theme.colorScheme.surfaceBright;

    return AspectRatio(
      aspectRatio: compact ? 1.02 : 1.16,
      child: TweenAnimationBuilder<double>(
        duration: AppMotionDurations.page,
        curve: AppMotionCurves.decelerate,
        tween: Tween<double>(end: progress),
        builder: (context, animatedProgress, _) {
          return CustomPaint(
            painter: _ConnectedFlowPainter(
              progress: animatedProgress,
              hasFailure: snapshot.hasFailure || snapshot.hasPartialFailure,
              isRunning: snapshot.isOperational,
              interceptionActive: snapshot.interceptionReady,
              primary: theme.colorScheme.primary,
              secondary: theme.colorScheme.secondary,
              tertiary: theme.colorScheme.tertiary,
              glow: context.appThemeExtras.glowColor,
              danger: context.appThemeExtras.danger,
              highlight: highlight,
            ),
          );
        },
      ),
    );
  }

  double _progressFor(ProxyRuntimeSnapshot snapshot) {
    if (snapshot.hasFailure || snapshot.hasPartialFailure) {
      return 0.74;
    }
    if (snapshot.isOperational) {
      return 1;
    }
    if (snapshot.strategyEngineReady) {
      return 0.78;
    }
    if (snapshot.serviceActive || snapshot.state == ProxyRuntimeState.running) {
      return 0.62;
    }
    if (snapshot.state == ProxyRuntimeState.starting) {
      return 0.48;
    }
    if (snapshot.state == ProxyRuntimeState.stopping) {
      return 0.38;
    }
    if (snapshot.backendConnected) {
      return 0.3;
    }
    return 0.22;
  }
}

class _ConnectedFlowPainter extends CustomPainter {
  const _ConnectedFlowPainter({
    required this.progress,
    required this.hasFailure,
    required this.isRunning,
    required this.interceptionActive,
    required this.primary,
    required this.secondary,
    required this.tertiary,
    required this.glow,
    required this.danger,
    required this.highlight,
  });

  final double progress;
  final bool hasFailure;
  final bool isRunning;
  final bool interceptionActive;
  final Color primary;
  final Color secondary;
  final Color tertiary;
  final Color glow;
  final Color danger;
  final Color highlight;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width * 0.5, size.height * 0.48);
    final radius = math.min(size.width, size.height) * 0.28;

    final glowPaint = Paint()
      ..shader = RadialGradient(
        colors: [
          glow.withValues(alpha: isRunning ? 0.58 : 0.34),
          glow.withValues(alpha: 0),
        ],
      ).createShader(Rect.fromCircle(center: center, radius: radius * 1.9));
    canvas.drawCircle(center, radius * 1.76, glowPaint);

    final orbitPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.4
      ..color = primary.withValues(alpha: 0.3 + progress * 0.22);
    final linkPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.2
      ..color = secondary.withValues(alpha: 0.18 + progress * 0.34);
    final activePaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 4
      ..strokeCap = StrokeCap.round
      ..color = interceptionActive
          ? secondary.withValues(alpha: 0.96)
          : primary.withValues(alpha: 0.72);

    canvas.drawCircle(center, radius, orbitPaint);
    canvas.drawOval(
      Rect.fromCenter(
        center: center,
        width: radius * 2.34,
        height: radius * 1.2,
      ),
      orbitPaint,
    );
    canvas.drawOval(
      Rect.fromCenter(
        center: center,
        width: radius * 1.28,
        height: radius * 2.14,
      ),
      orbitPaint,
    );
    canvas.drawArc(
      Rect.fromCircle(center: center, radius: radius * 1.02),
      -math.pi / 3,
      math.pi * 1.4,
      false,
      activePaint,
    );

    final arcPath = Path()
      ..moveTo(size.width * 0.1, size.height * 0.74)
      ..quadraticBezierTo(
        size.width * 0.34,
        size.height * (0.38 - progress * 0.08),
        size.width * 0.56,
        size.height * 0.62,
      )
      ..quadraticBezierTo(
        size.width * 0.74,
        size.height * 0.8,
        size.width * 0.9,
        size.height * (0.26 + (1 - progress) * 0.12),
      );
    canvas.drawPath(arcPath, linkPaint);

    final activeNodePaint = Paint()
      ..style = PaintingStyle.fill
      ..color = tertiary.withValues(alpha: 0.94);
    final inactiveNodePaint = Paint()
      ..style = PaintingStyle.fill
      ..color = primary.withValues(alpha: 0.24);
    final failureNodePaint = Paint()
      ..style = PaintingStyle.fill
      ..color = danger;

    final nodes = <Offset>[
      Offset(center.dx, center.dy - radius),
      Offset(center.dx + radius * 0.85, center.dy - radius * 0.2),
      Offset(center.dx + radius * 0.6, center.dy + radius * 0.75),
      Offset(center.dx - radius * 0.62, center.dy + radius * 0.78),
      Offset(center.dx - radius * 0.9, center.dy - radius * 0.16),
      Offset(size.width * 0.1, size.height * 0.74),
      Offset(size.width * 0.9, size.height * 0.28),
    ];

    final activeCount = (nodes.length * progress)
        .clamp(1, nodes.length)
        .toDouble()
        .round();
    for (var i = 0; i < nodes.length; i += 1) {
      final paint = hasFailure && i == 1
          ? failureNodePaint
          : i < activeCount
          ? activeNodePaint
          : inactiveNodePaint;
      canvas.drawCircle(nodes[i], i < 5 ? 8 : 7, paint);
    }

    final corePaint = Paint()
      ..shader = RadialGradient(
        colors: [
          highlight.withValues(alpha: 0.96),
          secondary.withValues(alpha: 0.9),
          primary.withValues(alpha: 0.72),
        ],
      ).createShader(Rect.fromCircle(center: center, radius: radius * 0.5));
    canvas.drawCircle(center, radius * (0.24 + progress * 0.16), corePaint);
  }

  @override
  bool shouldRepaint(covariant _ConnectedFlowPainter oldDelegate) {
    return oldDelegate.progress != progress ||
        oldDelegate.hasFailure != hasFailure ||
        oldDelegate.isRunning != isRunning ||
        oldDelegate.interceptionActive != interceptionActive ||
        oldDelegate.primary != primary ||
        oldDelegate.secondary != secondary ||
        oldDelegate.tertiary != tertiary ||
        oldDelegate.glow != glow ||
        oldDelegate.danger != danger ||
        oldDelegate.highlight != highlight;
  }
}
