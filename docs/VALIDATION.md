# Validation record

## Run and passed

- Foundation staged snapshot: `assembleDebug lintDebug` using JDK 17, Gradle 8.13, AGP 8.13.2, Android SDK 36.
- Main-worktree build: `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` passed. Lint reported no errors; reviewed warnings include pinned dependency versions and guarded platform APIs.
- JVM suite: 64 tests passed, covering invitations, encryption, event access, integrity, recognition decisions, wire validation, transfer transitions, and retries.
- Host recognition smoke: bundled model checksums, real YuNet/SFace execution, alignment, 128-value embedding, recompressed same-face match, multiple faces, and no face.
- Foundation GitHub Actions: build, lint, and APK artifact passed after replacing the retired SDK `tools` package with explicit supported packages.
- Android 16 x86_64 emulator: all 21 instrumentation tests passed. Includes 5 storage tests, 4 actual-model/preprocessing tests, 8 paired-session tests, 2 application-flow tests, and 2 preview validation tests.
- Paired-session tests use two real controllers, encrypted stores, and MediaStore copies with a test-only connection. They cover approval, original integrity, duplicate prevention, reconnect/retry, corruption, readiness ordering, and failed setup cleanup. They do not test the Nearby radio implementation.
- Application-flow tests exercise selected-photo import, manual correction, actual enrollment and matching, feedback exclusion, missing-photo cleanup, and local deletion using an isolated key and test data.
- Recovery regression tests cover rapid stop/start, stale reference deletion, and local cancellation despite a failed notification.
- Emulator UI smoke passed at normal and 150% font size, including selected-event persistence, readable system bars, intact navigation labels, and empty states. TalkBack and full device accessibility acceptance remain on the device checklist.
- Physical Nothing A142 phone, Android 16: debug APK installation, onboarding, profile/event creation, persistence after force-stop/relaunch, and all 4 recognition instrumentation tests passed. Nearby permission approval and actual advertising/discovery startup passed. No second physical phone was available and no friends' photos were used.

## In progress

- Integrated GitHub CI for the reviewed application commits.

## Required before claiming v1 complete

- Two physical phones: nearby discovery, mutual code verification, consent, original transfer, disconnection/retry, and offline behavior.
- Consenting friend-group samples: low-light accuracy, wrong and ambiguous suggestions, manual corrections, and accessibility.

No physical two-phone transfer result is claimed here.
