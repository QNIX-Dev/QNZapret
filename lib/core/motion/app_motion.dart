import 'package:flutter/animation.dart';

final class AppMotionDurations {
  static const Duration fast = Duration(milliseconds: 180);
  static const Duration standard = Duration(milliseconds: 260);
  static const Duration slow = Duration(milliseconds: 420);
  static const Duration page = Duration(milliseconds: 560);
}

final class AppMotionCurves {
  static const Curve standard = Cubic(0.2, 0.0, 0.0, 1.0);
  static const Curve emphasized = Cubic(0.2, 0.0, 0.0, 1.0);
  static const Curve decelerate = Cubic(0.05, 0.7, 0.1, 1.0);
  static const Curve accelerate = Cubic(0.3, 0.0, 0.8, 0.15);
}
