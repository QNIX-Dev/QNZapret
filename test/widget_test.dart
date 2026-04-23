import 'package:flutter_test/flutter_test.dart';
import 'package:qnzapret/app/app.dart';

void main() {
  testWidgets('renders project shell', (tester) async {
    await tester.pumpWidget(const QnzapretApp());

    expect(find.text('QNZapret'), findsOneWidget);
    expect(find.text('Runtime entry point'), findsOneWidget);
  });
}
