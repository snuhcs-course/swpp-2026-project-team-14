# 공통 앱·백엔드 실행

Android Studio에서 `frontend/`를 열고 `app`을 실행한다. 백엔드는 `backend/manage.py`, 배포는 기존 Docker의 `config.wsgi`를 사용한다. 별도 테스트 앱·서버·DB 실행 스크립트는 사용하지 않는다.

## 백엔드

Python 3.12 기준으로 최초 한 번 준비한다. 현재 PC의 `backend/.venv`와 모델은 준비되어 있다.

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
```

서버 실행:

```powershell
.\.venv\Scripts\python.exe manage.py runserver 127.0.0.1:8000
```

`GET http://127.0.0.1:8000/healthz/`로 확인한다. 종료는 실행 터미널에서 `Ctrl+C`다. 다른 PC에서 접근할 때만 바인딩을 `0.0.0.0:8000`으로 변경하고 허용 호스트를 추가한다.

## 환경변수

`backend/.env.example`을 `backend/.env`로 복사하고 값을 입력한다. 현재 PC에는 `.env`를 생성했고 기존 Gemini 키를 옮겼다. OS·배포 환경변수가 파일보다 우선하며 변경 후 서버를 재시작한다. 실제 `.env`는 Git에서 제외한다.

| 항목 | 입력 내용 |
| --- | --- |
| `MYSQL_HOST`, `MYSQL_PORT` | MySQL 서버 주소·포트. PC의 기본 서비스는 `127.0.0.1:3306` |
| `MYSQL_DATABASE` | 사용할 기존 DB 이름 |
| `MYSQL_USER`, `MYSQL_PASSWORD` | 해당 DB에 접근 가능한 계정 |
| `GEMINI_API_KEY` | Gemini API 키. 서버에서만 사용 |
| `DJANGO_SECRET_KEY` | Django 비밀 키 |
| `DJANGO_DEBUG` | 개발 시 `1`, 배포 시 `0` |
| `DJANGO_ALLOWED_HOSTS` | 쉼표로 구분한 서버 호스트 이름·주소 |

`MYSQL_DATABASE`를 비워 두면 기존 체형 분석과 자동 측정점 API는 DB 없이 실행된다. 옷장 목록·조회·분석 초안·저장은 `503 DATABASE_NOT_CONFIGURED`를 반환한다. 이때 Gemini 유료 호출도 수행하지 않는다. SQLite는 자동 테스트에서만 사용하며 실행용 DB를 대신하지 않는다.

MySQL에 사용할 DB와 계정을 준비한 뒤 `.env`를 입력하고 다음 명령으로 옷장 테이블을 생성한다. `migrate`가 MySQL 서버나 DB 자체를 설치·생성하지는 않는다.

```powershell
.\.venv\Scripts\python.exe manage.py migrate
```

DB 계정 입력·실제 DB 연결·마이그레이션은 사용자 요청으로 보류했다. 기존 개인 테스트 DB와 사진은 이관하지 않는다.

기본 사진 저장 위치는 `backend/private/wardrobe-media/`, 옷장 모델은 `backend/wardrobe/garment-landmarks/`다. 필요하면 `WARDROBE_MEDIA_ROOT`, `WARDROBE_MODEL_DIR`에 절대 경로를 지정한다. S3는 아직 연결하지 않았다.

## Android

Android Studio에서 `frontend/`를 열어 Gradle Sync 후 `app`을 실행한다. 체형과 옷장 모두 `BuildConfig.API_BASE_URL`을 사용한다. 주소는 `frontend/local.properties`에 입력하며 변경 후 앱을 다시 빌드한다.

| 연결 방식 | `stylemate.apiBaseUrl` |
| --- | --- |
| 에뮬레이터 | `http://10.0.2.2:8000` — 미지정 시 기본값 |
| USB 실기기 | `http://127.0.0.1:8000` — 현재 PC 설정 |
| 같은 Wi-Fi | `http://PC의_IP:8000` |

USB 연결 후 실행한다. USB를 다시 연결하면 재설정한다.

```powershell
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -d reverse tcp:8000 tcp:8000
```

앱 ID는 `com.swpp.stylemate`다. 옷장 탭의 `촬영하기 → 점 확인 → 촬영 → 점 수정 → 확인 → 분석하기 → 옷장에 추가` 순서로 확인한다. DB 미설정 상태에서는 자동 측정점·AR 촬영까지 사용할 수 있다.

## 모델 준비

체형 모델은 `backend/README.md`에 따라 `backend/models/pose_landmarker_heavy.task`에 준비한다.

옷장 자동 측정점은 GarmentIQ HRNet, 배경 제외는 U²-Net을 사용한다. 서버에서는 ONNX Runtime으로 CPU 추론하며 PyTorch는 최초 변환에만 필요하다. 현재 모델 파일은 이미 공통 모델 경로로 옮겼다.

새 환경에서 모델을 변환할 때만 다음 명령을 `backend/`에서 실행한다.

```powershell
python -m venv private/model-export-venv
.\private\model-export-venv\Scripts\python.exe -m pip install torch==2.6.0 --index-url https://download.pytorch.org/whl/cpu
.\private\model-export-venv\Scripts\python.exe -m pip install onnx==1.19.0
.\private\model-export-venv\Scripts\python.exe scripts/prepare_landmarks.py
```

스크립트는 고정 revision·SHA-256으로 소스와 가중치를 확인한다. 결과 `hrnet.onnx`·`manifest.json`·`u2net.onnx`·라이선스는 `wardrobe/garment-landmarks/`에 생성하며 Git에서 제외한다. U²-Net만 필요하면 `.venv/Scripts/python.exe scripts/prepare_landmarks.py --foreground-only`를 실행한다.

모델은 2D 측정점을 제안하고 Android의 ARCore가 고정된 촬영 프레임에서 광선·바닥 평면 교점을 계산하여 cm로 변환한다. 기준 물체·고정 촬영 높이·별도 높이 입력은 사용하지 않는다. 옷 전체가 같은 평면에 있다는 가정이며 실제 치수 정확도는 별도로 검증해야 한다. Gemini는 이름·분류·색상만 분석한다.

## 검증

`backend/`에서 실행한다. 실제 DB·Gemini 호출 없이 체형 분석과 옷장 계약·저장·조회 동작을 검사한다.

```powershell
.\.venv\Scripts\python.exe manage.py check
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe manage.py test apps.wardrobe --settings=config.test_settings --noinput
.\.venv\Scripts\python.exe manage.py makemigrations --check --dry-run --settings=config.test_settings
```

저장소 루트에서 Android 빌드·단위 테스트·린트를 실행한다. JDK 17 이상과 Android SDK가 필요하다.

```powershell
.\frontend\gradlew.bat -p frontend assembleDebug testDebugUnitTest lintDebug
```

연결 기기의 UI 검사는 `connectedDebugAndroidTest`로 실행한다. 기존 팀 CI는 체형 pytest와 옷장 Django 테스트를 모두 실행한다.

## 배포와 남은 범위

기존 GHCR·ArgoCD·k3s 흐름을 사용한다. 옷장 저장을 배포하려면 MySQL 환경변수·마이그레이션, 모델 볼륨과 사진 영구 볼륨, uid 10001의 접근 권한이 필요하다. `.env`나 사진·가중치를 이미지에 복사하지 않는다.

현재 배포의 1Gi 메모리 제한은 체형 모델 기준이다. HRNet·U²-Net 동시 로드 시 사용량을 다시 측정해야 한다. 사용자 구분·인증, S3, 옷 삭제·초안 정리·체형 프로필 영구 저장은 아직 구현하지 않았다. 공용 옷장 배포는 이 항목들을 확인한 뒤 진행한다.
