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
- A durable queue resumes uploads and freezes the account and session album at discovery.
- Camera originals are never deleted. Phone copies are removed only after confirmed upload.
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
