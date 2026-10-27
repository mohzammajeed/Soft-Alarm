# SoftAlarm

A gentle alarm clock for Android: voices instead of beeps (12 voices x English, Punjabi, Urdu, Hindi), timer, stopwatch.
Alarms ring with the screen off, on the lock screen, and after a restart.

## Folders
- `app/`                 the Android app (Kotlin). Voice mp3 files go in `app/src/main/assets/audio/` (see README.txt there).
- `backend-stub/`        the small payment + account server (Node.js). Only needed when you sell Pro voices.
- `store/`               store icon, feature graphic and listing text.
- `.github/workflows/`   builds the APK, runs the tests, and builds signed releases when you push a tag like v1.0.0.

## Guides
- `FINAL_CHECKLIST.md`   what is left for you to do, in order. START HERE.
- `PLUGIN_POINTS.md`     what is fake now and where the real Firebase / Polar pieces plug in.
- `PAYMENTS_SETUP.md`    Polar + Firebase server setup.   `FIREBASE_SETUP.md`  Firebase console steps.
- `STORE_READINESS.md`   signing key, release, store rules.

## Tests
GitHub runs them on every push: app unit tests (Kotlin) and server tests (Node).
