import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'pc'))
from core import receiver_ip
from runtime import Receiver


class NetworkTests(unittest.TestCase):
    def test_supported_ranges_and_rejected_addresses(self):
        for host in ['10.0.0.1', '10.255.255.254', '172.16.0.1', '172.31.255.254',
                     '192.168.0.1', '100.64.0.1', '100.127.255.254']:
            with self.subTest(host=host): self.assertTrue(receiver_ip(host))
        for host in ['172.15.0.1', '172.32.0.1', '192.169.0.1', '100.63.0.1',
                     '100.128.0.1', '8.8.8.8', '127.0.0.1', '0.0.0.0', '169.254.1.2',
                     '::1', 'localhost', '192.168.0.256', '192.168.01.2', '10.1.2.3.', '']:
            with self.subTest(host=host): self.assertFalse(receiver_ip(host))

    def test_switch_from_tailscale_to_lan_preserves_data_and_rebinds(self):
        servers = []
        class FakeServer:
            def __init__(self, address, *args):
                self.server_address = address
                self.closed = False
                servers.append(self)
            def serve_forever(self): pass
            def shutdown(self): pass
            def server_close(self): self.closed = True
        with tempfile.TemporaryDirectory() as folder:
            receiver = Receiver(folder, FakeServer)
            receiver.configure('100.100.1.2')
            receiver.store.receive([dict(id='1', employee_id='e', name='직원',
                time='2026-10-08T09:00:00+09:00', kind='출근', revision=1, reason='')])
            token = receiver.config['token']
            receiver.tick()
            receiver.configure('192.168.0.10')
            self.assertTrue(servers[0].closed)
            receiver.tick()
            self.assertEqual(receiver.server.server_address, ('192.168.0.10', 8765))
            with self.assertRaises(ValueError): receiver.configure('0.0.0.0')
            receiver.stop()
            reopened = Receiver(folder, FakeServer)
            self.assertEqual(reopened.config['host'], '192.168.0.10')
            self.assertEqual(reopened.config['token'], token)
            self.assertEqual(len(reopened.store.rows()), 1)
