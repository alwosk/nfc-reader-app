"""Windows tray receiver. The settings window may be hidden without stopping work."""
import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from runtime import Receiver, data_folder

DATA = data_folder()


class SingleInstance:
    def __init__(self):
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        self.kernel.CreateMutexW.argtypes = [ctypes.c_void_p, wintypes.BOOL, wintypes.LPCWSTR]
        self.kernel.CreateMutexW.restype = wintypes.HANDLE
        self.kernel.CreateEventW.argtypes = [ctypes.c_void_p, wintypes.BOOL, wintypes.BOOL, wintypes.LPCWSTR]
        self.kernel.CreateEventW.restype = wintypes.HANDLE
        self.kernel.SetEvent.argtypes = [wintypes.HANDLE]
        self.kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        self.kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self.mutex = self.kernel.CreateMutexW(None, False, 'Local\\AttendanceReceiver')
        if not self.mutex:
            raise ctypes.WinError(ctypes.get_last_error())
        self.existing = ctypes.get_last_error() == 183
        self.event = self.kernel.CreateEventW(None, False, False, 'Local\\AttendanceReceiver.Show')
        if not self.event:
            raise ctypes.WinError(ctypes.get_last_error())

    def signal(self):
        self.kernel.SetEvent(self.event)

    def requested(self):
        return self.kernel.WaitForSingleObject(self.event, 0) == 0

    def close(self):
        self.kernel.CloseHandle(self.event)
        self.kernel.CloseHandle(self.mutex)


class App:
    def __init__(self, root, instance, background=False):
        self.root = root
        self.instance = instance
        self.messages = queue.Queue()
        self.receiver = Receiver(DATA)
        root.title('근태 수신기')
        root.geometry('680x660')
        root.protocol('WM_DELETE_WINDOW', root.withdraw)
        box = ttk.Frame(root, padding=22)
        box.pack(fill='both', expand=True)
        ttk.Label(box, text='근태 수신기', font=('', 22, 'bold')).pack(anchor='w')
        ttk.Label(box, text='창을 닫아도 수신합니다. 작업표시줄 알림 영역에서 다시 열 수 있습니다.').pack(anchor='w', pady=8)
        ttk.Label(box, text='이 PC의 Tailscale IPv4 주소').pack(anchor='w')
        self.host = tk.StringVar(value=self.receiver.config['host'])
        ttk.Entry(box, textvariable=self.host).pack(fill='x')
        row = ttk.Frame(box)
        row.pack(fill='x', pady=6)
        ttk.Button(row, text='주소 찾기', command=self.detect_ip).pack(side='left')
        ttk.Button(row, text='설정 저장', command=self.save).pack(side='left', padx=8)
        ttk.Label(box, text='단말에 입력할 PC 주소').pack(anchor='w')
        self.url = tk.StringVar()
        ttk.Entry(box, textvariable=self.url, state='readonly').pack(fill='x')
        self.host.trace_add('write', lambda *_: self.url.set('http://' + self.host.get().strip() + ':8765'))
        self.url.set('http://' + self.host.get().strip() + ':8765')
        ttk.Label(box, text='처음 연결할 때만 숫자 6자리를 입력하세요.').pack(anchor='w', pady=(14, 4))
        self.code = tk.StringVar(value='연결 코드를 발급하세요')
        ttk.Label(box, textvariable=self.code, font=('', 18, 'bold')).pack(anchor='w')
        ttk.Button(box, text='새 연결 코드', command=self.issue_code).pack(anchor='w', pady=6)
        ttk.Separator(box).pack(fill='x', pady=12)
        ttk.Button(box, text='단말에 지금 전송 요청', command=self.request).pack(fill='x', pady=4)
        ttk.Button(box, text='근태 엑셀 폴더 열기', command=self.open_folder).pack(fill='x', pady=4)
        ttk.Label(box, text='엑셀 저장 폴더').pack(anchor='w', pady=(10, 2))
        self.excel_path = tk.StringVar(value=str(self.receiver.excel_folder))
        path_row = ttk.Frame(box)
        path_row.pack(fill='x')
        ttk.Entry(path_row, textvariable=self.excel_path, state='readonly').pack(side='left', fill='x', expand=True)
        ttk.Button(path_row, text='폴더 변경', command=self.change_folder).pack(side='right', padx=(8, 0))
        startup_dir = Path(os.environ['APPDATA']) / 'Microsoft/Windows/Start Menu/Programs/Startup'
        self.autostart = tk.BooleanVar(value=(startup_dir / 'Attendance Receiver.lnk').exists())
        ttk.Checkbutton(box, text='Windows 로그인 시 백그라운드 자동 실행',
                        variable=self.autostart, command=self.set_startup).pack(anchor='w', pady=12)
        self.status = tk.StringVar()
        ttk.Label(box, textvariable=self.status, wraplength=610).pack(anchor='w')
        self.tray = self.make_tray()
        self.tray.run_detached()
        self.receiver.start()
        root.after(500, self.poll)
        if not background or not self.receiver.config['host']:
            self.show()

    def make_tray(self):
        import pystray
        from PIL import Image, ImageDraw
        icon = Image.new('RGB', (64, 64), '#17436a')
        draw = ImageDraw.Draw(icon)
        draw.line([(14, 32), (27, 45), (51, 19)], fill='white', width=7)
        def action(name):
            return lambda *_: self.messages.put(name)
        return pystray.Icon('attendance', icon, '근태 수신기', pystray.Menu(
            pystray.MenuItem('설정 열기', action('show'), default=True),
            pystray.MenuItem('지금 전송 요청', action('request')),
            pystray.MenuItem('근태 엑셀 폴더', action('folder')),
            pystray.MenuItem('완전히 종료', action('quit'))))

    def show(self):
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def save(self):
        try:
            self.receiver.configure(self.host.get().strip())
            self.status.set('설정 저장 완료 · 잠시 후 연결됩니다.')
        except Exception as e:
            messagebox.showerror('설정 확인', str(e))

    def detect_ip(self):
        try:
            exe = Path(os.environ.get('PROGRAMFILES', r'C:\Program Files')) / 'Tailscale/tailscale.exe'
            r = subprocess.run([str(exe), 'ip', '-4'], capture_output=True, text=True,
                               timeout=5, check=True, creationflags=subprocess.CREATE_NO_WINDOW)
            self.host.set(r.stdout.strip().splitlines()[0])
        except Exception:
            messagebox.showerror('주소 확인', 'Tailscale 설치·로그인을 확인하거나 PC의 100.x.x.x 주소를 입력하세요.')

    def issue_code(self):
        if not self.receiver.server:
            messagebox.showinfo('연결 대기', 'PC 주소를 저장하고 수신이 시작될 때까지 기다려주세요.')
            return
        self.receiver.pairing.issue()
        self.code.set(self.receiver.pairing.status())

    def request(self):
        self.receiver.store.request()
        self.status.set('전송 요청을 저장했습니다. 단말이 연결되면 처리합니다.')

    def open_folder(self):
        try:
            folder = self.receiver.excel_folder
            folder.mkdir(parents=True, exist_ok=True)
            os.startfile(folder)
        except OSError as e:
            messagebox.showerror('폴더 열기 실패', f'저장 폴더 연결과 권한을 확인하세요.\n{e}')

    def change_folder(self):
        destination = filedialog.askdirectory(title='근태 엑셀 저장 폴더 선택',
                                               initialdir=str(self.receiver.excel_folder), mustexist=True)
        if not destination:
            return
        try:
            self.receiver.set_excel_folder(destination)
            self.excel_path.set(str(self.receiver.excel_folder))
            messagebox.showinfo('저장 폴더 변경', '선택한 폴더에 전체 근태 엑셀을 자동 생성합니다.\n기존 폴더의 파일은 그대로 남습니다.')
        except OSError as e:
            messagebox.showerror('폴더 변경 실패', f'쓰기 가능한 폴더를 선택하세요.\n{e}')

    def set_startup(self):
        script = Path(__file__).with_name('enable-startup.ps1')
        args = ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(script)]
        if not self.autostart.get():
            args.append('-Disable')
        try:
            subprocess.run(args, check=True, capture_output=True, timeout=15,
                           creationflags=subprocess.CREATE_NO_WINDOW)
        except Exception:
            self.autostart.set(not self.autostart.get())
            messagebox.showerror('자동 실행 설정 실패', '프로그램 폴더 위치와 Windows 권한을 확인하세요.')

    def poll(self):
        if self.instance.requested():
            self.show()
        while not self.messages.empty():
            action = self.messages.get_nowait()
            if action == 'quit':
                self.quit()
                return
            {'show': self.show, 'request': self.request, 'folder': self.open_folder}[action]()
        control = self.receiver.store.control()
        waiting = control['requested'] > control['completed']
        self.status.set(self.receiver.status + f" · {control['count']}건 저장" +
                        (' · 전송 응답 대기' if waiting else '') + '\n' + self.receiver.export_status)
        self.code.set(self.receiver.pairing.status())
        self.root.after(500, self.poll)

    def quit(self):
        self.receiver.stop()
        self.tray.stop()
        self.root.destroy()


if __name__ == '__main__':
    instance = SingleInstance()
    if instance.existing:
        if '--background' not in sys.argv:
            instance.signal()
        instance.close()
        sys.exit(0)
    root = tk.Tk()
    root.withdraw()
    try:
        App(root, instance, '--background' in sys.argv)
        root.mainloop()
    except Exception as e:
        messagebox.showerror('근태 수신기 실행 오류', f'install.cmd를 실행했는지 확인하세요.\n{e}')
    finally:
        instance.close()
