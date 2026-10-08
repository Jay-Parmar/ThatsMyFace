# Implementation checklist

## Completed
- Repository, remote, base branch, author identity, and GitHub authentication inspected.
- Persistent repository rules recorded.
- A: Native Android app, pinned build setup, navigation foundation, and passing GitHub CI. Draft PR #1.
- B: Encrypted local profiles/events, QR invitation encoding, authorized photo/folder import, and manual tagging implemented.
- C: Verified foreground Nearby transport, approval, intact original saving, cancellation, and persisted retries implemented. Paired controllers tested with real storage and a test-only connection.
- D: Face enrollment, source-side discovery, conservative suggestions, uncertain results, and manual corrections integrated.
- E: Privacy deletion, local feedback/export, readable dark UI, setup/privacy/model documentation, and two-phone checklist implemented.
- Real bundled YuNet/SFace detection, alignment, and embeddings execute on host, Android 16 emulator, and physical Android 16 phone.
- Local persistence, original byte preservation, duplicate saves, revoked provider access, and cancelled save tested on emulator.
- Final local build/lint, 64 JVM tests, 21 emulator tests, host inference, and normal/150% font UI checks passed.

## Pending
- Integrated GitHub CI and installation of the final APK on the available phone.

## Blocked
- Two-physical-phone acceptance requires a second available Android phone. One physical phone and one emulator are available.
- Friend-group accuracy and all-night field testing require consenting participants.

Implementation continues on `feat/core-sharing`, stacked on `feat/local-events`. This is not yet a completed, physically validated v1.
