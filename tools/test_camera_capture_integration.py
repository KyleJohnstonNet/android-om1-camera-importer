"""Real tshark parser, synthetic packets only. Skips when Wireshark is absent."""
import struct
import tempfile
import unittest
from pathlib import Path
from camera_capture import analyze, executable


try:
    executable("tshark")
    HAS_TSHARK = True
except RuntimeError:
    HAS_TSHARK = False


@unittest.skipUnless(HAS_TSHARK, "Install Wireshark for the packet-parser integration tests")
class TsharkIntegrationTest(unittest.TestCase):
    def analyze_frame(self, src, dst, protocol, transport):
        ip = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(transport), 1, 0, 64,
                         protocol, 0, bytes(src), bytes(dst))
        frame = bytes.fromhex("0200000000020200000000010800") + ip + transport
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic-not-camera-evidence.pcap"
            path.write_bytes(struct.pack("<IHHIIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 1)
                             + struct.pack("<IIII", 1, 0, len(frame), len(frame)) + frame)
            return analyze(path, "192.168.1.10")

    def test_tcp_syn_direction_from_tshark(self):
        tcp = struct.pack("!HHIIBBHHH", 50000, 15740, 1, 0, 0x50, 2, 65535, 0, 0)
        report = self.analyze_frame([192, 168, 1, 20], [192, 168, 1, 10], 6, tcp)
        self.assertEqual(report["tcpPortsRequestedOnCamera"], [{"port": 15740, "synPackets": 1}])
        self.assertFalse(report["cameraProtocolVerified"])

    def test_udp_endpoints_from_tshark(self):
        payload = b"SYNTHETIC_TEST_NOT_CAMERA_EVIDENCE"
        udp = struct.pack("!HHHH", 40000, 3702, 8 + len(payload), 0) + payload
        report = self.analyze_frame([192, 168, 1, 10], [239, 255, 255, 250], 17, udp)
        self.assertEqual(report["packets"], 1)
        self.assertEqual(report["flows"][0]["peerPort"], 3702)
        self.assertEqual(report["tcpPortsRequestedOnReceiver"], [])


if __name__ == "__main__":
    unittest.main()
