import 'package:flutter/material.dart';

final class AppSpacing {
  static const double xs = 8;
  static const double sm = 12;
  static const double md = 18;
  static const double lg = 24;
  static const double xl = 32;
  static const double xxl = 40;
}

final class AppRadii {
  static const double sm = 18;
  static const double md = 26;
  static const double lg = 34;
  static const double xl = 42;
  static const double pill = 999;
}

final class AppBreakpoints {
  static const double mobile = 600;
  static const double compact = 720;
  static const double medium = 1024;
  static const double wide = 1360;
  static const double contentMaxWidth = 1260;
  static const double settingsDialogMaxWidth = 1120;

  static bool isMobile(double width) => width < mobile;

  static bool isCompact(double width) => width < compact;

  static bool isMedium(double width) => width >= compact && width < medium;

  static bool isWide(double width) => width >= medium && width < wide;

  static bool isExpanded(double width) => width >= wide;

  static bool useSettingsPage(double width) => width < mobile;

  static double pagePadding(double width) {
    if (width < mobile) {
      return 18;
    }
    if (width < compact) {
      return 20;
    }
    if (width < medium) {
      return 24;
    }
    return 32;
  }
}

final class AppElevations {
  static List<BoxShadow> floating(Color color) {
    return [
      BoxShadow(
        color: color.withValues(alpha: 0.18),
        blurRadius: 42,
        offset: const Offset(0, 22),
      ),
      BoxShadow(
        color: Colors.black.withValues(alpha: 0.18),
        blurRadius: 18,
        offset: const Offset(0, 10),
      ),
    ];
  }

  static const List<BoxShadow> card = [
    BoxShadow(color: Color(0x14000000), blurRadius: 28, offset: Offset(0, 18)),
  ];
}
