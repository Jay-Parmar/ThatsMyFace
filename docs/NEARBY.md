# Nearby sharing

Sharing runs only during an intentional foreground session. Both phones need compatible Google Play services, Bluetooth, Wi-Fi, nearby permissions, and the same event invitation. Internet is not needed for the peer transfer after required software is installed. A disconnected phone cannot supply its originals.

## Pairing and access

The app uses Google Nearby Connections with the cluster strategy. Both people compare the transport's verification code and explicitly accept. An accepted connection exchanges an event-secret HMAC bound to the event, sender identity, nickname, and current verification code. Each phone persists and admits the peer, then sends an `EventReady` acknowledgment. Private exchange starts after both acknowledgments, so one phone's slower storage cannot race another phone's catalog. A face match never opens access.

Nicknames and event-specific service identifiers are advertised nearby. Do not put sensitive details in nicknames. Invitations contain an event secret, so show them only to intended friends. A shared invitation is not individual identity authentication. Compare the code with the actual friend on every new connection.

Messages use bounded JSON and event-scoped photo identifiers, never filesystem paths. Unsupported fields and message types, invalid identifiers, non-finite or unnormalized face references, excessive sizes, and another event's messages are rejected. Photo offers and their small previews are available only to verified event peers under the owner's selected sharing policy. Face references require a separate opt-in.

A catalog refresh starts with `Catalog(reset = true)` to discard stale offers, including previews of removed source photos. An empty reset clears all offers. Following catalog messages update individual offers. The application serializes refreshes per peer and invalidates older work when photos, reference sharing, or event access changes. Nearby guarantees ordering for payloads of the same type, so these byte messages arrive in order. `Cancel` binds cancellation to the event, request, and photo, so it can be applied to persisted transfer state as well as the current payload.

## Original file handshake

1. Receiver sends a stable request ID for a scoped photo ID.
2. Owner checks membership, selected-photo access, and explicit original approval.
3. Owner prepares the original file and sends `FileReady` with request ID, payload ID, exact size, MIME type, and SHA-256.
4. Receiver checks its persisted request, registers the expected payload, and acknowledges `Ready`.
5. Owner sends the file only after this acknowledgment. Byte metadata and file payload ordering is not assumed.
6. Nearby reports progress and supplies the received file URI after success. The app verifies exact size and SHA-256 before committing its MediaStore copy and sending a receipt.

The adapter rejects unrequested files, cancels files exceeding the approved size, and releases transfer state when disconnected. Application persistence owns approval, retries, integrity checks, and duplicate prevention. SDK success means delivery only, not verified application completion. Received temporary files use the SDK-provided content URI. Cleanup never targets a selected source URI. Partial retries restart the original transfer; there is no claim of byte-range continuation.

Checking a saved copy verifies its current size and SHA-256 without downloading again. A confirmed missing copy can be requested again with fresh owner approval. Changed or unreadable copies block another download: keep changed files safe and restore or remove them yourself, or restore access to unreadable storage, then check again. The app does not overwrite or delete these copies automatically.

Google documents FILE receive staging in Downloads. The app removes staging URIs after successful import or cancellation, but a sudden process exit can leave a temporary SDK file. Check Downloads after a forced-stop test. The app does not promise that SDK staging is private app storage or encrypted at rest.

## Platform and privacy limits

The pinned dependency is `com.google.android.gms:play-services-nearby:19.3.0`. Version 19.5.1 requires newer Kotlin metadata than this project's pinned compiler, so the app uses the compatible stable release. Nearby encrypts device connections, with its supported code comparison used to authenticate the intended peer. This app adds no analytics, backend, or photo upload service. Google Play services Nearby itself collects discovery/connection performance metrics and device information including model, country, build version, and app package. Google documents a device-level control under **Settings > Google > Usage & diagnostics**. Therefore this app does not promise that the operating system or SDK never communicates with Google.

Permission requests are feature-specific. Android 10 through 12L needs location permissions for nearby discovery; Android 12 and newer also needs Bluetooth permissions; Android 13 and newer needs nearby Wi-Fi permission. Selected source files use granted Android content URIs. Android 17 adds local-network permission requirements when targeting API 37 or newer; this app targets 36 and needs a new permission review before increasing that target. Turn Bluetooth and Wi-Fi on manually; the app does not depend on the SDK enabling radios.

Protocol tests and paired-session instrumentation exercise encrypted stores, approval, MediaStore copies, retries, and corruption handling with a test-only transport boundary.

Separate production Nearby SDK checks on two API 36 emulators passed verified pairing, opt-in reference exchange, source-side recognition, declined and approved originals, exact byte integrity, missing-copy recovery, persisted waiting state, and reconnect/retry. See [validation](VALIDATION.md) for versions and evidence.

These real SDK results use virtual networking and do not establish physical nearby interoperability or offline behavior. Pairing without internet, file URI behavior across vendors, throughput, and physical interruption recovery remain on the real-device checklist.

The manifest declarations for the app's minSdk 29 and targetSdk 36 are:

```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />
```

`NearbyPermissions.required()` supplies the runtime subset for the device version. Android 12's location request includes coarse and fine permissions together. The app does not infer physical location. The current Google getting-started sample mentions nearby Wi-Fi from API 32; Android's `NEARBY_WIFI_DEVICES` permission actually starts at API 33, so this app retains discovery location through API 32 and uses nearby Wi-Fi from 33. SAF-granted source descriptors and SDK-granted received URIs avoid blanket gallery read access. FILE transfer on API 29 and 30 still requires device verification with this approach. Do not add gallery permission as a silent fallback.

## Official references

- [Nearby overview, encryption, offline operation, and diagnostics](https://developers.google.com/nearby/connections/overview)
- [Connection verification and two-sided acceptance](https://developers.google.com/nearby/connections/android/manage-connections)
- [Payload ordering, progress, and content URI access](https://developers.google.com/nearby/connections/android/exchange-data)
- [Permissions and target API considerations](https://developers.google.com/nearby/connections/android/get-started)
- [Android 13 nearby Wi-Fi permission](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)
- [Pinned SDK version and Play services availability checks](https://developers.google.com/android/guides/setup)
- [Stable Nearby SDK release history](https://developers.google.com/android/guides/releases)
