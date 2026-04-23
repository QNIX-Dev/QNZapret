import 'package:flutter/material.dart';

import '../../app/theme/app_theme.dart';

class AppBackdrop extends StatelessWidget {
  const AppBackdrop({required this.child, super.key});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    final extras = context.appThemeExtras;
    final colorScheme = Theme.of(context).colorScheme;

    return DecoratedBox(
      decoration: BoxDecoration(gradient: extras.backgroundGradient),
      child: Stack(
        fit: StackFit.expand,
        children: [
          Positioned.fill(
            child: IgnorePointer(
              child: CustomPaint(
                painter: _BackdropConstellationPainter(
                  strokeColor: extras.glassStroke.withValues(alpha: 0.26),
                  nodeColor: colorScheme.primary.withValues(alpha: 0.18),
                ),
              ),
            ),
          ),
          child,
        ],
      ),
    );
  }
}

class _BackdropConstellationPainter extends CustomPainter {
  const _BackdropConstellationPainter({
    required this.strokeColor,
    required this.nodeColor,
  });

  final Color strokeColor;
  final Color nodeColor;

  @override
  void paint(Canvas canvas, Size size) {
    final linePaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.1
      ..color = strokeColor;
    final nodePaint = Paint()
      ..style = PaintingStyle.fill
      ..color = nodeColor;

    final points = <Offset>[
      Offset(size.width * 0.12, size.height * 0.18),
      Offset(size.width * 0.32, size.height * 0.12),
      Offset(size.width * 0.48, size.height * 0.22),
      Offset(size.width * 0.68, size.height * 0.16),
      Offset(size.width * 0.82, size.height * 0.28),
      Offset(size.width * 0.18, size.height * 0.56),
      Offset(size.width * 0.36, size.height * 0.48),
      Offset(size.width * 0.58, size.height * 0.6),
      Offset(size.width * 0.76, size.height * 0.52),
      Offset(size.width * 0.28, size.height * 0.84),
      Offset(size.width * 0.52, size.height * 0.78),
      Offset(size.width * 0.72, size.height * 0.88),
    ];

    final path = Path()
      ..moveTo(points[0].dx, points[0].dy)
      ..lineTo(points[2].dx, points[2].dy)
      ..lineTo(points[4].dx, points[4].dy)
      ..moveTo(points[5].dx, points[5].dy)
      ..lineTo(points[6].dx, points[6].dy)
      ..lineTo(points[8].dx, points[8].dy)
      ..moveTo(points[9].dx, points[9].dy)
      ..lineTo(points[10].dx, points[10].dy)
      ..lineTo(points[11].dx, points[11].dy)
      ..moveTo(points[1].dx, points[1].dy)
      ..lineTo(points[6].dx, points[6].dy)
      ..lineTo(points[10].dx, points[10].dy)
      ..moveTo(points[3].dx, points[3].dy)
      ..lineTo(points[7].dx, points[7].dy)
      ..lineTo(points[11].dx, points[11].dy)
      ..moveTo(points[2].dx, points[2].dy)
      ..quadraticBezierTo(
        size.width * 0.52,
        size.height * 0.44,
        points[7].dx,
        points[7].dy,
      );

    canvas.drawPath(path, linePaint);

    for (final point in points) {
      canvas.drawCircle(point, 3.8, nodePaint);
    }
  }

  @override
  bool shouldRepaint(covariant _BackdropConstellationPainter oldDelegate) {
    return oldDelegate.strokeColor != strokeColor ||
        oldDelegate.nodeColor != nodeColor;
  }
}
