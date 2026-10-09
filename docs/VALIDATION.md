# Validation record

## Run and passed

- Foundation staged snapshot: `assembleDebug lintDebug` using JDK 17, Gradle 8.13, AGP 8.13.2, Android SDK 36.
- Main-worktree build: `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` passed. Lint reported no errors; reviewed warnings include pinned dependency versions and guarded platform APIs.
- JVM suite: 74 tests passed, covering invitations, encryption, event access, integrity, recognition thresholds and ambiguity, preprocessing dimensions, wire validation, transfer transitions, retries, saved-copy admission, and compatibility with older persisted transfers.
- Host recognition smoke: bundled model checksums, real YuNet/SFace execution, alignment, 128-value embedding, recompressed same-face match, multiple faces, and no face.
- Foundation GitHub Actions: build, lint, and APK artifact passed after replacing the retired SDK `tools` package with explicit supported packages.
- Integrated [GitHub Actions run 37963727072](https://github.com/Jay-Parmar/ThatsMyFace/actions/runs/37963727072), source commit `ea08a30`, passed all four jobs: build/lint/JVM checks, host model execution, and all 41 instrumentation tests on API 29 and API 36. This includes the real system-picker regression and Android 10/11 compatibility fix. The debug APK and test reports are attached to the run.
- Android 10 / API 29 and Android 16 / API 36 x86_64 emulators: all 41 instrumentation tests passed on each. Includes 12 storage tests, 8 actual-model/preprocessing tests, 16 paired-session tests, 2 application-flow tests, 2 preview validation tests, and 1 real system-picker regression. Run with `adb -s DEVICE_SERIAL shell am instrument -w com.thatsmyface.test/androidx.test.runner.AndroidJUnitRunner` after installing the debug and test APKs on a test installation.
- The API 29 emulator also passed all 8 model tests with Wi-Fi and cellular data disabled and no active default network. This verifies offline model execution, not offline Nearby pairing.
- Recognition tests cover EXIF orientations 2 through 8, resized color-correct decoding, small/dark/blurred/washed-out faces, and recovery after an invalid image. Storage tests cover interrupted atomic writes, concurrent updates/saves, missing encryption keys, changed sources, and pending MediaStore saves.
- Peer revocation tests exposed and verified a fix for prepared outgoing files left in cache. Local and remote revocation now cancel and release payloads even after persisted revocation clears their handles. Late readiness and file delivery cannot complete cancelled requests.
- The real system picker grants access to a shell-created synthetic JPEG containing GPS metadata. The test checks external ownership, denied access before selection, exact original bytes and GPS after import/snapshot, previews, duplicate prevention, and blocked sharing after grant revocation. It exposed Android 10/11 routing media-document conversion to the wrong provider; those versions now retain the selected document URI. Fixture setup and picker navigation were corrected before passing. A System UI hang on the local API 36 emulator required recovery; no application success is inferred from that failed setup run.
- A further folder regression covers nested `image/jpg` aliases, exclusion of non-images, selected-tree traversal, and original-byte preservation. The 13 storage tests plus the system-picker test passed together on API 36. An earlier combined local run failed the immediate revocation check; standalone and repeated combined runs passed, so that intermittent observation remains under investigation.
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

Local APK: `app/build/outputs/apk/debug/app-debug.apk`. The verified APK SHA-256 is `3b1107c0b4f87b9d05a012db9e02df8611c2e3dd0902805e3c4051a9da26df80`. Rebuilding can produce a different debug artifact.

## Required before claiming v1 complete

- Two physical phones: nearby discovery, mutual code verification, consent, original transfer, disconnection/retry, and offline behavior.
- Consenting friend-group samples: low-light accuracy, wrong and ambiguous suggestions, manual corrections, and accessibility.

No physical two-phone transfer result is claimed here.
