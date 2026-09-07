# Preview queue behavior

Camera Link and Importer retain separate UIDs. Camera Link owns the camera Wi-Fi
request; Importer uses ordinary HTTPS connections on Android's default route.
There is no VPN transport check or network binding in the uploader. Android chooses
the app's default route and enforces its VPN policy:
https://developer.android.com/develop/connectivity/network-ops/reading-network-state

An eight-hour session records a baseline of all camera directories. By default,
existing JPEGs are skipped. Each later break enumerates all pages and downloads new
JPEGs sequentially. Include existing JPEGs explicitly to queue the baseline too.
The helper releases the camera network after completion or cancellation. Importing
still requires the user to start a break; no unattended Bluetooth wake loop runs.

The private SQLite ledger identifies a source by camera identity, path, size and
camera timestamp. First discovery freezes its account and album destination.
Local imports made before account setup are assigned to the first connected account
with the UI explaining that assignment. Timers apply at discovery, not exposure time.

Rows progress from DISCOVERED to READY after full-size, SHA-256 and JPEG checks.
WorkManager serializes uploads. Resumable URLs and upload tokens are encrypted with
Android Keystore. The server's queried byte offset controls each resumed upload.
A receipt is committed before local cleanup; the camera copy is never deleted.

Before media creation, a row is marked CREATING. If the process dies or the response
is ambiguous, UNCERTAIN rows retain their originals. Reconciliation looks for the
app's unique description marker in the assigned destination. Failure to find a
match does not cause a blind duplicate creation. Manual intervention may be needed
when the outcome cannot be established. This favors retaining photos over retries
that might create duplicates.

Cloud behavior is not yet verified against a live Google account. OAuth project
setup, upload interruption, account changes and album expiration need live testing.
The preview also needs durable-queue instrumentation and long session testing.
