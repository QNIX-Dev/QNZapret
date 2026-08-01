import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../app/theme/app_theme.dart';
import '../../backend/backend.dart';
import '../../motion/app_motion.dart';
import '../../state/runtime_view_models.dart';

class ConnectedFlowIllustration extends StatefulWidget {
  const ConnectedFlowIllustration({
    required this.snapshot,
    super.key,
    this.compact = false,
  });

  final ProxyRuntimeSnapshot snapshot;
  final bool compact;

  @override
  State<ConnectedFlowIllustration> createState() =>
      _ConnectedFlowIllustrationState();
}

class _ConnectedFlowIllustrationState extends State<ConnectedFlowIllustration>
    with TickerProviderStateMixin {
  late final AnimationController _progress = AnimationController.unbounded(
    vsync: this,
    value: _progressFor(widget.snapshot),
  );
  late final AnimationController _ambient = AnimationController(
    vsync: this,
    duration: _motionFor(widget.snapshot).period,
  );
  bool _disableAnimations = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _disableAnimations = MediaQuery.disableAnimationsOf(context);
    _syncAmbientMotion();
  }

  @override
  void didUpdateWidget(ConnectedFlowIllustration oldWidget) {
    super.didUpdateWidget(oldWidget);
    final target = _progressFor(widget.snapshot);
    if (_disableAnimations) {
      _progress.value = target;
    } else {
      _progress.animateTo(
        target,
        duration: AppMotionDurations.page,
        curve: AppMotionCurves.decelerate,
      );
    }
    _syncAmbientMotion();
  }

  void _syncAmbientMotion() {
    if (_disableAnimations) {
      _ambient
        ..stop()
        ..value = 0.16;
      return;
    }

    final period = _motionFor(widget.snapshot).period;
    if (_ambient.duration != period) {
      _ambient.duration = period;
    }
    if (!_ambient.isAnimating) {
      _ambient.repeat();
    }
  }

  @override
  void dispose() {
    _progress.dispose();
    _ambient.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final highlight =
        Color.lerp(
          theme.colorScheme.surfaceBright,
          theme.colorScheme.secondary,
          0.34,
        ) ??
        theme.colorScheme.surfaceBright;
    final motion = _motionFor(widget.snapshot);

    return AspectRatio(
      aspectRatio: widget.compact ? 1.02 : 1.16,
      child: RepaintBoundary(
        child: CustomPaint(
          key: const ValueKey('connected-flow-paint'),
          painter: ConnectedFlowPainter(
            progress: _progress,
            phase: _ambient,
            motionIntensity: _disableAnimations ? 0 : motion.intensity,
            hasFailure:
                widget.snapshot.hasFailure || widget.snapshot.hasPartialFailure,
            isRunning: widget.snapshot.isOperational,
            interceptionActive: widget.snapshot.interceptionReady,
            primary: theme.colorScheme.primary,
            secondary: theme.colorScheme.secondary,
            tertiary: theme.colorScheme.tertiary,
            glow: context.appThemeExtras.glowColor,
            danger: context.appThemeExtras.danger,
            highlight: highlight,
          ),
        ),
      ),
    );
  }

  static double _progressFor(ProxyRuntimeSnapshot snapshot) {
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

  static _FlowMotion _motionFor(ProxyRuntimeSnapshot snapshot) {
    if (snapshot.hasFailure || snapshot.hasPartialFailure) {
      return const _FlowMotion(0.12, AppMotionDurations.ambientFailure);
    }
    if (snapshot.state == ProxyRuntimeState.starting) {
      return const _FlowMotion(0.9, AppMotionDurations.ambientStarting);
    }
    if (snapshot.isOperational || snapshot.state == ProxyRuntimeState.running) {
      return const _FlowMotion(0.64, AppMotionDurations.ambientRunning);
    }
    if (snapshot.state == ProxyRuntimeState.stopping) {
      return const _FlowMotion(0.28, AppMotionDurations.ambientStopping);
    }
    return const _FlowMotion(0.2, AppMotionDurations.ambientIdle);
  }
}

class ConnectedFlowPainter extends CustomPainter {
  ConnectedFlowPainter({
    required this.progress,
    required this.phase,
    required this.motionIntensity,
    required this.hasFailure,
    required this.isRunning,
    required this.interceptionActive,
    required this.primary,
    required this.secondary,
    required this.tertiary,
    required this.glow,
    required this.danger,
    required this.highlight,
  }) : super(repaint: Listenable.merge([progress, phase]));

  final Animation<double> progress;
  final Animation<double> phase;
  final double motionIntensity;
  final bool hasFailure;
  final bool isRunning;
  final bool interceptionActive;
  final Color primary;
  final Color secondary;
  final Color tertiary;
  final Color glow;
  final Color danger;
  final Color highlight;

  final Paint _glowPaint = Paint()..style = PaintingStyle.fill;
  final Paint _orbitPaint = Paint()
    ..style = PaintingStyle.stroke
    ..strokeWidth = 1.4;
  final Paint _linkPaint = Paint()
    ..style = PaintingStyle.stroke
    ..strokeWidth = 2.2
    ..strokeCap = StrokeCap.round;
  final Paint _activePaint = Paint()
    ..style = PaintingStyle.stroke
    ..strokeWidth = 4
    ..strokeCap = StrokeCap.round;
  final Paint _nodePaint = Paint()..style = PaintingStyle.fill;
  final Paint _pulsePaint = Paint()..style = PaintingStyle.fill;
  final Paint _corePaint = Paint()..style = PaintingStyle.fill;
  final Path _arcPath = Path();

  @visibleForTesting
  double get debugPhase => phase.value;

  @override
  void paint(Canvas canvas, Size size) {
    final animatedProgress = progress.value;
    final angle = phase.value * math.pi * 2;
    final intensity = motionIntensity;
    final breath = math.sin(angle) * intensity;
    final center = Offset(size.width * 0.5, size.height * 0.48);
    final radius = math.min(size.width, size.height) * 0.28;

    _glowPaint.shader = RadialGradient(
      colors: [
        glow.withValues(alpha: (isRunning ? 0.55 : 0.32) + breath * 0.045),
        glow.withValues(alpha: 0),
      ],
    ).createShader(Rect.fromCircle(center: center, radius: radius * 1.9));
    canvas.drawCircle(center, radius * (1.74 + breath * 0.035), _glowPaint);

    _orbitPaint.color = primary.withValues(
      alpha: 0.3 + animatedProgress * 0.22,
    );
    _linkPaint.color = secondary.withValues(
      alpha: 0.18 + animatedProgress * 0.34,
    );
    _activePaint.color = hasFailure
        ? danger.withValues(alpha: 0.72)
        : interceptionActive
        ? secondary.withValues(alpha: 0.96)
        : primary.withValues(alpha: 0.72);

    canvas.drawCircle(center, radius, _orbitPaint);
    _drawRotatedOval(
      canvas,
      center,
      Rect.fromCenter(
        center: center,
        width: radius * 2.34,
        height: radius * 1.2,
      ),
      angle * 0.035 * intensity,
    );
    _drawRotatedOval(
      canvas,
      center,
      Rect.fromCenter(
        center: center,
        width: radius * 1.28,
        height: radius * 2.14,
      ),
      -angle * 0.025 * intensity,
    );
    canvas.drawArc(
      Rect.fromCircle(center: center, radius: radius * 1.02),
      -math.pi / 3 + angle * (0.05 + intensity * 0.1),
      math.pi * (hasFailure ? 0.48 : 1.4),
      false,
      _activePaint,
    );

    _arcPath
      ..reset()
      ..moveTo(size.width * 0.1, size.height * 0.74)
      ..quadraticBezierTo(
        size.width * 0.34,
        size.height * (0.38 - animatedProgress * 0.08),
        size.width * 0.56,
        size.height * 0.62,
      )
      ..quadraticBezierTo(
        size.width * 0.74,
        size.height * 0.8,
        size.width * 0.9,
        size.height * (0.26 + (1 - animatedProgress) * 0.12),
      );
    canvas.drawPath(_arcPath, _linkPaint);

    _paintNodes(canvas, size, center, radius, animatedProgress, angle);
    _paintTravelingPulse(canvas, center, radius, angle, intensity);

    _corePaint.shader = RadialGradient(
      colors: [
        highlight.withValues(alpha: 0.96),
        secondary.withValues(alpha: 0.9),
        primary.withValues(alpha: 0.72),
      ],
    ).createShader(Rect.fromCircle(center: center, radius: radius * 0.5));
    canvas.drawCircle(
      center,
      radius * (0.24 + animatedProgress * 0.16 + breath.abs() * 0.012),
      _corePaint,
    );
  }

  void _drawRotatedOval(
    Canvas canvas,
    Offset center,
    Rect rect,
    double rotation,
  ) {
    canvas
      ..save()
      ..translate(center.dx, center.dy)
      ..rotate(rotation)
      ..translate(-center.dx, -center.dy)
      ..drawOval(rect, _orbitPaint)
      ..restore();
  }

  void _paintNodes(
    Canvas canvas,
    Size size,
    Offset center,
    double radius,
    double animatedProgress,
    double angle,
  ) {
    final activeCount = (7 * animatedProgress).clamp(1, 7).round();
    for (var i = 0; i < 7; i += 1) {
      final node = _nodeAt(i, size, center, radius);
      final active = i < activeCount;
      _nodePaint.color = hasFailure && i == 1
          ? danger
          : active
          ? tertiary.withValues(alpha: 0.94)
          : primary.withValues(alpha: 0.24);
      final pulse = active
          ? (math.sin(angle + i * 1.37) + 1) * 0.42 * motionIntensity
          : 0.0;
      canvas.drawCircle(node, (i < 5 ? 8 : 7) + pulse, _nodePaint);
    }
  }

  Offset _nodeAt(int index, Size size, Offset center, double radius) {
    return switch (index) {
      0 => Offset(center.dx, center.dy - radius),
      1 => Offset(center.dx + radius * 0.85, center.dy - radius * 0.2),
      2 => Offset(center.dx + radius * 0.6, center.dy + radius * 0.75),
      3 => Offset(center.dx - radius * 0.62, center.dy + radius * 0.78),
      4 => Offset(center.dx - radius * 0.9, center.dy - radius * 0.16),
      5 => Offset(size.width * 0.1, size.height * 0.74),
      _ => Offset(size.width * 0.9, size.height * 0.28),
    };
  }

  void _paintTravelingPulse(
    Canvas canvas,
    Offset center,
    double radius,
    double angle,
    double intensity,
  ) {
    if (intensity == 0 || hasFailure) {
      return;
    }
    final pulseAngle = angle * 1.45 - math.pi / 3;
    final pulse = Offset(
      center.dx + math.cos(pulseAngle) * radius * 1.02,
      center.dy + math.sin(pulseAngle) * radius * 1.02,
    );
    _pulsePaint.color = highlight.withValues(alpha: 0.72 * intensity);
    _pulsePaint.maskFilter = MaskFilter.blur(
      BlurStyle.normal,
      3 + intensity * 3,
    );
    canvas.drawCircle(pulse, 2.5 + intensity * 2, _pulsePaint);
    _pulsePaint.maskFilter = null;
  }

  @override
  bool shouldRepaint(covariant ConnectedFlowPainter oldDelegate) {
    return oldDelegate.progress != progress ||
        oldDelegate.phase != phase ||
        oldDelegate.motionIntensity != motionIntensity ||
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

final class _FlowMotion {
  const _FlowMotion(this.intensity, this.period);

  final double intensity;
  final Duration period;
}
