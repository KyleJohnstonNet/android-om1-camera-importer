# OM-1 camera importer for Android

Import original JPEGs from an OM-1 during shooting breaks, then upload them to
Google Photos using Android’s normal network routing. **The camera transfer milestone
is verified. A retrospective session has also imported and received Google Photos
confirmations for its matching JPEGs. Unattended switch/reboot behavior still needs
hardware acceptance testing.**

[Privacy Policy](PRIVACY.md) · [Terms of Service](TERMS.md)

[How the app is used and operates](docs/how-the-app-works.md) explains the intended
workflow, architecture, persistence, and current limitations with diagrams.
[Reliability audit](docs/reliability-audit.md) records subsequent bugs, fixes, and tests.

## Verified workflow

Set the camera's smartphone Bluetooth standby and Power-off Standby to On.
Shoot normally, switch the camera OFF, connect using Camera Link, import photos,
disconnect, and switch the camera ON to resume shooting. Hardware checks exercised
this cycle. Full-resolution JPEG transfer and SHA-256
verification work, including newly shot photos and local duplicate handling.

- **OM-1 Camera Link** handles QR setup, remembered encrypted credentials,
  Bluetooth wake, secondary Wi-Fi and fixed camera HTTP endpoints.
- **OM-1 Importer** receives originals through signature-protected IPC. Keep it
  on the system route; exclude **only Camera Link** from your VPN if needed.
- Sessions have explicit local start and end times. Their chosen album is frozen, and a past window can backfill every JPEG whose camera capture time falls in that interval.
- With saved Bluetooth details and Power-off Standby enabled, Camera Link watches for standby advertisements, reconnects, and schedules sync. It persists unfinished collection across restarts. The advertisement's relationship to the physical switch and actual phone reboot recovery still need controlled validation.
- Both apps show the live camera phase, including Bluetooth scanning, the pause
  between scans, wake/connection steps and per-file transfer progress. Timed phases
  show a countdown; Camera Link's foreground notification follows the same status.
  Importer also shows upload progress, its adaptive limit and automatic retry waits.
- Standby monitoring uses 15-second low-power Bluetooth scan windows followed by
  45-second pauses. Detection can be delayed during the pauses; Sync now remains available.
  Post-import/error cooldowns also wait 45 seconds; failure backoff can extend the wait.
- Camera stats show timestamped passive Bluetooth sightings, RSSI and last controller
  state (not proof of the physical switch), plus aggregate download throughput over
  five seconds. Importer shows importable JPEG counts per card from completed directory
  scans in the most recent import, including files outside the session window. Missing
  or failed scans are unknown, not zero. Stats are in-memory and reset with their app.
  No extra scans, Wi-Fi wakes or live-view requests are made. Battery and free card
  space remain unavailable until their import-mode protocol and units are verified.
- Camera imports scan both SD card slots, keeping directory scans and slot
  switches serial. Each queue entry records its source slot. The previous playback
  slot is restored afterward when the camera remains reachable.
- Camera downloads are temporarily limited to **one at a time in both apps** while
  horizontal image corruption is investigated. Google Photos uploads remain adaptive
  and parallel. Hashes across IPC and JPEG marker checks do not prove the camera
  supplied uncorrupted pixels; concurrent camera reads are not considered verified safe.
- A durable queue resumes uploads and freezes the account and session album at discovery.
- Upload concurrency adapts from one to eight photos, starting at three. It measures
  acknowledged photo bytes in 10-second windows and tries one additional slot at
  a time, keeping it when total throughput improves by at least 10%. Stalls,
  network errors and server throttling reduce concurrency; unsuccessful probes
  wait 30 seconds before trying again. A new network starts learning afresh.
  No separate speed-test traffic is sent. An explicit upload-now request can use
  one extra slot. Network failures retry after 10 seconds,
  and a restored internet connection wakes the queue. Android background scheduling
  may delay these wakeups.
- Hold an imported pending photo in Recent photos to prioritize it immediately.
  This permits cellular data for that photo only and reserves an extra upload slot.
  The global upload pause and battery saver still apply.
- Battery saver pauses camera collection and cloud transfers in both apps; automatic
  monitoring and uploads resume when it is disabled.
- Tap a recent photo to open a screen-width preview; tap again to close. Originals
  are used when available, with retained previews used after original cleanup.
  Previews are retained only for the 20 displayed recent rows and pruned as rows
  leave that list. Older photos whose originals were already removed may show a placeholder.
- Camera originals are never deleted. Automatic phone-original and GPS upload-copy
  cleanup is temporarily suspended, even if the saved cleanup preference was enabled.
  Copies consume phone storage until the corruption investigation is resolved.
- After a successful standby collection, a persisted gate requires a new observed
  standby → powered → standby cycle before another automatic wake. Our own powered
  controller is ignored until a standby baseline is observed. One final collection
  is allowed after the session ends; failed scans retry with a 30-second to 15-minute
  backoff. Sync now bypasses the gate. Bluetooth controller state is not a definitive
  physical-switch signal, so very brief/unobserved shooting cycles may need Sync now.
- Optional GPS recording and geotagging have independent default-off toggles.
  Recording uses a visible location service, pauses for battery saver, and keeps
  30 days of phone-local history. It resumes an enabled recorder when the app opens,
  not from boot/background starts. Geotagging preserves existing GPS and requires
  a fix within two minutes of capture with accuracy at most 100 metres. It creates
  a separate JPEG upload copy without recompressing pixels; originals are unchanged.
  Retry payloads are frozen before upload and cleaned only after cloud confirmation.
  **Limitation:** automatic camera clock/timezone readback and correction remain
  unimplemented. EXIF timezone is used when present, otherwise the pinned session
  timezone. Verify the camera clock before using GPS matching. The verified OM-1
  command list exposes a clock setter but no verified clock/timezone getter; resetting
  blindly would lose the offset needed to correct existing photos.
- Cloud requests respect system VPN routing and lockdown; a VPN is not required by the app.

[Google Photos setup](docs/google-photos-setup.md) covers the required Cloud project
and Android OAuth registration. No backend or embedded client secret is needed.

Preview versions: Importer 0.9.0-preview, Camera Link 0.7.0-helper.
Historical preview checks passed; current regression and device results are recorded
in the [reliability audit](docs/reliability-audit.md). [Hardware evidence](docs/between-shooting-transfer.md),
[setup and security boundary](docs/camera-helper.md), [current status](PROJECT.md).

## Build

JDK 17, Android SDK 36, build tools 35.0.0. Workspace-local toolchains are kept in
ignored .local-tools; tools/build.sh detects them. Gradle wrapper 8.11.1 is pinned.

```sh
./tools/build.sh -Pkotlin.compiler.execution.strategy=in-process :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :camera-helper:assembleDebug :camera-helper:lintDebug
python3 -m unittest discover -s tools -p 'test_*.py' -v
adb install -r camera-helper/build/outputs/apk/debug/camera-helper-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Queue instrumentation: `./tools/build.sh :app:connectedDebugAndroidTest` with an
attached test device. The project explicitly keeps APKs installed after tests;
do not override that setting on a phone containing real app data.

Android 12+. Main application ID dev.om1.importer.diagnostic; helper
application ID dev.om1.camerahelper. Both APKs must have the same signing certificate.
Development APKs use the local debug key. Never commit camera profiles, credentials,
private captures, vendor APKs, signing keys or local SDKs.
