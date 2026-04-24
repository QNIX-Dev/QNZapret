import 'package:flutter_test/flutter_test.dart';
import 'package:qnzapret/app/app.dart';
import 'package:qnzapret/core/backend/backend.dart';

void main() {
  testWidgets('renders project shell', (tester) async {
    await tester.pumpWidget(
      const QnzapretApp(runtime: StubProxyRuntime(ProxyPlatform.android)),
    );
    await tester.pump();

    expect(find.text('QNZapret'), findsOneWidget);
    expect(find.text('Runtime entry point'), findsOneWidget);
  });
}
