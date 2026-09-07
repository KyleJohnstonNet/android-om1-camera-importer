# OM-1 camera importer for Android

Import original JPEGs from an OM-1 during shooting breaks, then upload them to
Google Photos using Android’s normal network routing. **The camera transfer milestone
is verified. Session imports and cloud upload are implemented in the preview; live
Google Photos validation awaits OAuth setup.**

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
- Eight-hour sessions skip existing photos by default; import new JPEGs during breaks.
- A durable queue resumes uploads and freezes the account and timed album at discovery.
- Camera originals are never deleted. Phone copies are removed only after confirmed upload.
- Cloud requests respect system VPN routing and lockdown; a VPN is not required by the app.

[Google Photos setup](docs/google-photos-setup.md) covers the required Cloud project
and Android OAuth registration. No backend or embedded client secret is needed.

Preview versions: Importer 0.9.0-preview, Camera Link 0.7.0-helper.
All 26 Kotlin tests, 11 protocol-tool tests and both APK build/lint checks passed.
The preview UI and an unauthenticated Google connectivity probe passed in hardware testing. [Hardware evidence](docs/between-shooting-transfer.md),
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

Android 12+. Main application ID dev.om1.importer.diagnostic; helper
application ID dev.om1.camerahelper. Both APKs must have the same signing certificate.
Development APKs use the local debug key. Never commit camera profiles, credentials,
private captures, vendor APKs, signing keys or local SDKs.
