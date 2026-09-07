# Transfer between shooting sessions

## Workflow and scope

Hardware observation showed that smartphone connection mode can block the physical
shutter. The supported workflow is shoot, switch the camera off with Power-off
Standby enabled, import during a break, release the link, then switch on to shoot.
That cycle has been exercised; simultaneous shooting and transfer is not established.
These are limited findings, not a firmware compatibility or endurance guarantee.

The original OM-1 manual, pages 262–264, documents:

- Setup → 4. Wi-Fi/Bluetooth → Bluetooth: enable the smartphone standby option,
  distinguished from the optional remote-control option.
- Setup → 4. Wi-Fi/Bluetooth → smartphone Settings → Power-off Standby → On.
  The smartphone can browse/download while the camera's power lever is off.
- Standby terminates after 12 hours of inactivity, card removal, battery replacement,
  or a charging error. Turning the camera on restores availability; startup may
  be delayed when standby is enabled.
- OI.Share's documented automatic import path requires Share Order marks and
  power-off standby; tapping its notification starts transfer to the phone.
  This is not direct Google Photos uploading.

Source: https://my.omsystem.com/consumer/manuals/cameras/OM-1_MANUAL_EN.pdf
## Standby protocol

Static OI.Share paths distinguish BLE controller power from readiness to transfer.
Discovery must accept the saved camera's controller-off advertisements. For this
state, delay three seconds, connect GATT without service discovery, wait for
its disconnect (bounded to twenty seconds), close it, and scan for the powered
controller before authentication. Cancellation closes the connection.

Authenticate with command 0x0c/subcommand 2 and the QR passcode. Unless flags 0x08
or 0x20 are set, command 0x0f/subcommand 1/payload 02 requests power-on. Only when
flag 0x08 is set, send Wi-Fi command 0x1d/subcommand 1/payload 02 and wait 5.5 seconds.
Otherwise the power-on path starts Wi-Fi; wait three seconds. Sending both in the
standby path produced an error. Result code 1 is not globally treated as success.
See [protocol findings](oishare-protocol.md) for framing and characteristic UUIDs.

The vendor's automatic-transfer notification checks share-order and pairing flags.
It does not establish a generic notification that every new JPEG is ready.
The preview therefore uses an explicit import action, not an unattended wake loop.

## Transfer and release

Enumerate supported DCF directories using bounded pages, then compare against the
durable per-camera ledger. Import original bytes and verify size and hashes before
releasing the helper's camera connection. Upload independently from local storage,
using normal Android routing and system VPN settings.

Hardware checks exercised controller wake, authentication, Wi-Fi, paged listing and
original JPEG transfer with the lever off. Hashes matched across helper/importer
and an independent local calculation. Returning to shooting after releasing the
phone connection and switching the camera on was also checked. No remote shutdown
command was needed for that observed cycle.

Connection-mode responses included `private` and `playmodeonly_private`; normalized
fixtures are in `fixtures/om1/`. These strings do not establish undocumented mode
semantics. The advertised `exec_pwoff.cgi?mode=withble` command was traced but is not
implemented; it must not be confused with releasing the phone's Wi-Fi request.

Remaining work includes full preview batch acceptance, interrupted-transfer recovery,
unattended standby detection, long sessions and live Google Photos delivery.
Personal test timestamps, network assignments, image names, sizes and hashes have
been omitted from this public summary.
