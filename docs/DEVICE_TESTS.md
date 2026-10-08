# Two-phone acceptance checklist

Use two consenting adults, two Android 10+ phones with compatible Google Play services, and test media you can share. Record phone models, Android/Play services versions, APK commit, and result. Do not export face vectors or private media in bug reports.

- [ ] Install the same APK, choose different nicknames, create an event, and scan its QR invitation. Relaunch and check persistence.
- [ ] Deny then allow nearby permissions. With Bluetooth and Wi-Fi on, start sharing on both phones. Compare identical digits and accept both. Reject another pairing and confirm no previews appear.
- [ ] Select a few event images and an event folder. Verify unrelated gallery images are absent. Rescan only the selected folder and subfolders.
- [ ] Enroll one to five clear selfies. Verify enrollment rejects multiple faces or poor references. Opt in for this event; verify no references are sent before verification or before opt-in.
- [ ] Check genuine matches, a different person, multiple people, low-light/blurred and ambiguous images, and no-face images. Record incorrect or uncertain suggestions. Confirm, reject, and manually correct them.
- [ ] Request an original. Verify the owner sees an approval request and no original moves before approval. Approve and check progress, destination, original byte count, and SHA-256 equality.
- [ ] Reject and cancel requests. Attempt a different event and a removed peer. Verify access stays denied and no cancelled transfer becomes complete on a late message.
- [ ] Switch off or disconnect the source phone mid-transfer. Verify waiting/failed state and no fake completed download. Reconnect, verify again, and retry.
- [ ] Relaunch either app mid-transfer. Retry twice and confirm only one saved copy. Interrupt immediately after saving but before receipt to check duplicate prevention.
- [ ] Revoke photo/folder permissions or move/delete a source file. Check actionable errors and reselect the file. Ensure stale previews are removed on refresh and no original is supplied.
- [ ] Disable reference sharing, delete face data, remove a friend, and delete local app data. Verify future transfers stop and source/downloaded files remain. Inspect Downloads for orphaned Nearby staging files after forced termination.
- [ ] After setup, disable internet while retaining local Wi-Fi and Bluetooth. Test pairing, matching, and approved originals. This checks offline capability, not absence of SDK/system diagnostic traffic.
- [ ] Try TalkBack, larger font size, landscape, denied camera permission with QR image/text fallback, low storage, and a phone without compatible Google Play services.

One phone plus an emulator does not count as this two-physical-phone acceptance test. Automated model fixtures establish execution, not real-world recognition accuracy.
