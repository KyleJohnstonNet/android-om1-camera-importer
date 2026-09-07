# OM-1 receiver feasibility experiment

This experiment determines whether the phone can replace the desktop receiver.
The diagnostic APK alone does not pair or receive photographs.

## Evidence and unknowns

- [Original OM-1 manual](https://download.omsystem.com/pages/inst/om1/manual_om1_v1.7_ENU.pdf):
  initial PC pairing via USB (pp. 269–270), PC Wi-Fi configuration (pp. 271–275),
  and automatic transfer while shooting (pp. 276–277).
- [OM Capture features](https://software.omsystem.com/omcapture/en/features.html):
  original OM-1 supports image transfer through a wireless access point.
- [db link](https://github.com/azqeurio/db_link), inspected at
  `33b1f7e3d311967ed17a675a8561748977623fe3`: author reports OM-1 support for
  smartphone Wi-Fi and USB workflows. That is not evidence of a PC-mode Wi-Fi
  receiver. No source from that project is incorporated here.
- [Android USB host APIs](https://developer.android.com/develop/connectivity/usb/host)
  provide access to USB devices, but do not implement OM's pairing procedure.

Unknown: pairing registration, network discovery, which side opens connections,
framing/authentication, JPEG retrieval/acknowledgment, and recovery after disconnect.
Do not assume PTP/IP, HTTP, a port number, or camera-IP discovery without a recording.

## 1. Establish the official baseline

Equipment: OM-1 and card with some test JPEGs, Android hotspot/cellular service,
Mac or Windows machine with official OM Capture, USB cable, Wireshark.
OM Workspace is not the OM Capture application. Obtain OM Capture from
[OM's software page](https://software.omsystem.com/omcapture/en/index.html).

1. Record firmware, phone OS/build and OM Capture version in the result template.
2. Enable the Android hotspot. Join the computer and camera to it.
3. Follow the manual's USB **RAW/Control → Create new link** procedure to pair
   the computer. Do not reset existing pairings; choose an unused pairing slot.
4. Disconnect USB; select **PC Connection** on the camera and Wi-Fi in OM Capture.
5. Configure JPEG transfer from the card slot in use. Take three new pictures
   with the physical shutter. Confirm full-sized files arrive on the computer.
6. Record what happens with a burst, camera playback, screen-off phone, a Wi-Fi
   disconnect, and pictures taken during the disconnect. Keep originals on the card.

If this baseline fails, resolve it before attempting to interpret app failures.
A computer connected to the hotspot proves the camera baseline, not that an Android
app on the hotspot host can receive the same traffic.

## 2. Record camera network traffic

On the receiving computer, find the camera's IP from its network details and choose
the Wi-Fi capture interface. This tool does not scan the network or guess the IP.

```sh
python3 tools/camera_capture.py interfaces
mkdir -p captures
# Substitute actual interface and camera IP. These are examples, not OM-1 defaults.
python3 tools/camera_capture.py capture --interface en0 --camera-ip 192.168.1.10 \
  --seconds 120 --output captures/om1-baseline.pcapng
```

Start the recording before connecting OM Capture, then take three fresh JPEGs and
disconnect. Capture stops after the requested duration or 256 MiB. If Wireshark
reports insufficient capture permissions, configure Wireshark's supported capture
permissions; this script does not change privileges automatically. The receiver's
own interface is sufficient—do not enable monitor mode to collect unrelated traffic.

```sh
python3 tools/camera_capture.py analyze captures/om1-baseline.pcapng \
  --camera-ip 192.168.1.10 > captures/om1-baseline-summary.json
```

The JSON shows packet counts, frame bytes, endpoints and TCP SYN direction. A
requested port is not necessarily open; SYN retransmissions count as separate
packets. Payload is excluded from JSON. Inspect the original capture locally when
decoding framing; do not share raw captures publicly since they can contain photos
or pairing material. Initial USB registration needs a separate observation path;
this network capture does not capture USB, and Android descriptor inspection does
not disclose the commands. Prefer an available documented SDK or a USB capture on
a supported host over guessing vendor commands.

## 3. Check Android prerequisites

Install the diagnostic APK. Connect the camera by USB to the phone and tap
**Inspect USB and networks**; export the report. USB-debugging to the Mac and direct
camera USB connection may require separate test steps or wireless ADB.

Enable the hotspot and inspect again. Only if the camera accepted inbound TCP in
the baseline, use that observed address and port in the app's **Check once**.
For camera-initiated connections, an outbound port check is not a valid test of
receiver feasibility. Keep system network/VPN restrictions as used normally;
record any restriction that prevents local connectivity instead of bypassing it.

Test a long recording with screen off. Export the report to inspect actual sample
gaps and route changes. This does not test camera transfer or cloud uploads.

## 4. Gate acceptance

The gate passes only when an independently implemented Android receiver:

- Registers/pairs successfully and receives original JPEGs while the physical
  shutter remains usable, with the phone as hotspot host and no computer relay.
- Saves files matching card originals byte-for-byte; handles partial transfers
  without marking them complete and leaves camera files intact.
- Continues with screen off, reconnects after camera/network interruptions, and
  makes missing/offline-shot limitations explicit.
- Demonstrates a viable path for catch-up, or records that requirement as blocked
  for an explicit product decision; success is not inferred from TCP traffic.

Use [hardware-results.example.json](hardware-results.example.json) for results.
All initial outcomes are `not_run`, not passing fixtures.
