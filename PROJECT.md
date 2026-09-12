# Project status

## Objective

Import original JPEGs from an OM-1 to Android during shooting breaks, then upload
them to Google Photos. One camera and one account, with no hosted backend.
Explicit timed sessions freeze the account and selected app-created album at discovery. A session uses camera listing timestamps as a capture-time proxy, so it can also backfill an earlier interval.
Camera originals are never deleted. Local cleanup requires confirmed cloud creation.

Camera Link uses a separate APK/UID so a VPN can exclude camera traffic independently.
The importer follows Android's default routing and VPN policy without requiring a VPN.
Camera IPC is signature-protected; camera HTTP has fixed endpoints and bounded inputs.

## Implementation and validation

Importer 0.9.0-preview includes a SQLite queue, complete paged enumeration,
foreground/manual and scheduled automatic imports, Google authorization, session albums, resumable uploads,
retries and conservative reconciliation of ambiguous media creation. Camera Link
0.7.0-helper supports encrypted profiles, Bluetooth wake and connection release.

Hardware milestones include QR/profile persistence, secondary camera Wi-Fi,
authenticated Bluetooth wake, original transfer with matching independent hashes,
and the shoot / standby import / disconnect / return-to-shooting cycle.
An unauthenticated Google endpoint probe confirmed connectivity, not cloud delivery.

The recorded preview checks passed: 26 Kotlin tests, 11 protocol-tool tests and
both APK build/lint tasks. These are historical check results, not an automated
claim that all future changes pass.

Subsequent work verified a real retrospective import and Google Photos confirmation,
and added isolated durable-queue instrumentation and recovery regressions. See
[the reliability audit](docs/reliability-audit.md) for current test results and fixes.
Remaining validation includes controlled physical-switch/reboot acceptance,
live interrupted-upload/ambiguous-response fault injection, and long sessions.
OAuth setup instructions are deployment-neutral; private account
troubleshooting and individual device inventories are not kept in public notes.

See docs/development-history.md, docs/preview-queue.md and
docs/between-shooting-transfer.md for sanitized findings and limitations.

Public privacy and terms documents are provided in PRIVACY.md and TERMS.md,
linked from the README. Their publication does not establish Google verification.
