# QNZapret

Flutter frontend shell for the QNZapret client.

## Current scope

- Root Flutter app initialized for `android`, `linux`, and `windows`
- `.sources/` kept as local research and backend references, ignored by Git
- Initial product-facing home screen instead of the default counter demo
- Reserved integration layer for future native Go backend bridges

## Project structure

```text
lib/
  app/
    app.dart
    theme/
  core/
    backend/
  features/
    home/
```

## Run

```bash
flutter run -d linux
flutter run -d windows
flutter run -d android
```

## Next step

Wire platform adapters from `lib/core/backend/` to the native runtime entrypoints for Android first, then design equivalent desktop bridges for Linux and Windows.
