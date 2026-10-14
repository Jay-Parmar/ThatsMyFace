# Validation record

## Version 0.2.0, 14 October 2026

Application source: `19775d4`, on `feat/field-test-walkthrough`. [Draft PR #3](https://github.com/Jay-Parmar/ThatsMyFace/pull/3) depends on PR #2. No private photos, face references, or credentials are included in these results or the walkthrough.

| Check | Environment | Result |
| --- | --- | --- |
| Debug APK, test APK, lint | JDK 17, Gradle 8.13, AGP 8.13.2 | Passed |
| JVM behavior tests | Local and CI | 88 passed |
| Earlier full Android suite | Local API 29 and API 36 emulators, source `146ae3c` | 54 passed on each |
| Final build, model and Android checks | [CI run 38568829940](https://github.com/Jay-Parmar/ThatsMyFace/actions/runs/38568829940) | All four jobs passed; 58 Android tests each on API 29 and API 36, none failed or skipped |
| Final physical Android suite | Nothing A142 / Android 16, isolated test app | 58 passed |
| Large-text gallery behavior | 320dp width, 150% font, API 29 | Four interaction tests passed; layout visually reviewed |
| Host model execution | Bundled YuNet/SFace, licensed fixtures | Checksums, detection, alignment, embeddings, degraded and multi-face inputs passed |
| Production Nearby startup | A142, isolated app | Permission prompt, advertising/discovery startup and explicit stop passed |
| Production Nearby recovery | Two isolated API 36 emulators | Pairing, real recognition, rejection, persisted offline approval, owner retry, intact original, duplicate check and access revocation passed |
| Updated two-phone Nearby journey | A142 and replacement POCO / Android 14 | Blocked: POCO requires a SIM to enable USB installation and automated input |

The isolated debug option uses `com.thatsmyface.devicecheck`, with a separate Android UID, app data, grants, and test-provider authority. Test fixtures and both Check packages were removed from the Nothing phone afterward. The ordinary APK was rebuilt without this option and installed as an update. The existing Device test event still shows all ten selected photos, its selection survives restart, and sharing remains off. The POCO has the ordinary APK in Downloads for later manual installation.

## What the checks exercise

- Event membership and isolation, invitation validation, encrypted persistence, selected-event restoration, migration from older records, and local deletion.
- Exact system-picker grants, externally owned JPEGs with GPS metadata, folder traversal, missing/revoked/changed files, and original-byte preservation.
- Actual on-device model execution, EXIF orientation, alignment, conservative decisions, poor reference rejection, multi-face photos, confirmation, rejection, and corrections.
- Two real sharing controllers with encrypted stores and MediaStore copies, connected by a test-only transport. Cases include reciprocal readiness, handshake timeout, owner approval, corrupt payloads, interrupted requests, retry, duplicates, access revocation, cancellation, and failed acknowledgments. These tests do not verify radios.
- SDK task cancellation and late callbacks, without terminating the sharing event collector.
- Owner-initiated retry after offline approval, interruption, failed delivery, and corrupt bytes. A sender enqueue failure now fails both sides instead of leaving the receiver transferring. Terminal decisions and mismatched request identifiers cannot reopen a transfer.
- Search and filters across 120 photos, event isolation, opening a late tile without losing scroll, persistent approval while offline, and opening a new request even when the Received filter is selected.
- The Of me empty-state action runs the real model and persists a user-confirmed result after reopening encrypted storage.

Manual emulator checks also covered QR-image joining through Android's file picker, explicit licensed-photo import, and an actual local face suggestion through Of me. Walkthrough screenshots identify seeded gallery/transfer records separately from the real model result. Seeded received copies were created using the real save routine; they are not evidence of a radio transfer.

The Android 10 emulator reports Nearby code 8007 and has no `bluetooth_manager` service. It supports storage, model, and UI checks but cannot provide this run's Nearby radio test. No success is inferred from that failure.

The final production Nearby SDK run used two API 36 emulators with licensed synthetic/public fixtures. Matching verification digits preceded acceptance. The receiver obtained two previews, one actual face suggestion and one unmatched no-face image. A declined request saved nothing. A second request was approved while the receiver was disconnected. Its waiting state survived app update/restart, then an owner-side retry after fresh code verification delivered the original. Independent checks found all 791,555 original bytes, the expected SHA-256, and exactly one MediaStore copy in `Pictures/ThatsMyFace/Requested`. A later UI saved-copy check and process restart still found one intact copy. Removing future access ended the connection and removed the receiver's previews; its downloaded original remained intact. Test-owned media was then cleaned up. This tests the real SDK with virtual networking, not physical radio interoperability. The Nothing phone and emulator did not discover each other during a separate attempt.

## Earlier physical and virtual evidence

The user's A142 and Jay Ultra previously joined by QR, compared codes, exchanged previews, and completed an owner-approved original download confirmed by the user. The old UI buried approval actions and retained a stale disconnected endpoint card. That session preceded the current UI and control-message delivery changes. No private original was pulled from Jay Ultra, so its bytes were not independently measured.

On the A142, ten explicitly selected private photos imported after exact-URI and JPEG-alias fixes. Two reference selfies processed 22 detected faces, producing two suggestions and three uncertain results with no processing failure. A user-confirmed decision survived restart. Wrong/missed counts remain unreported; these are execution results, not an accuracy measurement. The photos and face data stayed on the phone.

At source `436b76d`, two isolated Android 16 emulators using the production Nearby SDK passed code verification, opted-in reference exchange, source-side recognition, approval/rejection, restart/retry, duplicate prevention, and future-access removal. An approved NASA fixture transfer preserved all 791,555 bytes and its SHA-256. Virtual radios do not establish physical interoperability or offline behavior.

## Artifact and remaining acceptance

Ordinary debug APK: `app/build/outputs/apk/debug/app-debug.apk`, version 0.2.0 (code 2).

SHA-256: `fd4a83e3c30d76074a31a87297a75e7fa38478d5c4fde4a917222b382ac41c6a`.

The [illustrated PDF](ThatsMyFace-Walkthrough.pdf) and [two-phone checklist](DEVICE_TESTS.md) cover the remaining physical run. Updated two-phone transfers, offline radio behavior, TalkBack, low-storage handling, and consenting friend-group low-light accuracy still require acceptance. A successful model fixture or controlled transport test does not establish those results.
