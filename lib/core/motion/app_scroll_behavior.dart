import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';

/// Keeps desktop scrolling visually clean while retaining touch-like kinetic
/// movement for touch, trackpad, and stylus dragging.
final class AppScrollBehavior extends MaterialScrollBehavior {
  const AppScrollBehavior();

  @override
  Set<PointerDeviceKind> get dragDevices => <PointerDeviceKind>{
    ...super.dragDevices,
    PointerDeviceKind.trackpad,
  };

  @override
  Widget buildScrollbar(
    BuildContext context,
    Widget child,
    ScrollableDetails details,
  ) {
    return child;
  }
}

/// A softer spring than Flutter's default bounce, so overscroll settles
/// smoothly instead of snapping back at desktop pointer-wheel speeds.
final class AppBouncingScrollPhysics extends BouncingScrollPhysics {
  const AppBouncingScrollPhysics({super.parent});

  static final SpringDescription _softReturnSpring =
      SpringDescription.withDampingRatio(
        mass: 0.72,
        stiffness: 94,
        ratio: 1.03,
      );

  @override
  AppBouncingScrollPhysics applyTo(ScrollPhysics? ancestor) {
    return AppBouncingScrollPhysics(parent: buildParent(ancestor));
  }

  @override
  SpringDescription get spring => _softReturnSpring;

  @override
  Simulation? createBallisticSimulation(
    ScrollMetrics position,
    double velocity,
  ) {
    final contentMin = position.minScrollExtent + pointerActivationInset;
    final contentMax = position.maxScrollExtent - pointerActivationInset;
    final tolerance = toleranceFor(position);
    final outsideContent =
        position.pixels < contentMin || position.pixels > contentMax;

    if (velocity.abs() < tolerance.velocity && !outsideContent) {
      return null;
    }

    return BouncingScrollSimulation(
      spring: spring,
      position: position.pixels,
      velocity: velocity,
      leadingExtent: contentMin,
      trailingExtent: contentMax,
      tolerance: tolerance,
      constantDeceleration: switch (decelerationRate) {
        ScrollDecelerationRate.fast => 1400,
        ScrollDecelerationRate.normal => 0,
      },
    );
  }
}

/// Lets [Scrollable] route a pointer signal to the custom position while the
/// content itself is exactly at an edge. The custom physics always settles to
/// the real content boundary, so this inset is never a visible resting offset.
const double pointerActivationInset = 0.5;

const ScrollPhysics appVerticalScrollPhysics = AppBouncingScrollPhysics(
  parent: AlwaysScrollableScrollPhysics(),
);
