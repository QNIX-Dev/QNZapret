import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qnzapret/core/motion/app_scroll_behavior.dart';
import 'package:qnzapret/core/motion/app_scroll_motion.dart';
import 'package:qnzapret/core/motion/app_smooth_scroll.dart';

void main() {
  testWidgets('desktop wheel moves gradually and accumulates input', (
    tester,
  ) async {
    final controller = AppScrollController();
    addTearDown(controller.dispose);
    await _pumpScrollHarness(tester, controller: controller);

    await _sendWheel(tester, 96);
    expect(controller.offset, closeTo(0, 0.001));

    await tester.pump();
    await tester.pump(const Duration(milliseconds: 24));
    final firstFrameOffset = controller.offset;
    expect(firstFrameOffset, greaterThan(0));
    expect(firstFrameOffset, lessThan(96));

    await _sendWheel(tester, 96);
    expect(controller.offset, closeTo(firstFrameOffset, 0.001));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 120));

    expect(controller.offset, greaterThan(96));
    expect(controller.offset, lessThan(192));
  });

  testWidgets('wheel overscroll is bounded and springs back at both edges', (
    tester,
  ) async {
    final controller = AppScrollController();
    addTearDown(controller.dispose);
    await _pumpScrollHarness(tester, controller: controller);
    final position = controller.position as AppSmoothScrollPosition;

    await _sendWheel(tester, -120);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 220));
    expect(position.pixels, lessThan(position.contentMinScrollExtent));
    expect(
      position.contentMinScrollExtent - position.pixels,
      lessThanOrEqualTo(68),
    );
    await tester.pumpAndSettle();
    expect(position.pixels, closeTo(position.contentMinScrollExtent, 0.01));

    position.jumpTo(position.contentMaxScrollExtent);
    await tester.pump();
    await _sendWheel(tester, 120);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 220));
    expect(position.pixels, greaterThan(position.contentMaxScrollExtent));
    expect(
      position.pixels - position.contentMaxScrollExtent,
      lessThanOrEqualTo(68),
    );
    await tester.pumpAndSettle();
    expect(position.pixels, closeTo(position.contentMaxScrollExtent, 0.01));
  });

  testWidgets('reduced motion leaves user wheel scrolling functional', (
    tester,
  ) async {
    final controller = AppScrollController();
    addTearDown(controller.dispose);
    await _pumpScrollHarness(
      tester,
      controller: controller,
      disableAnimations: true,
    );

    await _sendWheel(tester, 80);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 80));
    expect(controller.offset, greaterThan(0));
  });

  testWidgets('nested scrollable under pointer exclusively handles wheel', (
    tester,
  ) async {
    final outer = AppScrollController();
    final inner = AppScrollController();
    addTearDown(outer.dispose);
    addTearDown(inner.dispose);

    await tester.pumpWidget(
      MaterialApp(
        scrollBehavior: const AppScrollBehavior(),
        home: Scaffold(
          body: SizedBox(
            height: 360,
            child: AppSmoothSingleChildScrollView(
              controller: outer,
              child: Column(
                children: [
                  const SizedBox(height: 120),
                  SizedBox(
                    key: const ValueKey('inner-scroll'),
                    height: 180,
                    child: ListView.builder(
                      controller: inner,
                      physics: appVerticalScrollPhysics,
                      itemExtent: 48,
                      itemCount: 20,
                      itemBuilder: (context, index) => Text('Строка $index'),
                    ),
                  ),
                  const SizedBox(height: 700),
                ],
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pump();

    final pointer = TestPointer(2, PointerDeviceKind.mouse)
      ..hover(tester.getCenter(find.byKey(const ValueKey('inner-scroll'))));
    await tester.sendEventToBinding(pointer.scroll(const Offset(0, 96)));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(inner.offset, greaterThan(0));
    expect(outer.offset, closeTo(0, 0.001));
  });

  testWidgets('trackpad pan stays directly coupled to the gesture', (
    tester,
  ) async {
    final controller = AppScrollController();
    addTearDown(controller.dispose);
    await _pumpScrollHarness(tester, controller: controller);
    final location = tester.getCenter(
      find.byKey(const ValueKey('scroll-host')),
    );
    final trackpad = await tester.createGesture(
      kind: PointerDeviceKind.trackpad,
    );

    await trackpad.panZoomStart(location);
    await trackpad.panZoomUpdate(location, pan: const Offset(0, -42));
    await tester.pump();

    expect(controller.offset, closeTo(42, 1));

    await trackpad.panZoomEnd();
    await tester.pump(const Duration(milliseconds: 300));
    expect(controller.offset, closeTo(42, 2));
  });

  test('controller and motion signal dispose cleanly', () {
    final signal = AppScrollMotionSignal();
    final controller = AppScrollController(motionSignal: signal);

    controller.dispose();
    signal.dispose();

    expect(controller.debugDisposed, isTrue);
    expect(signal.debugDisposed, isTrue);
  });
}

Future<void> _pumpScrollHarness(
  WidgetTester tester, {
  required AppScrollController controller,
  bool disableAnimations = false,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      scrollBehavior: const AppScrollBehavior(),
      home: MediaQuery(
        data: MediaQueryData(disableAnimations: disableAnimations),
        child: Scaffold(
          body: SizedBox(
            key: const ValueKey('scroll-host'),
            height: 280,
            child: AppSmoothSingleChildScrollView(
              controller: controller,
              child: const ColoredBox(
                color: Colors.transparent,
                child: SizedBox(height: 1400, width: double.infinity),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pump();
}

Future<void> _sendWheel(WidgetTester tester, double delta) {
  final pointer = TestPointer(1, PointerDeviceKind.mouse)
    ..hover(tester.getCenter(find.byKey(const ValueKey('scroll-host'))));
  return tester.sendEventToBinding(pointer.scroll(Offset(0, delta)));
}
