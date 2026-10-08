# ThatsMyFace

**If you're in it, find it.**

An Android app for a small trusted group sharing a night out, trip, or party. Select event photos, verify nearby friends, find suggestions using on-device face matching, and approve original-photo requests. No account or app backend.

## Build and install

Requires JDK 17, Android SDK platform 36, build tools 35.0.0, and an Android 10+ device. Use Android Studio or set `ANDROID_HOME` to your SDK. Dependencies need internet for the first build. Recognition weights are already bundled.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat connectedDebugAndroidTest
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

On macOS/Linux use `./gradlew`. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Connected tests use synthetic images and a licensed public-domain fixture, and reset this app's test state. Do not run them against an app installation with data you want to keep.

## Try it

1. Choose a nickname. Create an event and share its QR invitation with friends.
2. Select photos or an event folder. Folder rescans happen only when requested. Verified event friends may see previews; originals always need approval.
3. Open Friends on both phones, enable Wi-Fi and Bluetooth, and start sharing. Compare the pairing digits in person before confirming on both phones.
4. Optionally select one to five clear reference selfies in You. Opt in to share face references for the event. Recognition runs on the phone holding the photos.
5. Review Photos of me. Confirm, reject, or manually correct suggestions, then request originals. The owner approves in Downloads.
6. Received originals appear in `Pictures/ThatsMyFace`. Keep both apps open until they finish. Reconnect and retry interrupted requests.

Sharing requires compatible Google Play services. Installed models run offline, and Nearby supports offline peer transport, but SDK diagnostics and system services may use the network. We do not claim the entire phone produces no network traffic.

See [privacy and data flow](docs/PRIVACY.md), [model provenance](docs/MODEL.md), [Nearby details](docs/NEARBY.md), [two-phone checklist](docs/DEVICE_TESTS.md), and [implementation status](docs/IMPLEMENTATION.md). Test results and remaining limits are recorded in [validation](docs/VALIDATION.md).

## Boundaries

This is a foreground, small-group app. A disconnected phone cannot supply originals. Removing access stops future sharing and cannot recall downloaded copies. Recognition can be wrong, especially in low light, and never authenticates a person. This is not a completed, physically validated v1 until the remaining device checklist passes.

Later: photo debt, favorite shots, burst selection, video, iOS, and remote internet transfers.
