# Data flow and privacy limits

## Local data

A nickname and random local identifier replace accounts. Events contain random invitation secrets. Selected-photo references, verified friends, match decisions, requests, and feedback stay in an AES-GCM encrypted atomic state file in Android's backup-excluded app directory. Its non-exportable encryption key is generated in Android Keystore. App backups and device migration are excluded. This protects storage within platform limits; it does not protect an unlocked phone from its user or a compromised operating system.

Only photos explicitly selected through Android document pickers, or within an explicitly authorized event folder and its subfolders, are read. Rescans are manual. Revoked grants, moved files, and changed bytes are reported. Source images are never changed or deleted. There is no unrestricted gallery scan.

Reference selfies are processed into face representations; the app does not copy the selfie images. Event sharing is off by default. Deleting face data removes local references and matching suggestions and disables future sharing. Peer references are only useful in an active verified session. No face representations are written to logs or feedback exports.

## A sharing session

Nearby advertises a nickname and event-specific service identifier. Treat nicknames as visible to nearby devices. Both people compare Nearby's authentication digits before accepting. The app then validates a proof of the invitation secret tied to that connection. Only verified, allowed peers in the same event receive previews, optional face references, or private messages. An invitation alone and a face match alone do not authenticate a person.

Selecting event photos authorizes small previews to verified event friends, including photos without a face match. Each original needs owner approval. Optional reference sharing sends normalized embeddings, not selfies, to those friends; these are sensitive face data, not anonymous hashes. The source phone detects and aligns multiple faces and compares only enrolled participants. Suggestions and uncertain results need human review. Manual tags and corrections remain available.

Originals use Nearby FILE payloads after an approval and metadata handshake. Scoped photo identifiers never select arbitrary filesystem paths. Size and SHA-256 are checked before a received copy is saved, with deterministic MediaStore names to prevent duplicate saves after retry or process death. Incoming metadata is bounded and validated. Transfers preserve the bytes read from the selected provider. MediaStore-backed originals require original metadata access rather than silently using redacted data; other document/cloud providers control the bytes they supply. Originals may include GPS and other camera metadata.

`setRequireOriginal` adds a query parameter that can fall outside an exact selected-photo grant. The app retries only regular MediaStore rows using the unchanged URI after checking metadata permission and its app operation. On Android 12+, [EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT](https://developer.android.com/reference/android/provider/MediaStore#EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT) disables transcoding. Picker and redacted URIs never use this fallback. Android 10 and 11 keep the selected document URI unchanged, with the same metadata checks, because their [conversion implementation](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/android10-release/src/com/android/providers/media/MediaProvider.java#4229) routes media documents to the wrong provider.

New received copies are saved to `Pictures/ThatsMyFace/Requested`. Existing copies in `Pictures/ThatsMyFace` stay in place and are checked before creating another copy. Temporary outgoing/incoming copies use app cache and are removed after transfer. Nearby itself can stage received FILE payloads in shared Downloads storage; the app removes known staging URIs, but process death or device-specific SDK behavior can leave a staging file. Check this during device testing. Already downloaded or cached data on another person's phone cannot be recalled, including after access is removed.

## Network and feedback

No app backend, hosted recognition, app analytics, cloud photo storage, or ads are included. Models ship in the APK. Google Play services is a separate third-party component: Nearby documents diagnostic and performance collection. SDK setup, updates, diagnostics, document providers, or other system services may use internet access. See the [SDK documentation](https://developers.google.com/nearby/connections/overview) and [transport notes](NEARBY.md). Offline transport does not imply zero device network traffic.

Feedback consists only of a user-written note and app version. It stays local until the user exports it through the Android destination picker. Exports do not add photos, face data, event details, identifiers, logs, or credentials. Users should keep private details out of notes.

Deleting local app data removes profile, event access, face data, request history, feedback, and persisted source grants. Source photos and saved received images remain. Android uninstall removes app-private data; shared received copies remain independently owned by the receiver.
