"""Synthetic packet metadata tests; these are NOT OM-1 protocol fixtures."""
import argparse
import unittest
from camera_capture import camera_address, summarize


def packet(time="1.0", src="192.168.1.10", dst="192.168.1.20", sport="15740", dport="50000",
           udp=False, size="100", syn="0", ack="1"):
    return [time, src, dst, "" if udp else sport, "" if udp else dport,
            sport if udp else "", dport if udp else "", size, syn, ack]


class CaptureSummaryTest(unittest.TestCase):
    def test_bidirectional_flow_and_requested_camera_port(self):
        rows = [packet(src="192.168.1.20", dst="192.168.1.10", sport="50000", dport="15740", syn="1", ack="0"),
                packet(time="2.5", size="1500", syn="1"), packet(time="3.0", size="1000")]
        report = summarize(rows, "192.168.1.10")
        self.assertEqual(report["packets"], 3)
        self.assertEqual(report["frameBytes"], 2600)
        self.assertEqual(report["durationSeconds"], 2.0)
        self.assertEqual(report["tcpPortsRequestedOnCamera"], [{"port": 15740, "synPackets": 1}])
        self.assertEqual(report["tcpPortsRequestedOnReceiver"], [])
        self.assertEqual(report["flows"][0]["packetsToCamera"], 1)
        self.assertEqual(report["flows"][0]["frameBytesFromCamera"], 2500)
        self.assertFalse(report["cameraProtocolVerified"])

    def test_camera_initiated_connection(self):
        report = summarize([packet(sport="50000", dport="9000", syn="1", ack="0")], "192.168.1.10")
        self.assertEqual(report["tcpPortsRequestedOnCamera"], [])
        self.assertEqual(report["tcpPortsRequestedOnReceiver"], [{"peer": "192.168.1.20", "port": 9000, "synPackets": 1}])

    def test_udp_broadcast_keeps_direction_without_claiming_listener(self):
        report = summarize([packet(dst="239.255.255.250", udp=True, dport="3702")], "192.168.1.10")
        self.assertEqual(report["flows"][0]["transport"], "UDP")
        self.assertEqual(report["flows"][0]["peer"], "239.255.255.250")
        self.assertEqual(report["tcpPortsRequestedOnReceiver"], [])

    def test_unrelated_packets_ignored(self):
        report = summarize([packet(src="10.0.0.1", dst="10.0.0.2")], "192.168.1.10")
        self.assertEqual(report["packets"], 0)
        self.assertEqual(report["ignoredPackets"], 1)
        self.assertIsNone(report["startedAt"])

    def test_empty_capture_is_not_success(self):
        report = summarize([], "192.168.1.10")
        self.assertEqual(report["flows"], [])
        self.assertFalse(report["cameraProtocolVerified"])

    def test_out_of_order_timestamps(self):
        report = summarize([packet(time="3"), packet(time="1")], "192.168.1.10")
        self.assertEqual(report["durationSeconds"], 2)

    def test_invalid_metadata_rejected(self):
        for row in (["short"], packet(time="nan"), packet(time="inf"), packet(size="-1"), packet(sport="99999")):
            with self.assertRaises(ValueError):
                summarize([row], "192.168.1.10")

    def test_non_tcp_udp_packet(self):
        row = ["1", "192.168.1.10", "192.168.1.20", "", "", "", "", "64", "", ""]
        report = summarize([row], "192.168.1.10")
        self.assertEqual(report["flows"][0]["transport"], "OTHER")

    def test_address_validation(self):
        for addr in ("192.168.0.10", "10.0.0.2", "172.16.0.1"):
            self.assertEqual(camera_address(addr), addr)
        for addr in ("8.8.8.8", "127.0.0.1", "224.0.0.1", "camera.local", "10.0.0.1;id", "172.32.0.1"):
            with self.assertRaises(argparse.ArgumentTypeError):
                camera_address(addr)


if __name__ == "__main__":
    unittest.main()
