# 공통 앱·백엔드 실행

Android는 `frontend/`, 백엔드는 `backend/manage.py`, 배포는 Docker의 `config.wsgi`를 사용한다.

## 1. 최초 설치

저장소 루트에서 Python 3.12 가상환경과 의존성을 준비한다. 이미 준비한 환경은 이 단계를 건너뛴다.

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
```

## 2. 환경변수

`backend/.env`가 없으면 `backend/.env.example`을 복사하고 값을 입력한다. OS·배포 환경변수가 파일보다 우선한다. 변경 후 서버를 재시작하며 실제 `.env`는 Git에서 제외한다.

| 항목 | 입력 내용 |
| --- | --- |
| `MYSQL_HOST`, `MYSQL_PORT` | MySQL 서버 주소·포트. 로컬 기본 예시: `127.0.0.1:3306` |
| `MYSQL_DATABASE` | 사용할 기존 DB 이름 |
| `MYSQL_USER`, `MYSQL_PASSWORD` | 해당 DB에 접근 가능한 계정 |
| `GEMINI_API_KEY` | Gemini API 키. 서버에서만 사용 |
| `DJANGO_SECRET_KEY` | Django 비밀 키 |
| `DJANGO_DEBUG` | 개발 시 `1`, 배포 시 `0` |
| `DJANGO_ALLOWED_HOSTS` | 쉼표로 구분한 서버 호스트 이름·주소 |

`MYSQL_DATABASE`를 비워 두면 기존 체형 분석과 자동 측정점 API는 DB 없이 실행된다. 옷장 목록·조회·분석 초안·저장은 `503 DATABASE_NOT_CONFIGURED`를 반환한다. 이때 Gemini 유료 호출도 수행하지 않는다. SQLite는 자동 테스트에서만 사용하며 실행용 DB를 대신하지 않는다.

## 3. MySQL 테이블 생성

MySQL에 사용할 DB와 계정을 준비한 뒤 `.env`를 입력하고 다음 명령으로 옷장 테이블을 생성한다. `migrate`가 MySQL 서버나 DB 자체를 설치·생성하지는 않는다.

```powershell
.\.venv\Scripts\python.exe manage.py migrate
```

옷장 마이그레이션은 새 DB 기준의 `0001_initial` 하나다. 이전 0001~0004를 적용한 테스트 DB에는 그대로 적용하지 않으며, 해당 DB를 초기화한 뒤 실행한다. 이후 스키마 변경은 새 마이그레이션으로 관리한다.

| 저장 대상 | 기본 경로 | 경로 변경 환경변수 |
| --- | --- | --- |
| 옷 사진 | `backend/private/wardrobe-media/` | `WARDROBE_MEDIA_ROOT` |
| HRNet 모델 | `backend/wardrobe/garment-landmarks/` | `WARDROBE_MODEL_DIR` |

경로 변경 시 절대 경로를 지정한다. S3는 아직 연결하지 않았다.

## 4. 모델 준비

체형 모델은 [백엔드 안내](../../backend/README.md)에 따라 `backend/models/pose_landmarker_heavy.task`에 준비한다.

옷장 자동 측정점은 원본 사진에 GarmentIQ HRNet을 적용한다. 서버에서는 ONNX Runtime으로 CPU 추론하며 PyTorch는 최초 변환에만 필요하다.

### Docker 배포

이미지 빌드 단계가 공개된 원본 가중치와 모델 코드를 다운로드·검증하고 ONNX로 변환한다. 최종 이미지에는 `hrnet.onnx`·`manifest.json`·라이선스만 포함하며 PyTorch와 변환 도구는 포함하지 않는다. 별도 모델 업로드·Git LFS·모델 볼륨 없이 기본 경로에서 실행한다. 모델 경로에 빈 볼륨을 연결하면 이미지에 포함된 파일이 가려지므로 연결하지 않는다.

원본은 [Hugging Face의 고정 HRNet 가중치](https://huggingface.co/lygitdata/garmentiq/resolve/5f02016e9ad3a4aa171fa9199423a437170f5afe/hrnet.pth)와 [GarmentIQ 모델 정의](https://github.com/lygitdata/GarmentIQ/blob/6eba6d65f462647b48e9eed24440d609e9e671d6/src/garmentiq/landmark/detection/model_definition.py)를 사용한다. 다운로드 주소와 체크섬은 `backend/scripts/prepare_landmarks.py`에서 관리한다.

### 로컬 실행

Docker 없이 새 로컬 환경에서 모델을 준비할 때만 다음 명령을 `backend/`에서 실행한다.

```powershell
python -m venv private/model-export-venv
.\private\model-export-venv\Scripts\python.exe -m pip install torch==2.6.0 --index-url https://download.pytorch.org/whl/cpu
.\private\model-export-venv\Scripts\python.exe -m pip install onnx==1.19.0
.\private\model-export-venv\Scripts\python.exe scripts/prepare_landmarks.py
```

스크립트는 고정 revision·SHA-256으로 소스와 가중치를 확인한다. 결과 `hrnet.onnx`·`manifest.json`·라이선스는 `wardrobe/garment-landmarks/`에 생성하며 Git에서 제외한다.

측정과 분석의 역할·제약은 [데이터 흐름](wardrobe-flow.md#3-촬영측정)과 [데이터 명세](wardrobe-spec.md)를 따른다.

## 5. 서버 실행

```powershell
.\.venv\Scripts\python.exe manage.py runserver 127.0.0.1:8000
```

`GET http://127.0.0.1:8000/healthz/`로 확인한다. 종료는 실행 터미널에서 `Ctrl+C`다. 다른 PC에서 접근할 때만 바인딩을 `0.0.0.0:8000`으로 변경하고 허용 호스트를 추가한다.

## 6. Android 연결

Android Studio에서 `frontend/`를 열어 Gradle Sync 후 `app`을 실행한다. 체형과 옷장 모두 `BuildConfig.API_BASE_URL`을 사용한다. 주소는 `frontend/local.properties`에 입력하며 변경 후 앱을 다시 빌드한다.

| 연결 방식 | `stylemate.apiBaseUrl` |
| --- | --- |
| 에뮬레이터 | `http://10.0.2.2:8000` — 미지정 시 기본값 |
| USB 실기기 | `http://127.0.0.1:8000` |
| 같은 Wi-Fi | `http://PC의_IP:8000` |

USB 연결 후 실행한다. USB를 다시 연결하면 재설정한다.

```powershell
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -d reverse tcp:8000 tcp:8000
```

앱 ID는 `com.swpp.stylemate`다. 옷장 탭의 `촬영하기 → 점 확인 → 촬영 → 점 수정 → 확인 → 분석하기 → 옷장에 추가` 순서로 확인한다. DB 미설정 상태에서는 자동 측정점·AR 촬영까지 사용할 수 있다.

## 7. 검증

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

## 8. 배포

기존 GHCR·ArgoCD·k3s 흐름을 사용한다.

| 구성 | 배포 설정 |
| --- | --- |
| MySQL | 환경변수 입력 후 `python manage.py migrate` 실행 |
| Gemini | 컨테이너 환경에 `GEMINI_API_KEY` 입력 |
| 옷 사진 | 영구 볼륨 연결, uid 10001에 쓰기 권한 부여 |
| HRNet | Docker 빌드에서 준비, 기본 경로 `/app/wardrobe/garment-landmarks` 사용 |

`.env`·사진·PC의 모델 파일은 Docker 빌드 컨텍스트에서 제외한다.

최초 빌드에는 원본 다운로드와 ONNX 변환 시간이 추가되며 GitHub·Hugging Face·패키지 저장소 접속이 필요하다. 다운로드·체크섬 검증·변환에 실패하면 이미지 빌드도 실패한다. Backend CI는 이미지 내부에서 HRNet의 체크섬·입출력 크기와 실제 추론을 확인한다.

현재 배포의 1Gi 메모리 제한은 체형 모델 기준이다. 체형 모델과 HRNet 동시 로드 시 사용량을 다시 측정해야 한다. 사용자 구분·인증, S3, 옷 삭제·초안 정리·체형 프로필 영구 저장은 아직 구현하지 않았다. 공용 옷장 배포는 이 항목들을 확인한 뒤 진행한다.
