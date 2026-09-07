# Development milestones

This summary retains implementation findings without personal hardware inventories,
network identifiers, photo metadata, account details or individual test timestamps.

1. Read-only diagnostics established camera capability and connection-mode queries.
2. Explicit physical-network access could be denied under a non-bypassable VPN.
   A separate, signature-protected camera helper provides an app exclusion boundary.
3. QR parsing and encrypted camera-profile persistence enabled remembered reconnects.
   Persistence was checked across process restart and an in-place app update.
4. Original JPEG transfer through a read-only descriptor passed size, structure and
   independent hash checks. Duplicate content was reused locally. Trailing bytes
   after JPEG end-of-image must be retained.
5. Authenticated Bluetooth wake and Android's enable-Bluetooth consent flow were
   exercised. Rejecting consent left the connection stopped; allowing it resumed.
6. Power-off standby required controller-off discovery and a two-phase GATT wake.
   The power-on path can start Wi-Fi itself; a redundant Wi-Fi command must be avoided.
7. Paged browsing removed the old truncated-directory limitation. The camera cycle
   of shooting, standby import, disconnect and returning to shooting was exercised.
8. The preview added a durable queue, session imports, Google authorization, timed
   albums, resumable uploads and conservative recovery from ambiguous creation.
   Cloud traffic uses system routing without an app-enforced VPN requirement.

Build/test results and remaining validation are in [project status](../PROJECT.md).
Protocol facts are in [OI.Share findings](oishare-protocol.md), helper architecture
in [camera helper](camera-helper.md), and standby behavior in
[transfer between shooting](between-shooting-transfer.md).

Hardware milestones are limited observations, not a compatibility matrix or an
endurance guarantee. Live cloud delivery and full preview batch recovery remain
unverified. Private session logs are not part of the published documentation.
