#!/usr/bin/env python3
"""Capture only an explicitly named camera, or summarize an existing capture.

Requires Wireshark's dumpcap/tshark for capture analysis; never starts capture
implicitly, discovers targets, replays traffic, or sends camera commands.
"""
from __future__ import annotations

import argparse
import csv
import ipaddress
import json
import shutil
import subprocess
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

FIELDS = (
    "frame.time_epoch", "ip.src", "ip.dst", "tcp.srcport", "tcp.dstport",
    "udp.srcport", "udp.dstport", "frame.len", "tcp.flags.syn", "tcp.flags.ack",
)
WIRESHARK_MAC = Path("/Applications/Wireshark.app/Contents/MacOS")


def camera_address(value: str) -> str:
    try:
        address = ipaddress.IPv4Address(value)
    except ipaddress.AddressValueError as exc:
        raise argparse.ArgumentTypeError("Use the camera's numeric IPv4 address.") from exc
    ranges = ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
    if not any(address in ipaddress.IPv4Network(network) for network in ranges):
        raise argparse.ArgumentTypeError("Use the camera's private IPv4 address.")
    return str(address)


def executable(name: str) -> str:
    installed = shutil.which(name)
    if installed:
        return installed
    mac = WIRESHARK_MAC / name
    if mac.is_file():
        return str(mac)
    raise RuntimeError(f"{name} was not found. Install Wireshark or add it to PATH.")


def summarize(rows: Iterable[list[str]], camera: str) -> dict:
    """Analyze metadata only. A TCP SYN identifies a requested port, not support."""
    camera_address(camera)
    flows: dict[tuple, dict] = {}
    requested_camera_ports: Counter = Counter()
    requested_receiver_ports: Counter = Counter()
    first = last = None
    count = byte_count = ignored = 0
    for line_number, row in enumerate(rows, 1):
        if not row or not any(row):
            continue
        if len(row) != len(FIELDS):
            raise ValueError(f"Metadata row {line_number} has {len(row)} fields; expected {len(FIELDS)}.")
        values = dict(zip(FIELDS, row))
        src, dst = values["ip.src"], values["ip.dst"]
        if camera not in (src, dst):
            ignored += 1
            continue
        try:
            timestamp = float(values["frame.time_epoch"])
            length = int(values["frame.len"])
            if not (0 <= timestamp < 253402300800) or length < 0:
                raise ValueError("Invalid timestamp or frame length")
            transport = "TCP" if values["tcp.srcport"] else "UDP" if values["udp.srcport"] else "OTHER"
            prefix = transport.lower()
            source_port = int(values[f"{prefix}.srcport"]) if transport != "OTHER" else None
            dest_port = int(values[f"{prefix}.dstport"]) if transport != "OTHER" else None
            if any(port is not None and not 0 <= port <= 65535 for port in (source_port, dest_port)):
                raise ValueError("Invalid port")
        except (ValueError, KeyError) as exc:
            raise ValueError(f"Invalid packet metadata at row {line_number}: {exc}") from exc
        first = timestamp if first is None else min(first, timestamp)
        last = timestamp if last is None else max(last, timestamp)
        count += 1
        byte_count += length
        from_camera = src == camera
        peer = dst if from_camera else src
        camera_port = source_port if from_camera else dest_port
        peer_port = dest_port if from_camera else source_port
        key = (transport, peer, camera_port, peer_port)
        flow = flows.setdefault(key, {
            "transport": transport, "peer": peer, "cameraPort": camera_port,
            "peerPort": peer_port, "packetsFromCamera": 0, "packetsToCamera": 0,
            "frameBytesFromCamera": 0, "frameBytesToCamera": 0,
        })
        direction = "FromCamera" if from_camera else "ToCamera"
        flow[f"packets{direction}"] += 1
        flow[f"frameBytes{direction}"] += length
        if transport == "TCP" and values["tcp.flags.syn"] == "1" and values["tcp.flags.ack"] == "0":
            if from_camera:
                requested_receiver_ports[(peer, peer_port)] += 1
            else:
                requested_camera_ports[camera_port] += 1

    return {
        "schemaVersion": 1,
        "cameraAddress": camera,
        "cameraProtocolVerified": False,
        "note": "Observed packet metadata only. Ports and traffic volume do not prove pairing, JPEG transfer, or Android compatibility.",
        "packets": count,
        "frameBytes": byte_count,
        "ignoredPackets": ignored,
        "startedAt": datetime.fromtimestamp(first, timezone.utc).isoformat() if first is not None else None,
        "durationSeconds": round(last - first, 6) if first is not None else 0,
        "tcpPortsRequestedOnCamera": [
            {"port": port, "synPackets": n} for port, n in sorted(requested_camera_ports.items())
        ],
        "tcpPortsRequestedOnReceiver": [
            {"peer": peer, "port": port, "synPackets": n}
            for (peer, port), n in sorted(requested_receiver_ports.items())
        ],
        "flows": sorted(flows.values(), key=lambda f: (f["transport"], f["peer"], f["cameraPort"] or 0, f["peerPort"] or 0)),
    }


def analyze(path: Path, camera: str) -> dict:
    camera = camera_address(camera)
    if not path.is_file():
        raise ValueError(f"Capture does not exist: {path}")
    args = [executable("tshark"), "-n", "-r", str(path), "-Y", f"ip.addr == {camera}",
            "-T", "fields", "-E", "occurrence=f"]
    for field in FIELDS:
        args.extend(["-e", field])
    # No shell interpretation; IP and field expressions are constrained above.
    result = subprocess.run(args, check=True, capture_output=True, text=True)
    return summarize(csv.reader(result.stdout.splitlines(), delimiter="\t", quoting=csv.QUOTE_NONE), camera)


def capture(args: argparse.Namespace) -> None:
    if args.output.exists():
        raise ValueError("Output already exists. Choose a new capture filename.")
    if not args.output.parent.is_dir():
        raise ValueError("Create the capture output directory first.")
    # Capture camera traffic only, including its broadcasts. No broad network capture.
    subprocess.run([
        executable("dumpcap"), "-i", args.interface, "-f", f"host {args.camera_ip}",
        "-a", f"duration:{args.seconds}", "-a", "filesize:262144",
        "-w", str(args.output),
    ], check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("interfaces", help="List interfaces available to Wireshark")
    record = commands.add_parser("capture", help="Record a named camera for a bounded period")
    record.add_argument("--interface", required=True)
    record.add_argument("--camera-ip", type=camera_address, required=True)
    record.add_argument("--seconds", type=int, default=120)
    record.add_argument("--output", type=Path, required=True)
    summary = commands.add_parser("analyze", help="Summarize ports and traffic direction without exporting payloads")
    summary.add_argument("capture", type=Path)
    summary.add_argument("--camera-ip", type=camera_address, required=True)
    args = parser.parse_args()
    try:
        if args.command == "interfaces":
            subprocess.run([executable("dumpcap"), "-D"], check=True)
        elif args.command == "capture":
            if not 1 <= args.seconds <= 600:
                raise ValueError("Capture duration must be 1–600 seconds.")
            capture(args)
        else:
            print(json.dumps(analyze(args.capture, args.camera_ip), indent=2))
        return 0
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        if isinstance(exc, subprocess.CalledProcessError) and exc.stderr:
            print(exc.stderr.strip(), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
