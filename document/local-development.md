# 개인 로컬 테스트 환경

## Gemini 분석 실행

1. 저장소 루트 `.env`에 `GEMINI_API_KEY=발급받은키`를 입력한다. `.env.example`은 빈 예시이며 실제 키를 넣지 않는다. 모델은 서버 코드의 `gemini-3.1-flash-lite`로 고정한다. OS 환경변수가 있으면 .env보다 우선한다.
2. `./local-dev/dev.ps1 setup`으로 의존성을 설치한다. python-dotenv는 .env 로딩, Pillow는 이미지 검증·축소에 사용하며 Gemini는 표준 라이브러리 REST로 호출한다.
3. 서버가 실행 중이면 `./local-dev/dev.ps1 stop` 후 `./local-dev/dev.ps1 start`로 다시 시작한다. .env 변경은 서버 재시작 후 반영된다.
4. USB 연결 실기기는 Android Studio Terminal에서 `& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -d reverse tcp:8001 tcp:8001`을 실행한다. USB 재연결 후 다시 설정한다. 앱은 실기기에서 127.0.0.1:8001, 에뮬레이터에서 10.0.2.2:8001을 사용한다.
5. 앱을 다시 빌드·실행하고 `촬영하기 → 측정 → 확인 → 분석하기`를 누른다. 사진이 PC의 Django를 거쳐 Gemini에 전송되며 한글 분석 결과를 표시한다. 수정·메모 입력 후 `옷장에 추가`를 누르면 저장된다. 목록 카드를 눌러 다시 수정할 수 있다.

이 API는 인증 없는 루프백 개발용이다. 공용 서버에 배포하지 않는다. 키는 Android에 전달하지 않는다. 옷 정보·치수·메모는 MySQL에, 정규화 사진은 `.local/wardrobe-media/`에 저장한다. S3는 아직 연결하지 않는다. 오류 후 자동 재시도는 하지 않는다. 회전 시 진행 중 요청은 ViewModel로 유지하고 사진 변경 시 이전 결과를 폐기한다.

키 없는 검증: `./.local/venv/Scripts/python.exe local-dev/backend/manage.py test wardrobe`.

## AR 실측 테스트

1. USB 디버깅을 허용한 Galaxy S23 Ultra를 연결하고 `./local-dev/android.ps1 run`으로 앱을 설치·실행한다.
2. 옷장의 `촬영하기`를 선택하고 카메라 권한·Google Play Services for AR 설치를 허용한다.
3. 옷을 바닥에 평평하게 펴고 주변 바닥을 천천히 비춘다. 중앙 십자가 인식된 수평 평면 위에 있어야 촬영할 수 있다.
4. 옷 종류를 선택하면 자동 제안점이 표시된다. 높이는 고정하지 않으며 옷 전체가 화면에 들어오도록 조절한다. 촬영 후 배경 제외 결과와 점 위치를 확인하고 필요한 항목은 직접 수정한다. 상의 어깨·총장은 뒷면 기준이다.
5. 선과 값을 확인하고 `확인`을 누르면 기존 사진 확인 화면에 AR 추정값이 표시된다. 자동 탐지에는 PC 서버가 필요하다. 서버 없이 테스트하려면 `직접 지정`을 선택한다.

처음에는 동일한 옷을 줄자로 측정한 값과 여러 번 비교한다. 오차 검증은 미완료이며 AR 평면 위의 경로 구간 거리를 합산한다. 사진을 고정하므로 휴대폰을 움직여도 편집 중 좌표는 변하지 않는다. 옷 전체가 같은 바닥에 놓였다는 가정하에 관측 다각형 밖으로 평면식을 연장한다. 같은 항목 다시 지정, 항목 삭제, 재촬영, 취소 시 기존 사진 유지, 백그라운드 복귀를 확인한다. 화면 회전·프로세스 종료로 측정 화면이 재생성되면 AR 촬영을 다시 시작한다.

저장 전 사진은 앱 캐시, 측정값은 임시 화면 상태에 둔다. 분석 후 확정 저장하면 사진과 데이터는 PC 서버에 유지되고 앱 재실행 시 목록을 불러온다. AR 미지원 기기에서는 촬영을 사용할 수 없다.

## 자동 측정점 모델

[GarmentIQ HRNet](https://huggingface.co/lygitdata/garmentiq)을 ONNX로 변환하여 Django 프로세스에서 CPU 추론한다. 모델은 측정점의 이미지 좌표만 출력하며 cm는 Android의 ARCore 평면으로 계산한다. 종류·색상 등의 분석은 기존 Gemini가 담당한다. `onnxruntime`은 CPU 실행, `numpy`는 이미지 정규화·heatmap 좌표 복원에 사용한다. PyTorch는 변환 전용 환경에만 설치한다.

| 촬영 종류 | 자동 제안 |
| --- | --- |
| 반팔·긴팔·반팔 아우터·긴팔 아우터 | 어깨너비, 가슴 단면, 총장, 소매길이, 밑단 단면, 소매끝, 암홀 |
| 바지·반바지 | 허리·엉덩이·허벅지 단면, 앞밑위, 인심, 총장, 한쪽 밑단 |
| 스커트 | 허리·엉덩이 단면, 총장 |

종류 내 1부터 시작하는 점 번호는 [DeepFashion2 공식 도식](https://github.com/switchablenorms/DeepFashion2/blob/master/images/cls.jpg)과 GarmentIQ instruction을 기준으로 연결한다. 반팔은 어깨 7–25, 가슴 12–20, 암홀 7–12, 총장 1–16, 소매 25–24–23, 밑단 15–17, 소매끝 23–22다. 긴팔은 각각 7–33, 16–24, 7–16, 1–20, 33–32–31–30–29, 19–21, 29–28이다. 바지 인심은 9–8–7, 총장은 1–4–5–6을 따라 계산한다. 허벅지 외곽은 가랑이를 지나는 허리선 평행선과 바깥 윤곽의 교점으로 유도한다. 엉덩이 점은 학습된 상단 옆선 지점의 근사이며 최대 폭을 보장하지 않는다. 앞밑위는 두 점 사이 근사로 실제 휘어진 봉제선 전체를 복원하지 않는다.

촬영 후 기본 활성화되는 `배경 제외`는 [U²-Net](https://github.com/xuebinqin/U-2-Net)의 rembg 배포 ONNX를 사용한다. 320×320 입력에서 마스크를 구하고 의상 영역을 여백과 함께 crop한 뒤 HRNet에 넣는다. 누끼는 옷 전용 분류기가 아닌 전경 분리 모델이므로 한 벌만 평평하게 놓는다. 좌표는 crop과 letterbox를 역변환해 원본 뷰포트에 맞춘다. 누끼에서 빠진 치수는 원본 HRNet 결과로 보완한다. 원본에서 보완한 필드는 API의 `fallback_fields`로 구분한다. `points`는 주 탐지의 원시 좌표이며 실제 측정은 보완 결과까지 포함한 `suggestions`를 사용한다. 배경 제외를 끄면 원본만 사용한다.

점수 0.4 미만·이미지 밖 점·필요한 중간점이 없는 경로는 제외한다. 점수는 정확도 확률이 아니다. 누끼 미리보기는 원본과 같은 종횡비·좌표 원점을 유지한다. 원본 사진은 Gemini 분석·옷장 저장에 사용하며 누끼는 임시 미리보기로만 사용한다.

최초 준비는 저장소 루트에서 실행한다. 현재 PC에는 이미 준비되어 있다.

```powershell
./local-dev/dev.ps1 setup
./.local/venv/Scripts/python.exe -m venv .local/model-venv
./.local/model-venv/Scripts/python.exe -m pip install torch==2.6.0 --index-url https://download.pytorch.org/whl/cpu
./.local/model-venv/Scripts/python.exe -m pip install onnx==1.19.0
./.local/model-venv/Scripts/python.exe local-dev/prepare-landmarks.py
./local-dev/dev.ps1 stop
./local-dev/dev.ps1 start
```

변환 스크립트는 HF revision `5f02016e9ad3a4aa171fa9199423a437170f5afe`의 가중치와 원본 코드 commit `6eba6d65f462647b48e9eed24440d609e9e671d6`를 사용한다. 다운로드 SHA-256 확인, `weights_only=True`, 모델 구조 엄격 일치 검사를 거쳐 변환한다. 결과 약 254MB는 `.local/models/garment-landmarks/`에 manifest·MIT 라이선스와 함께 보관하고 Git에는 넣지 않는다. 가중치 SHA-256은 `5b29ada40632cb5ce1aaa38e4896054329c42d0f6c6649a5b9d0b53e41ee04f6`다. 앱 서버는 첫 요청에 모델을 로드하며 이후 재사용한다. U²-Net ONNX는 약 176MB이며 동일 폴더에 U2NET-LICENSE와 함께 저장한다. SHA-256은 `8d10d2f3bb75ae3b6d527c77944fc5e7dcd94b29809d47a739a7a728a912b491`이다. 이미 HRNet이 준비된 경우 `./.local/venv/Scripts/python.exe local-dev/prepare-landmarks.py --foreground-only`로 추가한다. 런타임 의존성은 기존 ONNX Runtime·NumPy·Pillow를 재사용한다.

**이전 HRNet 단독 측정(누끼 모델 제외):** 2026-09-29 Windows 개발 PC에서 ONNX Runtime 1.23.2, CPU 스레드 4개, 입력 288×384, 배치 1, 단일 Django 프로세스로 측정했다. [공개 예제 사진](https://github.com/lygitdata/GarmentIQ/blob/gh-pages/asset/img/cloth_2.jpg)을 576×768 JPEG로 축소하여 실제 탐지 API를 12회 호출했다.

| 측정 항목 | 결과 |
| --- | --- |
| Django·런처·콘솔의 프로세스별 최대 working set 합계 | 622,637,056 bytes, 약 623MB / 594MiB |
| 같은 프로세스들의 측정 종료 시 working set 합계 | 365,305,856 bytes |
| 첫 요청, 모델 로딩 포함 | 4.30초 |
| 이후 11회 HTTP 왕복, PC 루프백 | 0.305~0.343초 |
| 에뮬레이터 실행과 동시에 재측정한 12회 | 0.616~1.401초, 최대 메모리 약 623MB |
| 앱 미리보기 요청 주기 | 최대 초당 2회, 동시에 한 요청 |

**2026-10-03부터 RAM 1GB 상한은 요구사항과 벤치마크 실패 조건에서 제거했다.** 위 수치는 이전 HRNet 단독 결과이며 U²-Net이 추가된 현재 전체 사용량이 아니다. MySQL·Android 앱 메모리도 별도다. CPU 500m 제한의 VM·네트워크 지연·S23 Ultra 카메라부터 화면 표시까지의 시간은 측정하지 않았다. 30fps 추적을 의미하지 않으며 이전 사진의 점이 현재 화면에 잘못 붙지 않도록 이동·회전이 큰 미리보기 결과를 숨긴다. 촬영 후 동일 사진 재탐지로 프레임 불일치를 차단한다. 다양한 옷·앞뒷면·배경·실측 정확도 검증은 남아 있다. 동시에 여러 서버 프로세스를 띄우면 모델 메모리도 복제된다.

재측정은 새로 시작한 서버에서 `./.local/venv/Scripts/python.exe local-dev/prepare-landmarks.py --benchmark-image <테스트용-반팔-사진>`을 실행한다. Windows에서는 서버 자식 프로세스까지 포함한 lifetime peak working set 합계를 보수적으로 사용한다. 테스트 사진은 탐지 후 보관하지 않는다. 누끼 경로도 측정하려면 `--remove-background`를 추가한다. 메모리를 보고만 하며 크기를 이유로 실패시키지 않는다. 실제 Gemini 호출은 이 검사에 포함하지 않는다.

2026-09-29 검증 기록: Django 테스트 36개, Android 단위 테스트 6개, 에뮬레이터 계측 테스트 13개 통과. 계측 테스트에는 Android에서 실제 CPU 탐지 API로 프레임을 전송하는 검사와 자동 제안 치수의 수정·출처 유지 검사가 포함된다. 빌드 성공, lint 오류 0개·경고 18개다. Gradle 계측 실행기는 에뮬레이터 콘솔 인증 문제로 결과 수집에 실패하여 APK 설치 후 ADB로 직접 실행했다. 테스트 APK 설치 후 명령은 `adb -s emulator-5554 shell am instrument -w -e landmarkServer true com.stylemate.localdev.test/androidx.test.runner.AndroidJUnitRunner`다.

실기기 AR 오차 검증은 별도다. USB 기기 연결 후 포트 reverse를 설정하고 `촬영하기 → 반팔/긴팔/바지 → 점 확인 → 촬영 → 점 수정 → 확인 → 분석하기 → 옷장에 추가` 순서로 확인한다.

## 기준 물체 없는 AR 치수 계산

기준 물체·고정 거리·사진 속 임의의 cm 비율은 사용하지 않는다. [ARCore](https://developers.google.com/ar/develop/fundamentals)가 여러 카메라 프레임과 IMU를 결합하여 미터 단위 카메라 자세와 바닥 평면을 추정한다. 자이로스코프만으로 거리나 절대 크기를 계산하지 않으며, AR 세션 없이 단일 사진만 넣어 실측하는 기능도 아니다.

1. 촬영 프레임의 이미지, View·Projection 행렬, 평면 원점 P와 법선 n, 시각을 함께 고정한다.
2. 화면의 정규화 좌표를 역 ViewProjection으로 역투영하여 카메라 광선 `O + tD`를 얻는다.
3. `t = dot(P - O, n) / dot(D, n)`으로 평면 교점 X를 계산한다.
4. 두 점의 치수는 `100 × ||X₂ - X₁||` cm이며 소매·인심·옆선 경로는 각 구간 거리를 합산한다.
5. 기울기는 카메라 시선과 평면 수직 방향 사이 각도로 표시한다. 높이는 별도로 계산·표시·전달하지 않으며, 치수 계산에는 ARCore 카메라 자세와 평면을 사용한다. 바닥을 거의 수평으로 보는 시선·추적 실패는 촬영 불가로 처리한다.

삼각측량과 관성 센서 결합을 통한 스케일 추정은 ARCore가 담당하고 앱은 광선·평면 교점 계산을 수행한다. 현재 Depth API나 ToF 센서를 필수로 사용하지 않는다. 옷의 두께·주름은 무시하고 같은 평면에 놓였다고 가정한다.

## 모델 선택 및 현재 검증 — 2026-10-03

- [GarmentIQ HRNet](https://huggingface.co/lygitdata/garmentiq): 현재 가중치에 의류 294개 좌표가 이미 포함되어 있어 측정 항목 연결을 확장했다. 새 학습은 수행하지 않았다.
- [Keypoint R-CNN trousers](https://huggingface.co/kengboon/keypointrcnn-trousers): 바지 14점 전용으로 상의 어깨·암홀을 대체하지 못하므로 채택하지 않았다.
- [DeepFashion2 aggregation 모델](https://github.com/lzhbrian/deepfashion2-kps-agg-finetune): 별도 전처리·구형 학습 환경의 도입보다 현재 실행이 검증된 HRNet 경로를 확장했다.
- 일반 YOLO Pose의 COCO 가중치는 사람 관절용이므로 옷의 봉제점 모델로 사용하지 않는다. KGDet 상태는 아래 기록을 따른다.

공개 GarmentIQ 예제 티셔츠 두 장과 스커트 한 장으로 실제 로컬 HTTP 탐지와 원본 좌표 복원을 확인했다. 첫 티셔츠는 원본·누끼 모두 상의 7개 항목을 반환했다. 다른 티셔츠에서는 누끼가 소매 항목을 누락하여 원본 재탐지 보완 경로를 추가했고 실제 HTTP 응답에서 소매길이·소매끝이 복구되어 7개 항목이 반환되는 것을 확인했다. 두 모델을 처음 읽는 보완 요청은 에뮬레이터가 켜진 PC에서 6.54초였다. 원본 이미지의 모델 준비 후 요청은 약 0.54초, 누끼는 약 1.4~2초였으며 CPU 부하·보완 추론에 따라 변한다. 이는 실기기 카메라부터 화면 표시까지의 지연이나 전체 의상 정확도 측정이 아니다.

최종 검증: Django/MySQL 테스트 43개, Android 기하 단위 테스트 9개, API·좌표·누끼 응답·편집·저장을 포함한 에뮬레이터 계측 테스트 16개 통과. `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintDebug` 성공. lint는 오류 0개·경고 18개이며 실제 Gemini 유료 호출은 수행하지 않고 새 3개 필드 계약을 모의 응답으로 검사했다. PC 서버는 변경 코드로 재시작했다. Android Studio에서 `local-dev/android` 프로젝트의 app을 다시 Run하여 휴대폰에 설치한다.

기하 단위 검증은 높이 0.6/1.0/1.8/2.5m와 기울기 0/20/40도에서 알려진 3D 점을 화면에 투영한 후 복원하여 50cm·20cm를 확인한다. 중간점을 포함한 70cm 경로를 양 끝 직선 50cm와 구분하는 검사도 포함한다. 휴대폰 실측 성능 검증은 별도이며 테스트 당시 USB 실기기는 연결되어 있지 않았다.

## KGDet 전환 검토 — 2026-09-30

상태: **미적용**. [공식 저장소](https://github.com/ShenhanQian/KGDet)의 revision `730bc8254440a7e75f56f28f06982c1879f70403`에서 모델 설정·탐지 헤드·연산 구현을 확인했다. 검토용 원본은 `.local/model-research/kgdet/source/`에 두었으며 실행 중인 탐지 API는 기존 GarmentIQ HRNet을 유지한다.

- **가중치 미확보:** README에서 지정한 `KGDet_epoch-12.pth`의 공식 OneDrive 링크는 Microsoft 로그인 페이지로 이동한다. 공개 응용 저장소 Clothware의 대체 Google Drive 링크는 HTTP 404다. 공식 GitHub Releases 및 공개 포크 8개 파일 트리에서도 가중치를 찾지 못했다. 로그인 화면을 가중치로 저장하거나 무작위 초기화 모델로 대체하지 않았다.
- **원본 CPU 실행 불가:** `mmdetection/mmdet/ops/dcn/deform_conv.py`의 forward는 CPU 텐서에 대해 `NotImplementedError`를 발생시킨다. 실제 KGDet 탐지 헤드가 이 DeformConv를 사용한다. 단순히 `device=cpu`로 변경할 수 없으며 CPU 지원 연산으로 이식한 뒤 결과 일치 검증이 필요하다.
- **환경 차이:** 원본은 PyTorch 1.4.0·torchvision 0.5.0·CUDA 10.1·mmcv 0.2.13을 사용한다. 공식 설정은 ResNet-50 + FPN2, 294개 측정점, 최대 1333×800 크기에 맞춘 입력이다. 현재 HRNet의 ONNX 파일을 그대로 바꿔 끼우는 방식은 호환되지 않는다. 기존 Python 환경에 구형 패키지는 설치하지 않았다.
- **검증 범위:** 소스 정적 검토와 다운로드 접근 검증만 수행했다. 학습된 모델 추론·정확도·지연시간·실제 메모리 사용량은 가중치 부재로 검증하지 못했다. 기존 HRNet의 623MB 결과를 KGDet에 적용하지 않는다.

진행에 필요한 입력은 접근 가능한 학습 가중치 파일 또는 다운로드 URL이다. 확보 후 별도 변환 환경에서 체크포인트 구조 확인 → CPU 연산 이식 → 좌표·종류·신뢰도 출력 대응 → 실제 프로세스 메모리·지연 측정 순서로 검증한다. 기준을 통과한 뒤 기존 `/api/wardrobe/landmarks/`를 교체한다. KGDet의 출력 visibility 값과 HRNet heatmap 점수는 같은 의미라고 가정하지 않는다.

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

현재 PC의 프로젝트 경로는 `C:\Users\JEONG\Desktop\SNU\2026-2\SWPP\stylemate\swpp-2026-project-team-14\local-dev\android`다. 이 경로의 `app/src/main/java/com/stylemate/localdev/`가 수정하는 실제 소스이며, 커밋 전 변경도 Android Studio 빌드에 반영된다.

Android Studio 실행 시 상단 구성에서 `app`, 대상에서 연결한 휴대폰을 선택하고 `Run`을 누른다. 기존 실행 구성은 `StyleMateLocal.app` 모듈을 빌드한 후 설치·실행한다. `MeasurementActivity.kt`의 반팔·긴팔·바지 선택 및 `GarmentLandmarks.kt`의 탐지 요청 코드로 최신 소스 여부를 확인한다. 실행한 앱에서는 `촬영하기` 안에 종류 선택이 표시된다. 외부 파일 변경이 편집기에 보이지 않으면 프로젝트를 다시 열어 디스크의 변경을 읽는다.

```powershell
.\local-dev\android.ps1 build
.\local-dev\android.ps1 run
```

build는 디버그 APK와 Android lint를 실행한다. Gradle 캐시·Android 도구 설정·개발용 서명 키는 .local/에 격리하고 Kotlin 컴파일은 Gradle 프로세스 안에서 수행한다. 첫 빌드는 테스트 전용 디버그 키를 생성하며 이 키는 배포에 사용하지 않는다. run은 **이미 실행 중인 에뮬레이터 또는 연결된 기기 한 대**에 설치하고 앱을 실행한다. Device Manager에서 SDK 36 에뮬레이터를 실행한 뒤 사용한다.

자동 탐색이 안 되면 경로를 지정한다.

```powershell
.\local-dev\android.ps1 build -JavaHome 'C:\path\to\jdk-21' -AndroidSdk 'C:\path\to\Android\Sdk'
```

옷장 앱은 에뮬레이터에서 http://10.0.2.2:8001, 실기기에서 http://127.0.0.1:8001을 사용한다. APK는 local-dev/android/app/build/outputs/apk/debug/app-debug.apk에 생성된다.

실제 Android 기기를 USB로 연결할 경우:

```powershell
adb reverse tcp:8001 tcp:8001
```

실기기 주소는 코드에서 자동 선택하므로 별도 수정하지 않는다. USB 재연결 후에는 포트 연결을 다시 설정한다. Android Studio의 Run은 Django·MySQL을 시작하지 않으므로 서버는 `dev.ps1 start`로 실행해 둔다. 개발 서버를 외부 네트워크에 공개하지 않는다. HTTP 허용은 debug manifest의 로컬 주소에만 적용되며 release에는 적용하지 않는다.

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
