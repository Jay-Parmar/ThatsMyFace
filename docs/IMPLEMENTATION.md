# Implementation checklist

## Completed
- Repository, remote, base branch, author identity, and GitHub authentication inspected.
- Persistent repository rules recorded.
- A: Native Android app, pinned build setup, navigation foundation, and passing GitHub CI. Draft PR #1.
- B: Encrypted local profiles/events, QR invitation encoding, authorized photo/folder import, and manual tagging implemented.
- C: Verified foreground Nearby transport, approval, intact original saving, cancellation, and persisted retries implemented. Paired controllers tested with real storage and a test-only connection, plus separate production Nearby SDK checks on two emulators.
- D: Face enrollment, source-side discovery, conservative suggestions, uncertain results, and manual corrections integrated.
- E: Privacy deletion, local feedback/export, readable dark UI, setup/privacy/model documentation, and two-phone checklist implemented.
- Real bundled YuNet/SFace detection, alignment, and embeddings execute on host, Android 16 emulator, and physical Android 16 phone.
- Local persistence, original byte preservation, duplicate saves, revoked provider access, and cancelled save tested on emulator.
- Local build/lint, 67 JVM tests, and 24 Android instrumentation tests passed. Earlier host inference and normal/150% font UI checks passed.
- Integrated GitHub build, model, and emulator jobs passed after the recovery fixes. The earlier APK installed and launched on the available physical phone, with working Nearby startup.
- Production Nearby SDK on two isolated API 36 emulators passed pairing, opt-in reference exchange, source-side recognition, approval/rejection, original byte integrity, persisted waiting state, reconnect/retry, and future-access removal.
- Saved-copy checks distinguish intact, missing, changed, and unreadable copies. Missing copies require a fresh request and owner approval; an intact copy does not produce another download.

## Pending
- Consenting real-photo check on the available phone is awaiting the user's selected photos. Sharing remains off for that check.
- Physical acceptance and low-light recognition evaluation remain as listed below.

## Blocked
- Two-physical-phone acceptance requires a second available Android phone. One physical phone and two isolated emulators are available.
- Friend-group accuracy and all-night field testing require consenting participants.

Implementation is on `feat/core-sharing` in draft PR #2, stacked on `feat/local-events` in draft PR #1. This is not yet a completed, physically validated v1.
