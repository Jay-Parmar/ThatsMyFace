# Validation record

## Gallery and request update

- Source `d68ba38`: debug APK/test APK builds and lint passed; all 86 JVM tests passed, including 12 control-message delivery/cancellation regressions.
- All 46 Android tests passed on a local API 29 emulator at 320 by 640: 14 storage, 17 paired-session, 8 recognition, 2 application-flow, 2 gallery interaction, 2 preview, and 1 real picker test. The gallery test exercises 120 lazy items, selection and retained position; the approval test checks persisted approval while the friend is offline. An initial viewport assumption failed with an old density override; the test now uses an explicit phone-sized viewport.
- A temporary emulator-only fixture exercised the production UI with 120 selected references, 120 offers, waiting requests, and four actual MediaStore saved copies. Grid, detail, and approval controls were visually checked at normal size and at 320dp with 150% font. Exact fixture cleanup passed; no fixture seeder ships in the source or APK.
- Storage checks verify the Requested destination, legacy-folder recovery, original bytes, duplicate prevention, protected user edits, and saved thumbnails after source deletion. A new session test verifies declining an unapproved request after disconnection.
- The APK was installed on the A142 without clearing data. Device test and its saved photos remain available; sharing startup and its keep-screen-on window flag were checked. The updated transport still needs a repeated two-phone pairing/download check. Earlier physical results below used the preceding APK.

## Run and passed

- Foundation staged snapshot: `assembleDebug lintDebug` using JDK 17, Gradle 8.13, AGP 8.13.2, Android SDK 36.
- Main-worktree build: `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` passed. Lint reported no errors; reviewed warnings include pinned dependency versions and guarded platform APIs.
- JVM suite: 74 tests passed, covering invitations, encryption, event access, integrity, recognition thresholds and ambiguity, preprocessing dimensions, wire validation, transfer transitions, retries, saved-copy admission, and compatibility with older persisted transfers.
- Host recognition smoke: bundled model checksums, real YuNet/SFace execution, alignment, 128-value embedding, recompressed same-face match, multiple faces, and no face.
- Foundation GitHub Actions: build, lint, and APK artifact passed after replacing the retired SDK `tools` package with explicit supported packages.
- Integrated [GitHub Actions run 37963727072](https://github.com/Jay-Parmar/ThatsMyFace/actions/runs/37963727072), source commit `ea08a30`, passed all four jobs: build/lint/JVM checks, host model execution, and all 41 instrumentation tests on API 29 and API 36. This includes the real system-picker regression and Android 10/11 compatibility fix. The debug APK and test reports are attached to the run.
- Android 10 / API 29 local emulator and Android 16 / API 36 CI emulator: all 42 instrumentation tests passed on each. Includes 13 storage tests, 8 actual-model/preprocessing tests, 16 paired-session tests, 2 application-flow tests, 2 preview validation tests, and 1 real system-picker regression. The API 29 local run used CI's 320 by 640 screen size. Run with `adb -s DEVICE_SERIAL shell am instrument -w com.thatsmyface.test/androidx.test.runner.AndroidJUnitRunner` after installing the debug and test APKs on a test installation.
- The API 29 emulator also passed all 8 model tests with Wi-Fi and cellular data disabled and no active default network. This verifies offline model execution, not offline Nearby pairing.
- Recognition tests cover EXIF orientations 2 through 8, resized color-correct decoding, small/dark/blurred/washed-out faces, and recovery after an invalid image. Storage tests cover interrupted atomic writes, concurrent updates/saves, missing encryption keys, changed sources, and pending MediaStore saves.
- Peer revocation tests exposed and verified a fix for prepared outgoing files left in cache. Local and remote revocation now cancel and release payloads even after persisted revocation clears their handles. Late readiness and file delivery cannot complete cancelled requests.
- The real system picker grants access to a shell-created synthetic JPEG containing GPS metadata. The test checks external ownership, denied access before selection, exact original bytes and GPS after import/snapshot, previews, duplicate prevention, and blocked sharing after grant revocation. It exposed Android 10/11 routing media-document conversion to the wrong provider; those versions now retain the selected document URI. Fixture setup and picker navigation were corrected before passing. A System UI hang on the local API 36 emulator required recovery; no application success is inferred from that failed setup run.
- A further folder regression covers nested `image/jpg` aliases, exclusion of non-images, selected-tree traversal, and original-byte preservation. The 13 storage tests plus the system-picker test passed together on API 29 and API 36. An earlier local combined run failed the immediate revocation check. The fixture now waits for picker-host destruction and explicitly verifies that Android removed both document and mapped grants before asserting denied reads and blocked sharing. This closes a possible activity-owned grant race without relaxing the application checks.
- Picker navigation waits for initial root loading and scrolls offscreen drawer entries. This addresses an API 29 CI navigation timeout before selection; original-byte and revocation assertions remain intact.
- Paired-session tests use two real controllers, encrypted stores, and MediaStore copies with a test-only connection. They cover approval, original integrity, duplicate prevention, reconnect/retry, corruption, readiness ordering, and failed setup cleanup. They do not test the Nearby radio implementation.
- Application-flow tests exercise selected-photo import, manual correction, actual enrollment and matching, feedback exclusion, missing-photo cleanup, and local deletion using an isolated key and test data.
- Recovery regression tests cover rapid stop/start, stale reference deletion, and local cancellation despite a failed notification.
- Saved-copy regressions cover missing, changed, and denied copies, fresh approval after deletion, intact/restored-copy duplicate prevention, and unchanged source bytes.
- Emulator UI smoke passed at normal and 150% font size, including selected-event persistence, readable system bars, intact navigation labels, and empty states. TalkBack and full device accessibility acceptance remain on the device checklist.
- Actual Nearby advertising/discovery startup and explicit session stop also passed on the Android 16 emulator after granting the app's requested permission. No radio pairing or original transfer was attempted in that smoke check.
- Physical Nothing A142 phone, Android 16: debug APK installation, onboarding, profile/event creation, persistence after force-stop/relaunch, and all 4 recognition instrumentation tests passed. Nearby permission approval and actual advertising/discovery startup passed. No second physical phone was available and no friends' photos were used.
- The earlier debug APK was reinstalled on that phone and launched successfully with the saved profile/event intact. Actual Nearby startup passed again; the session was stopped after testing. These phone checks preceded the saved-copy recovery changes; the latest APK has been checked on emulators.

## Selected photos on the physical phone

The updated APK was installed on the Nothing A142 without clearing its profile or references. Ten explicitly selected photos initially failed because `setRequireOriginal` changed the exact granted URI. Aggregate on-phone diagnostics confirmed the original selected and mapped URIs were readable, while their query-modified forms were denied. Two files also used the `image/jpg` MIME alias.

After both fixes, all ten photos imported, their imported digests matched the selected-provider bytes, and all ten previews loaded. Local recognition using two saved reference selfies checked 22 faces, produced 2 suggested photos and 3 uncertain photos, and reported no processing failures. Sharing stayed off. After the user's review, a confirmed match remained confirmed after force-stop and relaunch. Counts of wrong or missed suggestions are still unreported, so these results are not an accuracy measurement. No photos, names, URIs, embeddings, or private screenshots were copied into the repository or test reports. Temporary diagnostic instrumentation was removed from the phone.

## A142 and Jay Ultra

Both physical phones joined the same event using the A142 invitation and completed the code check. The A142 displayed a verified live connection; the user confirmed its selected previews appeared on Jay Ultra. Two original requests reached the A142 and still awaited owner approval. After the user approved a request, they confirmed the original downloaded on Jay Ultra. No private file was pulled from either phone, and byte integrity was not independently measured on Jay Ultra.

The same session exposed a stale disconnected endpoint card beside the live connection, and approval controls buried in Downloads. Physical offline operation, interruption recovery, and duplicate prevention still need acceptance checks.

## Production Nearby SDK on virtual devices

Two isolated Android 16 / API 36 emulators used Android Emulator 37.1.11 and Google Play services 25.26.35 (260800-783060121). These checks used the production SDK and virtual radios, without the test-only transport. The tested APK's source is commit `436b76d`, including the invitation-input and saved-copy recovery fixes.

- Both joined the same event through the QR image picker. Only the licensed NASA fixture was selected as a source photo or enrollment reference.
- Discovery, matching verification digits, and acceptance on both devices passed. Opt-in references produced a source-side recognition suggestion on the other device without a manual tag.
- A declined request saved no original. After explicit owner approval, a FILE transfer saved exactly one copy. Independent comparison confirmed all 791,555 bytes and SHA-256 matched the licensed source, which remained unchanged.
- Checking the intact saved copy retained one file. Removing only that received copy through MediaStore was detected and required fresh owner approval to replace it.
- Force-stopping the owner while approval was pending changed the receiver to Waiting. Waiting survived receiver restart. Both rediscovered and verified each other, then retry and fresh approval delivered one intact replacement.
- Removing future access disconnected both devices and showed access removed. It did not recall the downloaded copy.

This establishes production SDK behavior over virtual networking, not physical radio interoperability, performance, or offline operation. No private phone media was used in these checks.

## Artifact

Local APK: `app/build/outputs/apk/debug/app-debug.apk`. The verified APK SHA-256 is `e7c0c29b69b42dfd154d21c1e48a1b7a18d3bb62b3ce453435b28506c0bd8ac6`. Rebuilding can produce a different debug artifact.

## Required before claiming v1 complete

- Two physical phones: nearby discovery, mutual code verification, consent, original transfer, disconnection/retry, and offline behavior.
- Consenting friend-group samples: low-light accuracy, wrong and ambiguous suggestions, manual corrections, and accessibility.

One user-confirmed physical download is recorded above. Full physical acceptance is still pending.
