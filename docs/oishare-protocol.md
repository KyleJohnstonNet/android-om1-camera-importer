# OI.Share protocol findings

## Evidence and scope

Interoperability research used OI.Share 1.4.3 (versionCode 152), package
`com.omdigitalsolutions.oishare`. APKs and decompiled implementation are not part
of this repository. Source references below identify static call paths in that
version; they do not guarantee behavior across cameras or firmware. The app's
implementation is independently written from the observed protocol facts.

## HTTP transport

The HTTP client in `com/omdigitalsolutions/oishare/a.java` sets the user agent
`OI.Share v2` for camera URLs under `http://192.168.0.10/`.

| Endpoint | Observed purpose / evidence |
| --- | --- |
| `get_commandlist.cgi` | Capability discovery, `I2/a.java`, `y0()` |
| `get_caminfo.cgi` | Camera information, `I2/a.java`, `s0()` |
| `get_connectmode.cgi` | Connection-mode query, `I2/a.java`, `w0()` |
| `get_imglist.cgi?DIR=...` | Directory listing; `trans/ImageTransListActivity.java`, `P7()`; `/DCIM` then subdirectories |
| `get_rsvimglist.cgi` | Reserved/share-order image listing, `ImageTransListActivity.Q7()` |
| `switch_cammode.cgi?mode=play` | Explicit playback transition in Wi-Fi initialization, `I2/a.java`, `u0()` |
| `switch_cammode.cgi?mode=shutter` | Remote-shutter mode, `remocon/RemoconReleaseActivity.java` |
| `switch_cammode.cgi?mode=rec&lvqty=...` | Recording/live-view mode, found in the APK string table |

`ImageTransListActivity` parses line-based, comma-separated image listings. Some
paths are URL-encoded depending on a camera-capability flag. The implementation validates DCF paths and uses bounded directory parsing.

The distinction between playback and shutter modes is material: listing/download
and physical-camera shooting may not coexist. A successful HTTP response alone
does not prove continuous import while shooting. Do not switch camera modes as a
side effect of a read-only diagnostic.

## Bluetooth enables Wi-Fi

Confirmed in static call paths, including the import workflow:

1. `trans/BlePowOnActivity.java`, inner runnable `c`, invokes `L2.b.M(2, 10000)`.
2. On success, it waits **5,500 ms** before advancing toward Wi-Fi connection.
3. `remocon/RemoconConnectActivity.java` also uses `M(2, 10000)` in the method
   logged as `startWifiWithBle`; its stop path uses mode **1**.
4. `L2/b.java` routes this operation through command identifier **0x1D01**.

GATT constants observed in `M2/a.java`:

| Role in implementation | UUID |
| --- | --- |
| Service | `ADC505F9-4E58-4B71-B8CA-983BB8C73E4F` |
| Outgoing command / command-status characteristic | `82F949B4-F5DC-4CF3-AB3C-FD9FD4017B68` |
| Response and acknowledgment characteristic | `B7A8015C-CB94-4EFA-BDA2-B7921FA9951F` |
| Additional notification characteristic; semantics still to trace | `05A02050-0860-4919-8ADD-9801FBA8B6ED` |

The app subscribes to notifications on these characteristics in sequence before
marking the BLE connection initialized. It serializes command transactions.

The one-byte remote-mode operation is framed as:

```text
01 seq 04 1D 01 01 mode checksum 00
checksum = (0x1D + 1 + 1 + mode) & 0xFF
mode 02 starts Wi-Fi in the observed call paths; mode 01 stops it there.
Example for seq=01, mode=02: 01 01 04 1D 01 01 02 21 00
```

This describes the packet construction in `L2/b.java`, `u()` and `L()`, and the
write in `M2/a.java`, `s0()`. The authenticated transaction sequence is described below.
Do not send isolated raw BLE writes without the connection/transaction state machine.

## Camera QR codes

Static evidence in OI.Share `com/omdigitalsolutions/oishare/f.java` identifies
comma-delimited OIS1, OIS2 and OIS3 payloads. OIS1 has encoded SSID/password; OIS2
adds a connection-kind field (1 Wi-Fi, 3 combined); OIS3 also inserts a security
field before SSID/password. Combined codes append Bluetooth fields, which the helper retains in its encrypted profile.
The connection builder in `I2/e.java` selects WPA3 for security 2, WPA2 otherwise.
Our parser accepts only known security values 1 and 2 and known field counts.

Credential encoding reverses the alphabet `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ$%*+-/`
and separately reverses lowercase a–z; spaces remain spaces. The implementation
is independently written from these format facts. Invalid input is rejected, never
silently stripped. Tests use invented camera details, not user credentials.

## Network access

Camera traffic uses per-request binding to the helper-owned Wi-Fi network.
Owning a secondary network does not override a non-bypassable VPN's restrictions;
Android can reject explicit physical-network access with EPERM. The separate helper
UID permits a camera-only app exclusion while cloud traffic follows system routing.
LAN route exceptions and permission to select a physical network are distinct.

References:
- https://android.googlesource.com/platform/system/netd/+/refs/heads/main/server/NetworkController.cpp
- https://developer.android.com/reference/android/net/VpnService.Builder#allowBypass()
- https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap

## Hardware findings

Capability queries, DCF listings and full-size JPEG transfer succeeded through the
helper's signed IPC. Helper and importer hashes matched an independent local hash.
JPEGs may contain trailing data after the end-of-image marker; preserve those bytes.
The camera does not supply a cryptographic hash, so this is transfer verification,
not independent comparison with the SD card. No camera originals were modified.

Normalized capability and connection-mode XML fixtures are under `fixtures/om1/`.
They contain protocol information, not pairing identities or account details.
Advertised operations are not evidence that every command has been tested.

## Authenticated Bluetooth sequence


OI.Share 1.4.3's `BlePowOnActivity`, connection manager `e`, command wrapper `L2.b`
and GATT transport `M2.a` establish this interoperability sequence:

- Scan the saved QR Bluetooth name and service
  `adc505f9-4e58-4b71-b8ca-983bb8c73e4f`. Manufacturer company 0x04d0 or 0x09f1,
  product 1, readiness flag 1. Android strips the company ID from manufacturer data.
- Subscribe sequentially to command `82f949b4-f5dc-4cf3-ab3c-fd9fd4017b68`,
  response `b7a8015c-cb94-4efa-bda2-b7921fa9951f`, and event
  `05a02050-0860-4919-8add-9801fba8b6ed`, using CCCD 0x2902.
- Authenticate: command 0x0c, subcommand 2, UTF-8 QR Bluetooth passcode.
  Continue only on result 0. This is application authentication, not evidence
  that Android `createBond` is needed.
- Unless manufacturer flags 8 or 32 are set, request power-on: command 0x0f,
  subcommand 1, payload 02; accept result 0 or 1.
- If flag 0x08 is set, Wi-Fi wake uses command 0x1d, subcommand 1, payload 02;
  require result 0 and wait 5.5 seconds. Otherwise the power-on path starts Wi-Fi;
  wait 3 seconds without sending a redundant Wi-Fi command.

Outgoing frame: `01 sequence payloadLength+3 command 01 subcommand payload checksum 00`.
Checksum sums command, 1, subcommand and payload modulo 256. Sequence starts at 1.
Command-characteristic notification starts with 05. Response starts with 04,
command at byte 3, subcommand at byte 5, result at byte 6. Acknowledge on the
response characteristic with `02 responseSequence 00 00 00`. Response length and
checksum semantics are not yet independently established; the implementation checks
bounds, type and matching command/subcommand, as distinct from outgoing checksum.

Helper implementation is independently written from these wire facts. No vendor
implementation is copied. Authentication, power-on and Wi-Fi wake are the only
Bluetooth commands exposed. Secrets, raw frames and device addresses are not logged.
All GATT writes await completion; response buffering handles notifications arriving
before write completion. Scan/connection/commands have deadlines and cancellation
closes the GATT link. The helper retains its existing VPN exclusion.

Bluetooth wake and original transfer have been exercised on hardware. This does
not establish simultaneous physical shooting or compatibility across firmware.
See [standby transfer](between-shooting-transfer.md) for the controller-off path.
