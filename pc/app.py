"""Windows desktop receiver. Run with Python 3.12+ and requirements.txt."""
import json
import os
import secrets
import subprocess
import threading
from datetime import datetime
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog, ttk
from core import Store, Server, tailscale_ip

DATA = Path(os.environ.get('LOCALAPPDATA', str(Path.home()))) / 'Q52Attendance'

class App:
    def __init__(self, root):
        self.root=root; root.title('Q52 근태 수신기'); root.geometry('760x650')
        self.store=Store(DATA); self.server=None; self.exporting=False
        self.config_path=DATA/'config.json'
        if self.config_path.exists(): self.config=json.loads(self.config_path.read_text('utf-8'))
        else: self.config={'host':'','port':8765,'token':secrets.token_urlsafe(24)}
        box=ttk.Frame(root,padding=20); box.pack(fill='both',expand=True)
        ttk.Label(box,text='Q52 근태 수신기',font=('',22,'bold')).pack(anchor='w')
        ttk.Label(box,text='같은 Tailscale 네트워크의 Q52 한 대와 연결합니다. 창을 닫으면 수신이 중지됩니다.').pack(anchor='w',pady=8)
        ttk.Label(box,text='이 PC의 Tailscale IPv4 (100.x.x.x)').pack(anchor='w')
        self.host=tk.StringVar(value=self.config['host']);ttk.Entry(box,textvariable=self.host).pack(fill='x')
        ttk.Button(box,text='Tailscale 주소 찾기',command=self.detect_ip).pack(anchor='w',pady=4)
        ttk.Label(box,text='Q52 관리자 → PC 연결 설정에 입력할 주소').pack(anchor='w')
        self.url=tk.StringVar();ttk.Entry(box,textvariable=self.url,state='readonly').pack(fill='x')
        ttk.Label(box,text='연결 암호 (이 PC와 Q52에만 보관)').pack(anchor='w',pady=4)
        self.token=tk.StringVar(value=self.config['token']);ttk.Entry(box,textvariable=self.token,state='readonly').pack(fill='x')
        for label,fn in [('설정 저장 및 수신 시작',self.start),('Q52에 지금 전송 요청',self.request),('엑셀 다시 만들기',self.export),('다른 엑셀 양식에 넣기',self.template),('데이터 폴더 열기',self.open_folder),('DB 백업 파일 만들기',self.backup)]:
            ttk.Button(box,text=label,command=fn).pack(fill='x',pady=4)
        self.status=tk.StringVar(value='PC 주소를 입력한 후 수신을 시작하세요.')
        ttk.Label(box,textvariable=self.status,wraplength=700).pack(anchor='w',pady=12)
        self.export_status=tk.StringVar();ttk.Label(box,textvariable=self.export_status,wraplength=700).pack(anchor='w')
        self.host.trace_add('write',lambda *_:self.url.set('http://'+self.host.get().strip()+':8765'))
        self.url.set('http://'+self.host.get().strip()+':8765')
        if self.config['host']:root.after(300,self.start)
        root.after(2000,self.poll);root.protocol('WM_DELETE_WINDOW',self.close)
    def detect_ip(self):
        try:
            exe=Path(os.environ.get('PROGRAMFILES',r'C:\Program Files'))/'Tailscale'/'tailscale.exe'
            result=subprocess.run([str(exe),'ip','-4'],capture_output=True,text=True,timeout=5,check=True)
            value=result.stdout.strip().splitlines()[0]
            if not tailscale_ip(value):raise ValueError('Tailscale 주소가 없습니다.')
            self.host.set(value)
        except Exception:messagebox.showerror('Tailscale 확인','Tailscale 설치·로그인 상태를 확인하거나 앱에 표시된 PC의 100.x.x.x 주소를 직접 입력하세요.')
    def start(self):
        host=self.host.get().strip()
        if not tailscale_ip(host):messagebox.showerror('주소 확인','이 PC의 Tailscale IPv4 주소를 입력하세요. 예: 100.100.20.30');return
        if self.server:
            if self.server.server_address[0]==host:return
            messagebox.showinfo('다시 실행','주소를 변경하려면 프로그램을 닫았다가 다시 실행하세요.');return
        try:
            self.server=Server((host,8765),self.store,self.config['token'])
            threading.Thread(target=self.server.serve_forever,daemon=True).start()
            self.config['host']=host;self.config_path.write_text(json.dumps(self.config),encoding='utf-8')
        except Exception as e:self.server=None;messagebox.showerror('수신 시작 실패',f'Tailscale 연결, PC 주소, 중복 실행을 확인하세요.\n{e}')
    def request(self):
        if not self.server:messagebox.showerror('수신 중지','먼저 수신을 시작하세요.');return
        self.store.request();messagebox.showinfo('요청 저장','Q52가 연결되면 전송합니다. Q52 앱과 PC 연결 알림, Tailscale을 켜 두세요. 보통 10초 이내 요청을 확인하지만 절전 시 지연될 수 있습니다.')
    def export(self):
        if self.exporting:return
        self.exporting=True
        def work():
            try:self.store.export();self.store.export_error=''
            except Exception:self.store.export_error='엑셀 생성 대기: 열려 있는 근태 엑셀을 닫아주세요. 원본 DB 수신은 계속됩니다.'
            finally:self.exporting=False
        threading.Thread(target=work,daemon=True).start()
    def poll(self):
        c=self.store.control();pending=c['requested']>c['completed']
        self.status.set(('수신 중' if self.server else '수신 중지')+f" · PC 저장 {c['count']}건 · "+('Q52 전송 응답 대기' if pending else '전송 요청 대기 없음'))
        self.export_status.set(self.store.export_error or '엑셀: 데이터 폴더 → 엑셀 → 근태기록_연월.xlsx')
        self.export();self.root.after(10000,self.poll)
    def open_folder(self):os.startfile(DATA)
    def backup(self):
        destination=filedialog.asksaveasfilename(defaultextension='.db',initialfile='근태백업_'+datetime.now().strftime('%Y%m%d_%H%M%S')+'.db')
        if destination:
            if Path(destination).resolve()==self.store.db.resolve():messagebox.showerror('경로 오류','현재 DB와 다른 경로를 선택하세요.');return
            self.store.backup(destination);messagebox.showinfo('완료','DB 백업을 저장했습니다. 연결 설정과 Q52의 직원 등록 정보는 별도입니다.')
    def template(self):
        source=filedialog.askopenfilename(title='원본 엑셀 양식 선택',filetypes=[('Excel','*.xlsx')])
        if not source:return
        sheet=simpledialog.askstring('시트','기록을 넣을 시트 이름 (정확하게 입력)')
        if not sheet:return
        start=simpledialog.askinteger('시작 행','첫 데이터 행 번호 (머리글 다음 행)',minvalue=1,maxvalue=1048576)
        if start is None:return
        columns=simpledialog.askstring('열 배치','직원이름, 시간, 출퇴근을 넣을 열 번호 3개. 예: A,C,F 열은 1,3,6',initialvalue='1,2,3')
        if columns is None:return
        month=simpledialog.askstring('대상 월','YYYY-MM 형식',initialvalue=datetime.now().strftime('%Y-%m'))
        if month is None:return
        dest=filedialog.asksaveasfilename(title='새 파일로 저장 (원본과 다른 이름)',defaultextension='.xlsx',filetypes=[('Excel','*.xlsx')])
        if not dest:return
        if not messagebox.askokcancel('양식 확인','지정한 영역의 값은 근태 기록으로 대체됩니다. 해당 영역을 비워 둔 단순 표 양식을 사용하세요. 매크로·차트·외부연결·복잡한 서식 보존은 보장하지 않습니다. 원본 양식은 변경하지 않습니다.'):return
        try:
            count=self.store.export_template(source,dest,sheet,start,[int(x.strip()) for x in columns.split(',')],month)
            messagebox.showinfo('완료',f'{count}건을 새 파일에 저장했습니다.')
        except Exception as e:messagebox.showerror('양식 적용 실패',str(e))
    def close(self):
        if not messagebox.askokcancel('수신 중지','창을 닫으면 PC 수신이 중지됩니다. Q52 기록은 폰에 계속 저장됩니다. 닫을까요?'):return
        if self.server:self.server.shutdown();self.server.server_close()
        self.root.destroy()

if __name__=='__main__':
    root=tk.Tk();App(root);root.mainloop()
