# Dependency notes

Versions are pinned in the Gradle files and Python requirements. Compose artifacts use BOM 2025.09.01. The app uses JDK 17, Gradle 8.13, AGP 8.13.2, and Kotlin 2.2.20. This compatible stable set is intentional; latest-version lint notices are reviewed rather than automatically upgraded.

| Component | Purpose | License or terms |
| --- | --- | --- |
| AndroidX Core, Activity, Lifecycle, Compose, Material 3 | Android UI and lifecycle | Apache 2.0, Android Open Source Project |
| Kotlin and kotlinx coroutines/serialization | Language, concurrency, local/wire encoding | Apache 2.0, JetBrains and contributors |
| Google Play services Nearby 19.3.0 | Verified encrypted nearby transport | [Google Play services terms](https://developers.google.com/android/guides/overview); diagnostics described in [Nearby notes](NEARBY.md) |
| OpenCV Android 4.13.0 | CPU detection, alignment, and recognition | Apache 2.0 plus bundled third-party notices |
| ZXing core 3.5.3 and JourneyApps embedded 4.3.0 | QR generation, image decoding, and camera scanning | Apache 2.0; copies in APK assets |
| JUnit 4.13.2 and AndroidX Test | Automated tests only | EPL 1.0 and Apache 2.0 respectively |
| NumPy 2.2.6 and opencv-python-headless 4.13.0.92 | Optional host model smoke test | BSD and Apache 2.0 plus upstream binary notices |

The model weights have separate licenses and checksums in [MODEL.md](MODEL.md). The NASA test fixture and attribution ship only in the test APK, never the normal app. Required OpenCV, model, and QR licenses are included in `app/src/main/assets/licenses/`. Original license text is preserved, including punctuation from third parties.

Nearby 19.5.1 requires newer Kotlin metadata than this pinned compiler. Version 19.3.0 provides the authentication, file URI, and transfer APIs used here and compiles with the chosen stack. SDK radio behavior may change through Play services updates; users must enable Wi-Fi and Bluetooth themselves.
