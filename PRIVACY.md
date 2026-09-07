# Privacy Policy

Effective date: September 7, 2026

This policy covers OM-1 Importer (also described as OM-1 Photo Uploader) and
OM-1 Camera Link, maintained through the
[android-om1-camera-importer project](https://github.com/KyleJohnstonNet/android-om1-camera-importer).
It describes the software in this repository. Independently modified versions may
handle data differently.

## What the apps access and why

- **Camera connection information:** Camera Link reads a camera QR code and uses
  its Wi-Fi and Bluetooth identifiers and passcodes to remember and reconnect to
  that camera. QR scanning uses the phone's camera. Nearby-device access supports
  Bluetooth discovery and the local camera Wi-Fi connection.
- **Photographs:** The importer copies selected or session-discovered JPEGs from
  the camera and stores originals on the phone. Original embedded metadata, which
  may include capture time or location, is preserved with the file.
- **Google account information:** When you connect Google Photos, Google Play
  services handles authorization. The importer uses your Google account identifier
  and email address to display the account and keep uploads assigned to it.
- **Google Photos data:** With your permission, the importer uploads JPEGs, creates
  albums and reads albums/media created through this app. It uses that access to
  select destinations and reconcile interrupted uploads. It does not request access
  to browse your entire existing Photos library.
- **Transfer records:** Local records include image paths, sizes, hashes, discovery
  times, account and album assignments, upload progress, errors and Google media
  identifiers. An app-specific import marker is included in the uploaded item's
  description to help identify a previously completed upload.

Notifications show connection or import progress. Local diagnostics can include
operation times, device/software information, file names and errors. The apps do
not automatically send diagnostic reports to the maintainers.

## Storage and sharing

Camera connection secrets remain in Camera Link's private storage, encrypted using
Android Keystore. They are not passed to the importer or uploaded to Google.
Photos and queue records remain in the importer's private app storage. Resumable
upload URLs and upload tokens are encrypted using Android Keystore; account and
queue metadata are not separately encrypted by the app. Android app backup is
disabled. Google authorization is managed through Google Play services, and the
importer uses access tokens in memory for requests.

When uploads are enabled and Google access is authorized, original JPEG bytes,
file names, embedded metadata and the import marker are sent directly to Google
Photos in the assigned account and destination. Google requests use HTTPS and
Android's normal network routing, including system VPN settings. Camera transfers
use the camera's local Wi-Fi protocol. Camera Link may need a separate VPN exclusion.

There is no project-operated upload server. The apps contain no advertising or
analytics service, and do not sell personal data or use Google user data for
advertising, credit decisions or training general-purpose AI models. Maintainers
do not receive photos or account data through normal app operation.

Use and transfer of information received from Google APIs will adhere to the
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including its Limited Use requirements. Google processes data sent to its services
under its own [Privacy Policy](https://policies.google.com/privacy).

## Retention and your controls

You can pause imports or uploads and choose whether to remove phone copies after
confirmed Google Photos creation. Unconfirmed or ambiguous uploads retain their
local originals. The apps never delete camera originals. Queue/receipt metadata
can remain after an uploaded local copy is removed.

Use **Forget camera** in Camera Link to remove its saved connection profile and
key. Clear storage for each app or uninstall it to remove that app's private local
data; doing so can discard photos that have not yet uploaded. You can revoke
Google authorization through your [Google Account connections](https://myaccount.google.com/connections).
Revoking access does not delete existing uploads or local app data. Delete uploaded
photos and albums separately in Google Photos. The maintainers cannot delete data
from your phone or Google account on your behalf.

## Project website and contact

GitHub hosts the repository and processes visits and any information you submit
under its own [Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
For questions, contact the maintainers through the
[project issue tracker](https://github.com/KyleJohnstonNet/android-om1-camera-importer/issues).
Issues are public: do not attach passwords, tokens, private photos or unredacted
account information. For account authorization questions, you may also use the
support contact displayed by the Google consent screen for your deployment.

## Changes

Updates to this policy will be published here with a revised effective date.
Changes that introduce a new use of Google user data require updated disclosures
and consent before that new use begins.
