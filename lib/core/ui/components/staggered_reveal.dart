import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';

import '../../motion/app_motion.dart';

class StaggeredReveal extends StatefulWidget {
  const StaggeredReveal({
    required this.child,
    super.key,
    this.delay = Duration.zero,
    this.beginOffset = const Offset(0, 0.06),
    this.visitToken = 0,
    this.minVisibleFraction = 0.24,
  });

  final Widget child;
  final Duration delay;
  final Offset beginOffset;
  final int visitToken;
  final double minVisibleFraction;

  @override
  State<StaggeredReveal> createState() => _StaggeredRevealState();
}

class _StaggeredRevealState extends State<StaggeredReveal>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: AppMotionDurations.page,
  );

  ScrollPosition? _scrollPosition;
  bool _revealed = false;
  bool _scheduled = false;
  int _scheduleVersion = 0;

  Animation<double> get _opacity =>
      CurvedAnimation(parent: _controller, curve: AppMotionCurves.decelerate);

  Animation<Offset> get _slide =>
      Tween<Offset>(begin: widget.beginOffset, end: Offset.zero).animate(
        CurvedAnimation(parent: _controller, curve: AppMotionCurves.decelerate),
      );

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      _attachScrollListener();
      _evaluateVisibility();
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      _attachScrollListener();
      _evaluateVisibility();
    });
  }

  @override
  void didUpdateWidget(covariant StaggeredReveal oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.visitToken != widget.visitToken) {
      _resetReveal();
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) {
          return;
        }
        _attachScrollListener();
        _evaluateVisibility();
      });
    }
  }

  @override
  void dispose() {
    _scrollPosition?.removeListener(_evaluateVisibility);
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FadeTransition(
      opacity: _opacity,
      child: SlideTransition(position: _slide, child: widget.child),
    );
  }

  void _attachScrollListener() {
    final nextScrollPosition = Scrollable.maybeOf(context)?.position;
    if (identical(nextScrollPosition, _scrollPosition)) {
      return;
    }

    _scrollPosition?.removeListener(_evaluateVisibility);
    _scrollPosition = nextScrollPosition;
    _scrollPosition?.addListener(_evaluateVisibility);
  }

  void _evaluateVisibility() {
    if (_revealed || _scheduled || !mounted) {
      return;
    }

    final renderObject = context.findRenderObject();
    if (renderObject is! RenderBox || !renderObject.hasSize) {
      return;
    }

    final scrollPosition = _scrollPosition;
    final viewport = RenderAbstractViewport.maybeOf(renderObject);
    if (scrollPosition == null || viewport == null) {
      _scheduleReveal();
      return;
    }

    var leadingOffset = viewport.getOffsetToReveal(renderObject, 0).offset;
    var trailingOffset = viewport.getOffsetToReveal(renderObject, 1).offset;
    if (trailingOffset < leadingOffset) {
      final swap = leadingOffset;
      leadingOffset = trailingOffset;
      trailingOffset = swap;
    }

    final viewportStart = scrollPosition.pixels;
    final viewportEnd = viewportStart + scrollPosition.viewportDimension;
    final visibleExtent =
        math.min(viewportEnd, trailingOffset) -
        math.max(viewportStart, leadingOffset);
    final totalExtent = math.max(trailingOffset - leadingOffset, 1);
    final visibleFraction = (visibleExtent / totalExtent).clamp(0, 1);

    if (visibleFraction >= widget.minVisibleFraction) {
      _scheduleReveal();
    }
  }

  void _scheduleReveal() {
    if (_revealed || _scheduled) {
      return;
    }

    _scheduled = true;
    final version = ++_scheduleVersion;
    Future<void>.delayed(widget.delay).then((_) {
      if (!mounted || _revealed || version != _scheduleVersion) {
        return;
      }

      _revealed = true;
      _scheduled = false;
      _controller.forward(from: 0);
    });
  }

  void _resetReveal() {
    _scheduleVersion += 1;
    _revealed = false;
    _scheduled = false;
    _controller.value = 0;
  }
}
