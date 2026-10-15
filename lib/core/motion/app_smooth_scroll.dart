import 'dart:math' as math;

import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';

import 'app_scroll_behavior.dart';
import 'app_scroll_motion.dart';

/// A controller whose position interpolates discrete desktop wheel input while
/// preserving a direct path for the small deltas produced by precision input.
final class AppScrollController extends ScrollController {
  AppScrollController({
    super.initialScrollOffset,
    super.keepScrollOffset,
    super.debugLabel,
    AppScrollMotionSignal? motionSignal,
  }) : _motionSignal = motionSignal;

  AppScrollMotionSignal? _motionSignal;
  final Map<ScrollPosition, _PositionSample> _samples = {};
  final Map<ScrollPosition, VoidCallback> _listeners = {};
  bool _disposed = false;

  set motionSignal(AppScrollMotionSignal? value) => _motionSignal = value;

  @visibleForTesting
  bool get debugDisposed => _disposed;

  @override
  ScrollPosition createScrollPosition(
    ScrollPhysics physics,
    ScrollContext context,
    ScrollPosition? oldPosition,
  ) {
    return AppSmoothScrollPosition(
      physics: physics,
      context: context,
      initialPixels: initialScrollOffset,
      keepScrollOffset: keepScrollOffset,
      oldPosition: oldPosition,
      debugLabel: debugLabel,
    );
  }

  @override
  void attach(ScrollPosition position) {
    super.attach(position);
    final now = _frameTimestamp;
    _samples[position] = _PositionSample(position.pixels, now);
    void listener() => _reportPosition(position);
    _listeners[position] = listener;
    position.addListener(listener);
  }

  @override
  void detach(ScrollPosition position) {
    final listener = _listeners.remove(position);
    if (listener != null) {
      position.removeListener(listener);
    }
    _samples.remove(position);
    super.detach(position);
  }

  void _reportPosition(ScrollPosition position) {
    final signal = _motionSignal;
    if (signal == null || !position.hasContentDimensions) {
      return;
    }

    final now = _frameTimestamp;
    final previous = _samples[position];
    _samples[position] = _PositionSample(position.pixels, now);
    final range = math.max(
      1.0,
      position.maxScrollExtent - position.minScrollExtent,
    );
    final elapsedMicros = previous == null
        ? 16667
        : math.max(1000, (now - previous.timestamp).inMicroseconds);
    final pixelsPerSecond = previous == null
        ? 0.0
        : (position.pixels - previous.pixels) * 1000000 / elapsedMicros;

    signal.record(
      normalizedOffset: (position.pixels - position.minScrollExtent) / range,
      normalizedVelocity: pixelsPerSecond / 2200,
      timestamp: now,
    );
  }

  Duration get _frameTimestamp =>
      WidgetsBinding.instance.currentSystemFrameTimeStamp;

  @override
  void dispose() {
    _disposed = true;
    for (final entry in _listeners.entries) {
      entry.key.removeListener(entry.value);
    }
    _listeners.clear();
    _samples.clear();
    super.dispose();
  }
}

final class AppSmoothScrollPosition extends ScrollPositionWithSingleContext {
  AppSmoothScrollPosition({
    required super.physics,
    required super.context,
    super.initialPixels,
    super.keepScrollOffset,
    super.oldPosition,
    super.debugLabel,
  });

  static const double _precisionDeltaThreshold = 18;
  static const double _maxElasticExtent = 58;
  static const Duration _wheelSequenceGap = Duration(milliseconds: 135);

  double? _wheelTarget;
  Duration _lastWheelInput = Duration.zero;

  double get contentMinScrollExtent => minScrollExtent + pointerActivationInset;
  double get contentMaxScrollExtent => maxScrollExtent - pointerActivationInset;

  @override
  bool applyContentDimensions(double minScrollExtent, double maxScrollExtent) {
    return super.applyContentDimensions(
      minScrollExtent - pointerActivationInset,
      maxScrollExtent + pointerActivationInset,
    );
  }

  @override
  void pointerScroll(double delta) {
    if (delta == 0) {
      _wheelTarget = null;
      goBallistic(0);
      return;
    }

    updateUserScrollDirection(
      -delta > 0 ? ScrollDirection.forward : ScrollDirection.reverse,
    );

    if (delta.abs() < _precisionDeltaThreshold) {
      _applyPrecisionDelta(delta);
      return;
    }

    final now = WidgetsBinding.instance.currentSystemFrameTimeStamp;
    final continuesSequence =
        _wheelTarget != null && now - _lastWheelInput <= _wheelSequenceGap;
    final base = continuesSequence ? _wheelTarget! : pixels;
    _wheelTarget = _elasticTarget(base + delta);
    _lastWheelInput = now;

    final distance = (_wheelTarget! - pixels).abs();
    final duration = Duration(
      milliseconds: (112 + distance * 0.14).round().clamp(120, 170),
    );
    final durationSeconds =
        duration.inMicroseconds / Duration.microsecondsPerSecond;
    final incomingVelocity = activity?.velocity ?? 0;
    final displacement = _wheelTarget! - pixels;
    final carriedVelocity = incomingVelocity.sign == displacement.sign
        ? incomingVelocity.abs().clamp(
                0.0,
                3 * distance / math.max(0.001, durationSeconds),
              ) *
              displacement.sign
        : 0.0;

    beginActivity(
      DrivenScrollActivity.simulation(
        this,
        _HermiteScrollSimulation(
          from: pixels,
          to: _wheelTarget!,
          velocity: carriedVelocity,
          duration: duration,
        ),
        vsync: context.vsync,
      ),
    );
  }

  void _applyPrecisionDelta(double delta) {
    _wheelTarget = null;
    goIdle();
    final oldPixels = pixels;
    forcePixels(_elasticTarget(pixels + delta));
    if (pixels != oldPixels) {
      isScrollingNotifier.value = true;
      didStartScroll();
      didUpdateScrollPositionBy(pixels - oldPixels);
      didEndScroll();
    }
    goBallistic(0);
  }

  double _elasticTarget(double candidate) {
    if (candidate < contentMinScrollExtent) {
      final distance = contentMinScrollExtent - candidate;
      return contentMinScrollExtent - _rubberBand(distance);
    }
    if (candidate > contentMaxScrollExtent) {
      final distance = candidate - contentMaxScrollExtent;
      return contentMaxScrollExtent + _rubberBand(distance);
    }
    return candidate;
  }

  double _rubberBand(double distance) {
    return _maxElasticExtent * (1 - math.exp(-distance / _maxElasticExtent));
  }
}

class AppSmoothSingleChildScrollView extends StatefulWidget {
  const AppSmoothSingleChildScrollView({
    required this.child,
    super.key,
    this.controller,
    this.padding,
    this.physics = appVerticalScrollPhysics,
    this.clipBehavior = Clip.hardEdge,
  });

  final Widget child;
  final AppScrollController? controller;
  final EdgeInsetsGeometry? padding;
  final ScrollPhysics physics;
  final Clip clipBehavior;

  @override
  State<AppSmoothSingleChildScrollView> createState() =>
      _AppSmoothSingleChildScrollViewState();
}

class _AppSmoothSingleChildScrollViewState
    extends State<AppSmoothSingleChildScrollView> {
  AppScrollController? _ownedController;

  AppScrollController get _controller =>
      widget.controller ?? (_ownedController ??= AppScrollController());

  @override
  Widget build(BuildContext context) {
    _controller.motionSignal = AppScrollMotionScope.maybeOf(context);
    return SingleChildScrollView(
      controller: _controller,
      padding: widget.padding,
      physics: widget.physics,
      clipBehavior: widget.clipBehavior,
      child: widget.child,
    );
  }

  @override
  void didUpdateWidget(AppSmoothSingleChildScrollView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller == null && widget.controller != null) {
      _ownedController?.dispose();
      _ownedController = null;
    }
  }

  @override
  void dispose() {
    _ownedController?.dispose();
    super.dispose();
  }
}

final class _HermiteScrollSimulation extends Simulation {
  _HermiteScrollSimulation({
    required this.from,
    required this.to,
    required this.velocity,
    required Duration duration,
  }) : durationSeconds =
           duration.inMicroseconds / Duration.microsecondsPerSecond;

  final double from;
  final double to;
  final double velocity;
  final double durationSeconds;

  double _t(double time) => (time / durationSeconds).clamp(0.0, 1.0);

  @override
  double x(double time) {
    final t = _t(time);
    final t2 = t * t;
    final t3 = t2 * t;
    final h00 = 2 * t3 - 3 * t2 + 1;
    final h10 = t3 - 2 * t2 + t;
    final h01 = -2 * t3 + 3 * t2;
    return h00 * from + h10 * velocity * durationSeconds + h01 * to;
  }

  @override
  double dx(double time) {
    final t = _t(time);
    final t2 = t * t;
    final derivative =
        (6 * t2 - 6 * t) * from +
        (3 * t2 - 4 * t + 1) * velocity * durationSeconds +
        (-6 * t2 + 6 * t) * to;
    return derivative / durationSeconds;
  }

  @override
  bool isDone(double time) => time >= durationSeconds;
}

final class _PositionSample {
  const _PositionSample(this.pixels, this.timestamp);

  final double pixels;
  final Duration timestamp;
}
