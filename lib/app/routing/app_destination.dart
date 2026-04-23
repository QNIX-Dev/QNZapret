enum AppDestination { home, logs }

extension AppDestinationX on AppDestination {
  int get index => switch (this) {
    AppDestination.home => 0,
    AppDestination.logs => 1,
  };

  static AppDestination fromIndex(int value) {
    if (value <= 0) {
      return AppDestination.home;
    }

    return AppDestination.logs;
  }
}
