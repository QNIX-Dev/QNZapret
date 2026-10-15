import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qnzapret/app/theme/app_theme.dart';
import 'package:qnzapret/core/backend/backend.dart';
import 'package:qnzapret/core/ui/app_backdrop.dart';
import 'package:qnzapret/core/ui/components/connected_flow_illustration.dart';

void main() {
  testWidgets('backdrop topology remains stable across rebuilds', (
    tester,
  ) async {
    final before = BackdropTopology.debugSignature;
    await _pumpAmbientScene(tester);
    await tester.pumpWidget(const SizedBox.shrink());
    await _pumpAmbientScene(tester);

    expect(BackdropTopology.debugSignature, before);
    expect(find.byKey(const ValueKey('ambient-network-backdrop')), findsOne);
  });

  testWidgets('connected flow painter advances in normal motion mode', (
    tester,
  ) async {
    await _pumpAmbientScene(tester);
    final paint = tester.widget<CustomPaint>(
      find.byKey(const ValueKey('connected-flow-paint')),
    );
    final painter = paint.painter! as ConnectedFlowPainter;
    final before = painter.debugPhase;

    await tester.pump(const Duration(seconds: 1));

    expect(painter.debugPhase, isNot(closeTo(before, 0.0001)));
  });

  testWidgets('reduced motion stops infinite decorative tickers', (
    tester,
  ) async {
    tester.platformDispatcher.accessibilityFeaturesTestValue =
        const FakeAccessibilityFeatures(disableAnimations: true);
    addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
    await _pumpAmbientScene(tester);
    final paint = tester.widget<CustomPaint>(
      find.byKey(const ValueKey('connected-flow-paint')),
    );
    final painter = paint.painter! as ConnectedFlowPainter;
    final before = painter.debugPhase;

    await tester.pump(const Duration(seconds: 2));

    expect(painter.debugPhase, closeTo(before, 0.0001));
    expect(tester.binding.hasScheduledFrame, isFalse);
  });

  testWidgets('TickerMode pauses connected flow on an invisible page', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.createTheme(
          paletteId: AppPaletteId.monokai,
          brightness: Brightness.dark,
        ),
        home: TickerMode(
          enabled: false,
          child: ConnectedFlowIllustration(
            snapshot: ProxyRuntimeSnapshot.initial(ProxyPlatform.linux),
          ),
        ),
      ),
    );
    await tester.pump();
    final paint = tester.widget<CustomPaint>(
      find.byKey(const ValueKey('connected-flow-paint')),
    );
    final painter = paint.painter! as ConnectedFlowPainter;
    final before = painter.debugPhase;

    await tester.pump(const Duration(seconds: 2));

    expect(painter.debugPhase, closeTo(before, 0.0001));
  });
}

Future<void> _pumpAmbientScene(WidgetTester tester) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.createTheme(
        paletteId: AppPaletteId.monokai,
        brightness: Brightness.dark,
      ),
      home: AppBackdrop(
        child: Center(
          child: SizedBox(
            width: 360,
            child: ConnectedFlowIllustration(
              snapshot: ProxyRuntimeSnapshot.initial(ProxyPlatform.linux),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pump();
}
