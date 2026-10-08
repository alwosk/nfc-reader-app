"""Receiver lifecycle independent of the settings window."""
import json
import os
import secrets
import threading
from pathlib import Path
from core import Pairing, Server, Store, tailscale_ip


def data_folder():
    base = Path(os.environ.get('LOCALAPPDATA', str(Path.home())))
    legacy = base / 'Q52Attendance'
    # Keep existing records/settings when updating the first release.
    return legacy if (legacy / 'attendance.db').exists() else base / 'AttendanceReceiver'


class Receiver:
    def __init__(self, folder, server_factory=Server):
        self.store = Store(folder)
        self.path = Path(folder) / 'config.json'
        self.config = json.loads(self.path.read_text('utf-8')) if self.path.exists() else {
            'host': '', 'port': 8765, 'token': secrets.token_urlsafe(24)}
        self.pairing = Pairing()
        self.server_factory = server_factory
        self.server = None
        self.lock = threading.Lock()
        self.status = 'PC 주소를 설정하세요.'
        self.export_status = ''
        self.stop_event = threading.Event()
        self.worker = None

    def configure(self, host):
        if not tailscale_ip(host):
            raise ValueError('이 PC의 Tailscale IPv4 주소를 입력하세요.')
        with self.lock:
            self.config['host'] = host
            tmp = self.path.with_suffix('.tmp')
            tmp.write_text(json.dumps(self.config), encoding='utf-8')
            os.replace(tmp, self.path)
            if self.server and self.server.server_address[0] != host:
                self.server.shutdown()
                self.server.server_close()
                self.server = None

    def tick(self):
        with self.lock:
            if self.config['host'] and self.server is None:
                try:
                    self.server = self.server_factory(
                        (self.config['host'], 8765), self.store, self.config['token'], self.pairing)
                    threading.Thread(target=self.server.serve_forever, daemon=True).start()
                except OSError:
                    self.server = None
                    self.status = 'Tailscale 연결 대기 · 자동으로 다시 연결합니다.'
            if self.server:
                self.status = '백그라운드 수신 중'
        try:
            self.store.export()
            self.export_status = ''
        except Exception:
            self.export_status = '엑셀 갱신 대기 · 열어둔 근태 엑셀을 닫으면 자동 갱신됩니다.'

    def run(self):
        while not self.stop_event.is_set():
            try:
                self.tick()
            except Exception:
                self.status = '수신 상태 확인 필요 · 자동으로 다시 시도합니다.'
            self.stop_event.wait(10)

    def start(self):
        self.worker = threading.Thread(target=self.run, daemon=True)
        self.worker.start()

    def stop(self):
        self.stop_event.set()
        if self.worker:
            self.worker.join()
        with self.lock:
            if self.server:
                self.server.shutdown()
                self.server.server_close()
                self.server = None
