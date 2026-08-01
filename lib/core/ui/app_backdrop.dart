import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../app/theme/app_theme.dart';
import '../motion/app_motion.dart';
import '../motion/app_scroll_motion.dart';

class AppBackdrop extends StatefulWidget {
  const AppBackdrop({required this.child, super.key, this.motionSignal});

  final Widget child;
  final AppScrollMotionSignal? motionSignal;

  @override
  State<AppBackdrop> createState() => _AppBackdropState();
}

class _AppBackdropState extends State<AppBackdrop>
    with SingleTickerProviderStateMixin {
  late final AppScrollMotionSignal _ownedMotionSignal = AppScrollMotionSignal();
  late final AnimationController _ambient = AnimationController(
    vsync: this,
    duration: AppMotionDurations.ambientIdle,
  );

  AppScrollMotionSignal get _motionSignal =>
      widget.motionSignal ?? _ownedMotionSignal;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final disableAnimations = MediaQuery.disableAnimationsOf(context);
    if (disableAnimations) {
      _ambient
        ..stop()
        ..value = 0.18;
    } else if (!_ambient.isAnimating) {
      _ambient.repeat();
    }
  }

  @override
  void dispose() {
    _ambient.dispose();
    _ownedMotionSignal.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final extras = context.appThemeExtras;
    final colorScheme = Theme.of(context).colorScheme;
    return AppScrollMotionScope(
      signal: _motionSignal,
      child: DecoratedBox(
        decoration: BoxDecoration(gradient: extras.backgroundGradient),
        child: Stack(
          fit: StackFit.expand,
          children: [
            Positioned.fill(
              child: ExcludeSemantics(
                child: IgnorePointer(
                  child: RepaintBoundary(
                    child: CustomPaint(
                      key: const ValueKey('ambient-network-backdrop'),
                      painter: BackdropConstellationPainter(
                        animation: _ambient,
                        motionSignal: _motionSignal,
                        strokeColor: extras.glassStroke.withValues(alpha: 0.3),
                        nodeColor: colorScheme.primary.withValues(alpha: 0.2),
                        accentColor: colorScheme.secondary.withValues(
                          alpha: 0.16,
                        ),
                        glowColor: extras.glowColor,
                      ),
                    ),
                  ),
                ),
              ),
            ),
            widget.child,
          ],
        ),
      ),
    );
  }
}

@immutable
final class BackdropNode {
  const BackdropNode(this.x, this.y, this.phase);

  final double x;
  final double y;
  final double phase;
}

@immutable
final class BackdropEdge {
  const BackdropEdge(this.from, this.to, {this.arc = 0});

  final int from;
  final int to;
  final double arc;
}

/// Stable normalized topology; only its geometry drifts while painting.
final class BackdropTopology {
  static const nodes = <BackdropNode>[
    BackdropNode(0.07, 0.17, 0.08),
    BackdropNode(0.22, 0.08, 0.61),
    BackdropNode(0.38, 0.19, 0.34),
    BackdropNode(0.55, 0.1, 0.83),
    BackdropNode(0.73, 0.2, 0.49),
    BackdropNode(0.91, 0.12, 0.19),
    BackdropNode(0.13, 0.45, 0.74),
    BackdropNode(0.3, 0.52, 0.27),
    BackdropNode(0.48, 0.4, 0.93),
    BackdropNode(0.66, 0.55, 0.41),
    BackdropNode(0.86, 0.43, 0.68),
    BackdropNode(0.06, 0.76, 0.37),
    BackdropNode(0.25, 0.86, 0.88),
    BackdropNode(0.44, 0.72, 0.13),
    BackdropNode(0.62, 0.84, 0.57),
    BackdropNode(0.8, 0.73, 0.96),
    BackdropNode(0.94, 0.9, 0.22),
  ];

  static const edges = <BackdropEdge>[
    BackdropEdge(0, 1),
    BackdropEdge(1, 2, arc: 0.04),
    BackdropEdge(2, 3),
    BackdropEdge(3, 4, arc: -0.035),
    BackdropEdge(4, 5),
    BackdropEdge(0, 6),
    BackdropEdge(2, 7),
    BackdropEdge(2, 8),
    BackdropEdge(3, 8),
    BackdropEdge(4, 9),
    BackdropEdge(5, 10),
    BackdropEdge(6, 7),
    BackdropEdge(7, 8, arc: -0.045),
    BackdropEdge(8, 9),
    BackdropEdge(9, 10, arc: 0.04),
    BackdropEdge(6, 11),
    BackdropEdge(7, 12),
    BackdropEdge(8, 13),
    BackdropEdge(9, 14),
    BackdropEdge(10, 15),
    BackdropEdge(11, 12),
    BackdropEdge(12, 13, arc: 0.035),
    BackdropEdge(13, 14),
    BackdropEdge(14, 15, arc: -0.04),
    BackdropEdge(15, 16),
  ];

  @visibleForTesting
  static String get debugSignature =>
      '${nodes.length}:${edges.length}:'
      '${nodes.map((node) => '${node.x},${node.y},${node.phase}').join('|')}:'
      '${edges.map((edge) => '${edge.from},${edge.to},${edge.arc}').join('|')}';
}

class BackdropConstellationPainter extends CustomPainter {
  BackdropConstellationPainter({
    required this.animation,
    required this.motionSignal,
    required this.strokeColor,
    required this.nodeColor,
    required this.accentColor,
    required this.glowColor,
  }) : super(repaint: Listenable.merge([animation, motionSignal]));

  final Animation<double> animation;
  final AppScrollMotionSignal motionSignal;
  final Color strokeColor;
  final Color nodeColor;
  final Color accentColor;
  final Color glowColor;

  final Paint _linePaint = Paint()
    ..style = PaintingStyle.stroke
    ..strokeWidth = 1.05
    ..strokeCap = StrokeCap.round;
  final Paint _accentPaint = Paint()
    ..style = PaintingStyle.stroke
    ..strokeWidth = 1.35
    ..strokeCap = StrokeCap.round;
  final Paint _nodePaint = Paint()..style = PaintingStyle.fill;
  final Paint _glowPaint = Paint()
    ..style = PaintingStyle.fill
    ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 15);
  final Path _path = Path();
  final Path _accentPath = Path();

  @override
  void paint(Canvas canvas, Size size) {
    _linePaint.color = strokeColor;
    _accentPaint.color = accentColor;
    _nodePaint.color = nodeColor;
    _glowPaint.color = glowColor.withValues(alpha: 0.13);
    _path.reset();
    _accentPath.reset();

    final frameTime = WidgetsBinding.instance.currentSystemFrameTimeStamp;
    final ageSeconds = math.max(
      0,
      (frameTime - motionSignal.lastUpdate).inMicroseconds /
          Duration.microsecondsPerSecond,
    );
    final scrollDecay = math.exp(-ageSeconds * 2.8);
    final velocity = motionSignal.velocity * scrollDecay;
    final scrollEnergy = velocity.abs().clamp(0.0, 1.0);

    for (var i = 0; i < BackdropTopology.edges.length; i += 1) {
      final edge = BackdropTopology.edges[i];
      final from = _nodeOffset(edge.from, size, velocity);
      final to = _nodeOffset(edge.to, size, velocity);
      final path = i % 6 == 2 ? _accentPath : _path;
      path.moveTo(from.dx, from.dy);
      if (edge.arc == 0) {
        path.lineTo(to.dx, to.dy);
      } else {
        final midpoint = (from + to) / 2;
        final normal = Offset(-(to.dy - from.dy), to.dx - from.dx);
        final length = math.max(1.0, normal.distance);
        final control =
            midpoint + normal / length * size.shortestSide * edge.arc;
        path.quadraticBezierTo(control.dx, control.dy, to.dx, to.dy);
      }
    }

    canvas.drawPath(_path, _linePaint);
    canvas.drawPath(_accentPath, _accentPaint);

    for (var i = 0; i < BackdropTopology.nodes.length; i += 1) {
      final point = _nodeOffset(i, size, velocity);
      final pulse =
          0.5 +
          0.5 *
              math.sin(
                (animation.value + BackdropTopology.nodes[i].phase) *
                    math.pi *
                    2,
              );
      if (i % 5 == 1) {
        canvas.drawCircle(point, 7 + pulse * 3 + scrollEnergy * 3, _glowPaint);
      }
      canvas.drawCircle(point, 2.4 + pulse * 0.75, _nodePaint);
    }
  }

  Offset _nodeOffset(int index, Size size, double velocity) {
    final node = BackdropTopology.nodes[index];
    final phase = (animation.value + node.phase) * math.pi * 2;
    final driftX = math.sin(phase) * (2.2 + velocity.abs() * 5.5);
    final driftY = math.cos(phase * 0.73) * 2.8;
    final parallax = velocity * (10 + node.y * 18);
    return Offset(
      node.x * size.width + driftX,
      node.y * size.height + driftY - parallax,
    );
  }

  @override
  bool shouldRepaint(covariant BackdropConstellationPainter oldDelegate) {
    return oldDelegate.strokeColor != strokeColor ||
        oldDelegate.nodeColor != nodeColor ||
        oldDelegate.accentColor != accentColor ||
        oldDelegate.glowColor != glowColor ||
        oldDelegate.animation != animation ||
        oldDelegate.motionSignal != motionSignal;
  }
}
