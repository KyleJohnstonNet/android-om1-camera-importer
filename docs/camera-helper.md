# Camera helper and VPN separation

## Apps and routing

| App | Package | Routing |
| --- | --- | --- |
| OM-1 Importer | `dev.om1.importer.diagnostic` | Android's normal system/VPN route |
| OM-1 Camera Link | `dev.om1.camerahelper` | Camera Wi-Fi; exclude from VPN if needed |

The apps have separate UIDs and must use the same signing certificate. A second
process within one package would not provide a separate VPN exclusion boundary.
They do not share credentials or an account store. App exclusion covers the helper's
entire UID; the fixed camera endpoint restriction is enforced by application code.

## Connection flow

Keep an internet connection available. If the VPN blocks camera access, exclude
only Camera Link using the VPN provider's app split-tunnel settings. Scan the camera
QR code in Camera Link and grant camera, nearby-device and notification permissions.
The connected-device foreground service owns the secondary local camera network.
An import started from the importer returns there after connection and releases the
camera link after its batch. Manual disconnect and notification controls remain.

When Bluetooth wake is selected, Android's enable-Bluetooth confirmation is shown
if needed. Approval resumes connection; denial leaves it stopped. Ordinary apps
cannot silently enable Bluetooth on recent Android versions. See
[BluetoothAdapter](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#ACTION_REQUEST_ENABLE).

## Remembered camera

QR profiles retain Wi-Fi and, for combined codes, Bluetooth identity and passcodes.
Atomic AES-256-GCM storage uses an Android Keystore key and fresh IVs; backup is
disabled. Scanning saves the profile, but does not prove it has authenticated.
Successful Wi-Fi updates retain matching Bluetooth details. Older Wi-Fi-only
profiles require a combined QR scan to gain Bluetooth wake support.

Forget camera deletes the encrypted profile and key while disconnected. It does
not reset the camera, delete photos or remove an Android Bluetooth bond. Corrupt
profiles produce an error rather than guessed credentials. Reconnect remains
explicit; there is no unattended retry loop or reboot/endurance guarantee.

## IPC and file integrity

A signature permission protects the helper's Messenger service. Each request also
checks the kernel-supplied sender UID and package signing certificate. The importer
verifies the helper certificate and binds an explicit component. Reply IDs and
timeouts prevent stale replies from satisfying later requests.

Operations expose capability queries, bounded DCF directory listings, original
JPEG download and connection release. They accept no arbitrary URL, socket, local
path or cloud token. Camera HTTP binds to the helper-owned camera network and uses
the fixed camera host; redirects, proxies and internet-route fallback are disabled.
Directory responses and JPEG sizes are bounded. Camera operations are serialized.

The helper returns a read-only file descriptor and unlinks its temporary copy.
The importer checks size, SHA-256 and JPEG dimensions before finalizing a private
original. Content hashes support local deduplication. JPEG trailing data is retained.
The queue and cleanup rules are described in [preview queue](preview-queue.md).

## Validation scope

Hardware checks exercised signed IPC, capability queries, secondary Wi-Fi,
encrypted-profile reload across restart/update, Bluetooth consent and reconnect,
original transfer/deduplication and power-off standby. No individual device,
network, account or photo identifiers are retained here. See
[standby findings](between-shooting-transfer.md) for the tested workflow and limits.
