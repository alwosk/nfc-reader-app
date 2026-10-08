import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
import urllib.request
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'pc'))
from core import Pairing, Server, Store
from runtime import Receiver


class PairingTests(unittest.TestCase):
    def test_code_expires_and_is_single_use(self):
        now = [100.0]
        p = Pairing(lambda: now[0])
        code = p.issue()
        self.assertRegex(code, r'^\d{6}$')
        self.assertTrue(p.consume(code))
        self.assertFalse(p.consume(code))
        code = p.issue()
        now[0] += 300
        self.assertFalse(p.consume(code))
        fresh = p.issue()
        self.assertTrue(p.consume(fresh))

    def test_five_wrong_attempts_lock_code_until_admin_reissues(self):
        p = Pairing()
        code = p.issue()
        wrong = str((int(code) + 1) % 1000000).zfill(6)
        for _ in range(5): self.assertFalse(p.consume(wrong))
        self.assertFalse(p.consume(code))
        self.assertTrue(p.consume(p.issue()))

    def test_pairing_token_works_but_code_cannot_be_reused(self):
        with tempfile.TemporaryDirectory() as folder:
            store = Store(folder)
            p = Pairing()
            code = p.issue()
            server = Server(('127.0.0.1', 0), store, 'a' * 32, p)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            try:
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                url = f'http://127.0.0.1:{server.server_port}'
                req = urllib.request.Request(url + '/pair', json.dumps({'code': code}).encode())
                with opener.open(req) as response:
                    token = json.load(response)['token']
                self.assertEqual(token, 'a' * 32)
                with self.assertRaises(urllib.error.HTTPError) as error:
                    opener.open(req)
                self.assertEqual(error.exception.code, 403)
                error.exception.close()
                req = urllib.request.Request(url + '/control', headers={'Authorization': 'Bearer ' + token})
                with opener.open(req) as response:
                    self.assertEqual(json.load(response)['count'], 0)
            finally:
                server.shutdown()
                server.server_close()


class RuntimeTests(unittest.TestCase):
    def test_boot_before_vpn_retries_and_exports_without_window(self):
        attempts = []
        class FakeServer:
            server_address = ('100.100.1.2', 8765)
            def serve_forever(self): pass
            def shutdown(self): pass
            def server_close(self): pass
        def factory(*args):
            attempts.append(args)
            if len(attempts) == 1: raise OSError('VPN not ready')
            return FakeServer()
        with tempfile.TemporaryDirectory() as folder:
            receiver = Receiver(folder, factory)
            receiver.configure('100.100.1.2')
            row = dict(id='1', employee_id='e1', name='직원', time='2026-10-08T09:00:00+09:00',
                       kind='출근', revision=1, reason='')
            receiver.store.receive([row])
            receiver.tick()
            self.assertIsNone(receiver.server)
            receiver.tick()
            self.assertIsNotNone(receiver.server)
            self.assertTrue((Path(folder) / '엑셀/근태기록_2026-10.xlsx').exists())
            token = receiver.config['token']
            receiver.stop()
            reopened = Receiver(folder, factory)
            self.assertEqual(reopened.config['token'], token)
            self.assertEqual(len(reopened.store.rows()), 1)


if __name__ == '__main__': unittest.main()
