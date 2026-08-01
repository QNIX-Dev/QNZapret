import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';

/// Keeps desktop scrolling visually clean while retaining touch-like kinetic
/// movement for touch, trackpad, stylus, and mouse dragging.
final class AppScrollBehavior extends MaterialScrollBehavior {
  const AppScrollBehavior();

  @override
  Set<PointerDeviceKind> get dragDevices => <PointerDeviceKind>{
    ...super.dragDevices,
    PointerDeviceKind.mouse,
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
        mass: 0.75,
        stiffness: 72,
        ratio: 1.08,
      );

  @override
  AppBouncingScrollPhysics applyTo(ScrollPhysics? ancestor) {
    return AppBouncingScrollPhysics(parent: buildParent(ancestor));
  }

  @override
  SpringDescription get spring => _softReturnSpring;
}

const ScrollPhysics appVerticalScrollPhysics = AppBouncingScrollPhysics(
  parent: AlwaysScrollableScrollPhysics(),
);
