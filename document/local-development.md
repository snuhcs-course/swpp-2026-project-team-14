# 개인 로컬 테스트 환경

## Gemini 분석 실행

1. 저장소 루트 `.env`에 `GEMINI_API_KEY=발급받은키`를 입력한다. `.env.example`은 빈 예시이며 실제 키를 넣지 않는다. 모델은 서버 코드의 `gemini-3.1-flash-lite`로 고정한다. OS 환경변수가 있으면 .env보다 우선한다.
2. `./local-dev/dev.ps1 setup`으로 의존성을 설치한다. python-dotenv는 .env 로딩, Pillow는 이미지 검증·축소에 사용하며 Gemini는 표준 라이브러리 REST로 호출한다.
3. 서버가 실행 중이면 `./local-dev/dev.ps1 stop` 후 `./local-dev/dev.ps1 start`로 다시 시작한다. .env 변경은 서버 재시작 후 반영된다.
4. USB 연결 실기기는 Android Studio Terminal에서 `& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -d reverse tcp:8001 tcp:8001`을 실행한다. USB 재연결 후 다시 설정한다. 앱은 실기기에서 127.0.0.1:8001, 에뮬레이터에서 10.0.2.2:8001을 사용한다.
5. 앱을 다시 빌드·실행하고 `촬영하기 → 측정 → 확인 → 분석하기`를 누른다. 사진이 PC의 Django를 거쳐 Gemini에 전송되며 한글 분석 결과를 표시한다. 수정·메모 입력 후 `옷장에 추가`를 누르면 저장된다. 목록 카드를 눌러 다시 수정할 수 있다.

이 API는 인증 없는 루프백 개발용이다. 공용 서버에 배포하지 않는다. 키는 Android에 전달하지 않는다. 옷 정보·치수·메모는 MySQL에, 정규화 사진은 `.local/wardrobe-media/`에 저장한다. S3는 아직 연결하지 않는다. 오류 후 자동 재시도는 하지 않는다. 회전 시 진행 중 요청은 ViewModel로 유지하고 사진 변경 시 이전 결과를 폐기한다.

키 없는 검증: `./.local/venv/Scripts/python.exe local-dev/backend/manage.py test wardrobe`.

## 수동 AR 실측 테스트

1. USB 디버깅을 허용한 Galaxy S23 Ultra를 연결하고 `./local-dev/android.ps1 run`으로 앱을 설치·실행한다.
2. 옷장의 `촬영하기`를 선택하고 카메라 권한·Google Play Services for AR 설치를 허용한다.
3. 옷을 바닥에 평평하게 펴고 주변 바닥을 천천히 비춘다. 중앙 십자가 인식된 수평 평면 위에 있어야 촬영할 수 있다.
4. 촬영 후 상의·얇은 아우터·바지 및 측정 항목을 선택하고 사진 위 두 점을 지정한다. 상의 어깨·총장은 뒷면 기준이다.
5. 선과 값을 확인하고 `확인`을 누르면 기존 사진 확인 화면에 AR 추정값이 표시된다. 서버는 이 테스트에 필요하지 않다.

처음에는 동일한 옷을 줄자로 측정한 값과 여러 번 비교한다. 오차 검증은 미완료이며 AR 평면 위의 직선 거리만 계산한다. 사진을 고정하므로 휴대폰을 움직여도 편집 중 좌표는 변하지 않는다. 인식된 평면 밖의 점은 선택할 수 없다. 같은 항목 다시 지정, 항목 삭제, 재촬영, 취소 시 기존 사진 유지, 백그라운드 복귀를 확인한다. 화면 회전·프로세스 종료로 측정 화면이 재생성되면 AR 촬영을 다시 시작한다.

저장 전 사진은 앱 캐시, 측정값은 임시 화면 상태에 둔다. 분석 후 확정 저장하면 사진과 데이터는 PC 서버에 유지되고 앱 재실행 시 목록을 불러온다. AR 미지원 기기에서는 촬영을 사용할 수 없다.

## 목적과 범위

팀 공통 FE·BE 초기 설정 전, Android Compose → Django → MySQL의 실제 요청·저장·조회 경로를 검증한다. 실행 코드는 `local-dev/`, 데이터·로그·가상환경·비밀 설정은 Git에서 제외하는 `.local/`에 둔다.

테스트 화면은 연결 확인, 테스트 이름 저장, 최근 100개 조회를 제공한다. Gemini 사진 분석은 별도 wardrobe API가 담당하며 편집·MySQL 옷장 저장·조회도 담당한다. S3·익명 인증은 아직 구현하지 않는다. `/api/dev/`와 ProbeItem은 테스트 전용이며 wardrobe-spec.md의 정식 계약을 대체하지 않는다.

루트 backend/와 infra/ 및 기존 배포 워크플로는 변경하지 않는다. `local-dev/` 변경만으로 기존 backend 배포 트리거가 실행되지 않는다. 별도 Windows 서비스 설치나 기존 MySQL 서비스의 설정 변경은 수행하지 않는다.

## 구성

| 구성요소 | 설정 |
| --- | --- |
| FE | Kotlin 2.2.10, Jetpack Compose, AGP 8.12.0, Gradle 8.13 |
| Android | compile/target SDK 36, min SDK 26, JDK 17 또는 21 |
| BE | Python 3.12 기준, Django 5.2.17, 개발용 runserver |
| DB | 설치된 MySQL 8.0 실행 파일로 별도 인스턴스 실행 |
| API 주소 | PC: http://127.0.0.1:8001, 에뮬레이터: http://10.0.2.2:8001 |
| DB 주소 | 127.0.0.1:3307, stylemate_local 데이터베이스 |
| 데이터 위치 | .local/mysql/ |
| 테스트 DB | test_stylemate_local. Django 테스트 수행 시 생성·삭제 |
| 인증 범위 | 서버·DB는 PC 루프백에만 바인딩. 개인 연결 확인용이며 공용 서버에 배포하지 않음 |

추가 Python 의존성은 Django의 MySQL 드라이버인 mysqlclient와 프로젝트 소유 프로세스 확인용 psutil이다. Android는 플랫폼 HttpURLConnection을 사용하여 별도 HTTP 라이브러리를 추가하지 않는다.

## 1. 최초 설정

저장소 루트의 PowerShell에서 실행한다. Python 실행 파일이 PATH에 있으면 -Python은 생략할 수 있다.

```powershell
.\local-dev\dev.ps1 setup -Python 'C:\path\to\python.exe'
```

MySQL의 기본 탐색 위치는 `C:\Program Files\MySQL\MySQL Server 8.0\bin`이다. 다른 위치이면 최초 설정 시 지정한다.

```powershell
.\local-dev\dev.ps1 setup -Python 'C:\path\to\python.exe' -MySqlBin 'D:\MySQL\bin'
```

setup은 .local/venv에 의존성을 설치하고 .local/runtime.json에 무작위 로컬 암호를 생성한다. 암호는 화면에 출력하거나 Git에 포함하지 않는다. 기존 설정이 있으면 유지한다. 경로를 변경하려면 서버를 중지한 뒤 로컬 runtime.json의 mysql_binary를 수정한다.

## 2. 서버·DB 실행 및 종료

```powershell
.\local-dev\dev.ps1 start
.\local-dev\dev.ps1 status
.\local-dev\dev.ps1 stop
```

- start: 전용 DB 초기화·실행 → 마이그레이션 → Django 실행. 같은 환경을 재실행해도 데이터를 초기화하지 않는다.
- stop: 이 프로젝트의 서버와 DB만 중지하고 데이터는 보존한다.
- start 중 DB 또는 API 포트가 다른 프로세스에 사용 중이면 종료시키지 않고 오류로 중단한다.
- 서버는 --noreload로 실행하므로 Python 수정 후 stop → start로 반영한다.
- 실행 로그: .local/backend.log, .local/mysql.log, 최초 초기화 로그: .local/mysql-init.log.
- DB 비밀번호를 기존 MySQL 서비스의 root 비밀번호로 바꾸거나 기존 서비스 데이터 디렉터리를 지정하지 않는다.

## 3. Android 빌드 및 실행

Android Studio에서 local-dev/android를 프로젝트로 연다. Gradle JDK는 17 또는 21을 사용한다. 현재 설치된 Android Studio의 자체 JBR이 더 높은 버전이면 Gradle JDK를 따로 지정한다.

```powershell
.\local-dev\android.ps1 build
.\local-dev\android.ps1 run
```

build는 디버그 APK와 Android lint를 실행한다. Gradle 캐시·Android 도구 설정·개발용 서명 키는 .local/에 격리하고 Kotlin 컴파일은 Gradle 프로세스 안에서 수행한다. 첫 빌드는 테스트 전용 디버그 키를 생성하며 이 키는 배포에 사용하지 않는다. run은 **이미 실행 중인 에뮬레이터 또는 연결된 기기 한 대**에 설치하고 앱을 실행한다. Device Manager에서 SDK 36 에뮬레이터를 실행한 뒤 사용한다.

자동 탐색이 안 되면 경로를 지정한다.

```powershell
.\local-dev\android.ps1 build -JavaHome 'C:\path\to\jdk-21' -AndroidSdk 'C:\path\to\Android\Sdk'
```

앱의 기본 API 주소는 에뮬레이터용 http://10.0.2.2:8001이다. 연결 확인 후 테스트 이름을 저장하고 목록 새로고침으로 영속 저장을 확인한다. APK는 local-dev/android/app/build/outputs/apk/debug/app-debug.apk에 생성된다.

실제 Android 기기를 USB로 연결할 경우:

```powershell
adb reverse tcp:8001 tcp:8001
```

앱 주소를 http://127.0.0.1:8001로 변경한다. 개발 서버를 외부 네트워크에 공개하지 않는다. HTTP 허용은 debug manifest의 로컬 주소에만 적용되며 release에는 적용하지 않는다.

## 4. 검증

```powershell
.\local-dev\dev.ps1 test
.\local-dev\android.ps1 build
.\local-dev\android.ps1 test
```

- BE: Django 설정 검사, 미생성 마이그레이션 검사, 실제 MySQL 테스트 DB에서 연결·저장·조회·입력 오류·DB 실패·HTTP 메서드 검증.
- Android build: 컴파일·APK 생성·lint.
- Android test: Gradle로 앱·테스트 APK를 빌드한 뒤 ADB의 AndroidJUnitRunner로 계측 테스트를 직접 실행한다. 실행 중인 에뮬레이터에서 실제 로컬 서버에 접속해 테스트 레코드를 저장하고 조회한다. dev.ps1 start가 선행되어야 한다. PC 전용 주소 10.0.2.2를 사용하므로 해당 테스트는 에뮬레이터용이다.
- Android 연결 테스트는 식별 가능한 테스트 이름을 로컬 DB에 남긴다. 실제 사용자 사진이나 데이터를 입력하지 않는다.

## 5. 팀 프로젝트 통합 시 처리

| 항목 | 통합 방향 |
| --- | --- |
| ProbeItem·/api/dev/·테스트 화면 | 연결 검증용. 정식 옷장 모델이나 UI로 취급하지 않음 |
| wardrobe 화면·분석·편집·저장·검증 로직 | 팀 패키지·앱 구조에 맞춰 선별 이동 |
| local-dev의 프로젝트 설정·포트·DB 계정 | 개인 실행용으로 유지하거나 통합 후 제거 |
| DB 데이터·runtime.json·IDE 설정 | 이식·커밋하지 않음 |
| S3·사용자 식별·정식 API 계약 | 팀 공통 구현과 wardrobe-spec.md에 맞춰 별도 연결 |

파일을 이동·삭제할 때 AGENTS.md의 규칙에 따라 architecture.md를 함께 갱신한다.

## 설정 근거

- [AGP 8.12 호환성](https://developer.android.com/build/releases/agp-8-12-0-release-notes): Gradle 8.13, SDK 36 지원.
- [Compose 컴파일러 설정](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler): Kotlin과 Compose 플러그인의 버전을 맞춘다.
- [Django MySQL 설정](https://docs.djangoproject.com/en/5.2/ref/databases/#mysql-notes): 공식 MySQL backend와 mysqlclient 사용.
- [MySQL 데이터 디렉터리 초기화](https://dev.mysql.com/doc/refman/8.0/en/data-directory-initialization.html): 프로젝트 전용 datadir를 초기화한다.

## 검증 기록 — 2026-09-26

- Python 3.12, MySQL 8.0.46에서 Django 검사·마이그레이션 검사 및 BE 테스트 6개 통과.
- DB와 서버 중지·재시작 후 기존 테스트 레코드 유지 확인.
- Android 디버그 APK 생성·lint 통과. lint의 의존성 업데이트 안내 및 아이콘·백업 설정 경고는 남아 있으며 릴리스 앱 완성 상태를 의미하지 않는다.
- SDK 36 에뮬레이터에서 Android → Django → MySQL 저장·조회 계측 테스트 1개 통과.
- 현재 실행 환경에서 Gradle UTP 실행기가 결과 수신에 실패하여, android.ps1 test는 동일한 계측 테스트를 ADB로 직접 실행한다. 테스트 실패 시 스크립트도 실패 처리한다.
- 기존 backend/·infra/·.github/ 변경 없음, 비밀 설정·DB·생성 APK의 Git 제외 확인.

## 옷장 목록·촬영 테스트

앱 시작 화면은 `내 옷장`이다. `촬영하기`를 누를 때 카메라 권한을 요청한다. 허용하면 앱 AR 카메라가 열리고 측정한 사진은 미리보기에서 다시 촬영하거나 삭제할 수 있다. 에뮬레이터에는 후면 카메라를 Emulated 또는 VirtualScene으로 설정해야 한다. 최초 거부 후 재시도 설명, 반복 거부 후 설정 이동, 설정에서 허용 후 재촬영도 확인한다. 촬영 취소 시 기존 사진은 유지된다.

Gemini 분석·편집·메모·옷 저장·목록 API를 연결했다. 확정 저장한 사진과 옷 정보만 목록에 표시한다. S3는 아직 연결하지 않았다. 기존 연결 검증은 `개발용 DB 연결 확인`에서 실행한다. 전체 설계는 [wardrobe-flow.md](wardrobe-flow.md)에 있다.

`android.ps1 test`는 기존 DB 연결 검사 외에 Compose 목록/필터/중복 촬영 버튼 상태 및 촬영 파일 URI·축소 미리보기·삭제·경로 제한을 검사한다. UI 검증을 위해 Compose BOM에 맞는 ui-test-junit4와 디버그 전용 ui-test-manifest를 추가했다. 실제 기기 권한창·기기별 카메라 앱의 동작은 별도 수동 검증 대상이다.

### 촬영 진입부 검증 기록 — 2026-09-26

- 최종 Android APK 빌드·lint 완료: 오류 0개, 경고 16개. 기존 업데이트/아이콘/백업 안내 외에 플랫폼 EXIF 대신 AndroidX 권장, 화면 너비 API·KTX 권장 경고가 남아 있다.
- API 36 에뮬레이터에서 계측 테스트 7개 통과: 기존 DB 연결 1개, 촬영 파일 3개, 목록 UI 3개.
- 시스템 권한창 최초 허용 → 카메라 실행, 최초 거부 → 설명 후 재요청, 반복 거부 → 설정 이동, 설정에서 허용 → 촬영을 직접 확인했다.
- 에뮬레이터 기기 카메라로 촬영 → 미리보기 확인. 재촬영 취소 후 이전 사진과 파일이 유지되고 새 파일은 삭제됨을 확인했다. 뒤로가기로 사진 폐기 후 캐시가 비고 총 0벌 목록으로 복귀했다.
- 실제 휴대폰·다른 카메라 앱, 앱 프로세스 종료 중 촬영 복구, 외부 AI/S3/정식 옷장 API는 아직 통합 검증하지 않았다.


### 저장 확인

`dev.ps1 start`가 wardrobe 테이블 마이그레이션을 적용한다. 촬영 → 분석하기 → 이름·치수 수정 → 메모 입력 → 옷장에 추가 순서로 확인한다. 앱을 종료하고 다시 열어 옷·사진·메모가 남아 있는지 확인하고, 카드를 눌러 수정 후 저장한다. AR 측정 정확도와 실제 Gemini 응답 품질은 별도 실기기 검증 대상이다.

분석만 하고 저장하지 않은 초안·사진은 목록에 노출되지 않지만 로컬 서버에 남는다. 현재 자동 정리·옷 삭제·사용자 구분은 구현하지 않는다. 이 데이터는 `.local/`에 있어 커밋되지 않는다. 서버가 꺼지면 목록·분석·저장이 동작하지 않는다.


### 옷장 저장 검증 기록 — 2026-09-28

- 실제 MySQL 테스트 DB에서 Django 검사·마이그레이션 검사 및 30개 테스트 통과. 분석은 합성 JPEG와 Gemini 모의 응답을 사용했다.
- 초안 비노출, 수정값·메모·사진 저장과 조회, 재저장 중복 방지, 메모 삭제, 잘못된 입력 거부를 검증했다.
- Android 단위 테스트 6개와 API 36 에뮬레이터 계측 테스트 10개 통과. 편집 폼의 메모·치수 수정, 두 자리 초과 입력 차단, 분류 변경, 카드 선택을 검증했다.
- 실제 Gemini 호출·옷 사진 품질·AR 실측 정확도는 이번 자동 검증에 포함하지 않았다.
