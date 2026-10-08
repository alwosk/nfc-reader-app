# Q52 NFC 출퇴근

LG Q52(Android 12)에 직원별 NFC 스티커를 찍어 기록하고, Tailscale을 통해 Windows 11 PC로 전송하는 소규모 근태 시스템입니다. 직원 폰용 앱과 웹 관리자 페이지는 없습니다.

## 다운로드

비공개 저장소에 로그인한 뒤 **Code → Download ZIP**으로 받으세요. 압축을 푼 `dist` 폴더에 설치 자료가 있습니다.

- [Q52 설치용 APK (v0.1.0 시험판)](dist/q52-attendance-v0.1.0.apk)
- [Windows PC 수신기](dist/windows-receiver-v0.1.0.zip) — Python 3.12 이상(3.14 지원)에서 `install.cmd`, `start.cmd` 순서
- [직원용 설명서 PDF](dist/employee-guide.pdf) / [Typst 원본](docs/employee.typ)
- [관리자용 설명서 PDF](dist/admin-guide.pdf) / [Typst 원본](docs/admin.typ)

## 기능

- 출근/퇴근 버튼 선택 후 태그, 직원당 하루 각 1회, 한국 시간 기록
- Q52 로컬 SQLite 저장, PC가 꺼져 있어도 기록
- 약 1시간 자동 전송, Q52 수동 전송, PC에서 전송 요청
- PC 요청은 DB에 보관하며 Q52의 연결 서비스가 약 10초 간격으로 확인
- DB 저장 확인 후 ACK, 재전송 중복 방지, 수정 버전 반영
- Q52 관리자 PIN, 태그 등록/해제, 누락 수동 입력, 같은 날짜의 시간 정정
- Windows 수신기 및 월별 XLSX: `직원이름 / 시간 / 출근인지 퇴근인지`
- 다른 단순 XLSX 양식의 시트·행·열을 지정해 새 파일로 출력
- Typst 설명서 2종 및 Windows 로그인 후 자동 실행 스크립트

## 범위와 확인 사항

- **실기기 파일럿**: Q52 NFC, Windows GUI/방화벽, Tailscale 실제 연결은 현장에서 확인해야 합니다. 개발 환경에서 APK 빌드와 PC 자동 테스트를 수행합니다.
- 삼성월렛 디지털 키는 지원 확정이 아닙니다. 지역·모델별 NFC 출입카드 기능과 Q52의 고정 식별값 인식 여부를 실물로 검증해야 합니다. 차량/도어록 키를 자동으로 지원하는 앱이 아닙니다. 직원 설명서에 조건과 시험 절차를 명시했습니다.
- Q52 앱 화면을 열고 NFC를 켜서 사용합니다. 재부팅 뒤에는 Tailscale과 앱을 열어주세요. 절전/강제 종료 시 PC 요청을 즉시 처리한다고 보장하지 않습니다.
- 직원 동명이인은 이름에 구분 표기를 넣습니다. 자정 넘기는 근무, 급여 계산, 기존 기록의 날짜·종류 변경/삭제, 직원 명부 복원은 미지원입니다.
- 월별 XLSX는 파생 파일입니다. 원본은 PC DB이며 정정은 Q52에서 합니다. 기존 엑셀 원본은 보존하고 새 파일로 내보냅니다. 달력형/직원별 집계 양식은 실제 양식을 받아 추가 매핑해야 합니다.
- 시험 APK는 debug 서명입니다. 정식 운영 전에 관리자 소유 서명 키·업데이트 전환을 계획하세요. 앱을 삭제하면 로컬 데이터가 지워집니다.
- 근태 데이터·연결 암호·서명 키는 GitHub에 커밋하지 않습니다.

## 개발

Android: JDK 17+, SDK 35, Gradle 8.13 / AGP 8.9.2. `android`에서 `./gradlew assembleDebug`.
PC: Python 3.12 이상(3.14 지원), `pip install -r pc/requirements.txt`, `python pc/app.py`.
검증: `python -m unittest discover -s tests -v`, Android는 `./gradlew testDebugUnitTest lintDebug`.
설명서: Noto Sans CJK KR 폰트를 설치하고 `typst compile docs/admin.typ dist/admin-guide.pdf` (직원용도 동일).

## 통신

PC는 Tailscale IPv4의 TCP 8765에서만 수신합니다. HTTP는 Tailscale 암호화 터널 안에서 사용하며 별도 Bearer 연결 암호를 검사합니다. 외부 포트포워딩은 필요 없습니다. PC 하나·Q52 하나를 전제로 합니다.

`GET /control` → 요청 번호 확인, `POST /records` → 최대 200건 원자적 저장, `POST /complete` → 완료된 요청 번호 반영. 전송 중 새 요청이 생겨도 이전 완료 응답이 새 요청을 지우지 않습니다.
