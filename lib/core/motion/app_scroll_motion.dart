import 'package:flutter/widgets.dart';

/// Paint-only signal shared by scrollable surfaces and the ambient backdrop.
final class AppScrollMotionSignal extends ChangeNotifier {
  double _offset = 0;
  double _velocity = 0;
  int _direction = 0;
  Duration _lastUpdate = Duration.zero;
  bool _disposed = false;

  double get offset => _offset;
  double get velocity => _velocity;
  int get direction => _direction;
  Duration get lastUpdate => _lastUpdate;

  @visibleForTesting
  bool get debugDisposed => _disposed;

  void record({
    required double normalizedOffset,
    required double normalizedVelocity,
    required Duration timestamp,
  }) {
    if (_disposed) {
      return;
    }
    _offset = normalizedOffset.clamp(-0.12, 1.12);
    _velocity = normalizedVelocity.clamp(-1.0, 1.0);
    if (_velocity.abs() > 0.002) {
      _direction = _velocity.sign.toInt();
    }
    _lastUpdate = timestamp;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}

class AppScrollMotionScope extends InheritedWidget {
  const AppScrollMotionScope({
    required this.signal,
    required super.child,
    super.key,
  });

  final AppScrollMotionSignal signal;

  static AppScrollMotionSignal? maybeOf(BuildContext context) {
    return context
        .dependOnInheritedWidgetOfExactType<AppScrollMotionScope>()
        ?.signal;
  }

  @override
  bool updateShouldNotify(AppScrollMotionScope oldWidget) {
    return oldWidget.signal != signal;
  }
}
