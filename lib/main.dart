import 'package:flutter/widgets.dart';

import 'app/app.dart';
import 'core/backend/backend.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(QnzapretApp(runtime: createDefaultProxyRuntime()));
}
