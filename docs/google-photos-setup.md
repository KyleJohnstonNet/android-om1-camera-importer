# Google Photos setup

The camera/import queue works before Google setup. Live Photos delivery requires
a Cloud project owned by you. No backend, service account, API key, client secret,
or downloaded credential file is needed by this Android implementation.

## Configure Google Cloud

1. Open https://console.cloud.google.com/ and create or select your own project.
2. In APIs & Services → Library, enable **Photos Library API**.
3. In Google Auth Platform, configure branding/contact information, choose the
   appropriate audience, and add your Google account as a test user while testing.
   Supply truthful branding/privacy information requested by the console; do not
   invent hosted URLs. Personal testing may show Google's unverified-app prompt.
4. Add these scopes under Data Access:
   - `openid`
   - `https://www.googleapis.com/auth/userinfo.email`
   - `https://www.googleapis.com/auth/photoslibrary.appendonly`
   - `https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata`
5. Create an OAuth client with application type **Android**:
   - Package: `dev.om1.importer.diagnostic`
   - SHA-1: the fingerprint of the certificate used to sign your installed APK.
   Obtain it with `apksigner verify --print-certs <your-apk>` or the Gradle
   `:app:signingReport` task. Different development keys have different fingerprints.
   Register the main importer, not the camera helper. Keep the client/project stable:
   app-created album/media access is tied to the original OAuth application.
6. Ensure Google Play services is available in the same Android profile
   and add your Google account. Tap **Connect Google
   Photos**, select the intended account, and approve requested access.

AuthorizationClient identifies this Android application by its package and signing
certificate. This client-side flow does not request a server auth code or use a
Web client secret. If Google reports code 10 or setup failure, check the package,
SHA-1, enabled API, test user and consent configuration. Testing-mode grants can
require reauthorization; queued originals are retained.

The app only offers albums created through this OAuth app. Existing arbitrary
Google Photos albums are not writable through the append-only Library API.
A timed album affects photos discovered while its timer is active; later upload
retries keep their original destination. Photos already imported without an account
are explicitly assigned when you first connect Google Photos.

## Privacy and storage

The importer reads camera JPEGs, verifies their integrity, stores originals privately,
and uploads to the selected Google account. It uses an app-specific description
marker to reconcile interrupted media creation. It does not delete camera images.
The optional cleanup switch removes the phone's copy only after a confirmed Google
media item exists. Original-quality API uploads count toward Google storage.
OAuth access tokens remain with Play services/in memory; resumable URLs and upload
tokens are encrypted with an app-specific Android Keystore key. Account identity
and queue metadata are private app data; Android backup is disabled.

Cloud requests use Android’s default network routing and respect the system VPN
and lockdown settings. There is no app-enforced VPN requirement: uploads can run
when you intentionally turn the VPN off. Google Play services manages its own
authorization transport. Only Camera Link needs the camera split-tunnel exclusion.

## Validation still required

Verify account selection, consent/refresh, library and timed-album uploads,
interrupted resumable upload, ambiguous creation reconciliation, quota/album errors,
and confirmed-only cleanup against the deployment account. Fake-transport tests
are not a substitute for these checks. No live cloud delivery is claimed here.

Sources: [Android authorization](https://developer.android.com/identity/authorization),
[Photos configuration](https://developers.google.com/photos/overview/configure-your-app),
[Photos scopes](https://developers.google.com/photos/overview/authorization),
[Resumable uploads](https://developers.google.com/photos/library/guides/resumable-uploads),
[Upload and creation](https://developers.google.com/photos/library/guides/upload-media).

## Test-user eligibility troubleshooting

Use the exact primary Google Account email for the account selected on the phone.
Confirm Audience settings in the same project as the OAuth client. If the console
rejects the account as ineligible, check whether it is enrolled in Google Advanced
Protection or subject to Google Workspace administrator restrictions. These can
block authorization even when the app configuration is otherwise correct.
Do not assume publishing the app will override account restrictions.

Google documents account restrictions in [Manage App Audience](https://support.google.com/cloud/answer/15549945)
and [Advanced Protection](https://support.google.com/accounts/answer/7519408).
Personal-use apps can normally operate without full OAuth verification, subject
to warnings and user limits, per [verification exemptions](https://support.google.com/cloud/answer/13464323).
The exported client JSON is kept locally and excluded from Git; this Android
AuthorizationClient integration uses the registered package and signing certificate.
