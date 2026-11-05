# Nano Banana 2.1 가상 피팅

피팅은 기존 Django CPU 백엔드가 OpenRouter의 `google/gemini-nano-banana-2.1`을 호출한다. GPU 서버, PyTorch, 가중치, 별도 피팅 서버는 필요 없다. 옷장·체형 분석 기능의 기존 의존성은 별개다.

## 실행 설정

- 서버의 `OPENROUTER_API_KEY`에 유료 키를 설정한다. 키를 APK·코드·Git에 넣지 않는다.
- `FITTING_CONCURRENCY=2`가 기본이며 1~4개로 제한한다. 생성은 2K, 사진 비율은 2:3이다.
- 기존 백엔드 Dockerfile처럼 **Gunicorn 1프로세스, 백엔드 1인스턴스**로 실행한다. 작업 큐는 프로세스 메모리에 있으므로 재시작 시 미완료 작업과 미수신 결과는 사라진다.
- 실행 명령과 인증 DB 설정은 기존 백엔드와 같다. 피팅 전용 DB·마이그레이션·이전 DB 복구는 없다.

## 처리 흐름

1. 앱에서 갤러리 또는 체형별 가상 모델 사진을 선택하고 외부 전송에 동의한다.
2. 인증된 `POST /api/fittings/jobs/`가 작업 ID·작업 토큰을 반환한다. 본문은 `consent`, base64 `person`, `garments`다.
3. 의류는 `category`(`tops`·`bottoms`·`outerwear`), base64 `image`, `photo_type`(`flat-lay`·`model`), 선택적 `open_front`를 사용한다. 아우터에는 상의·하의가 함께 필요하다. 해상도 모드·스텝 옵션은 없다.
4. 메모리 큐에서 최대 20개 작업을 수용하고 설정된 개수만 외부 API를 동시에 호출한다. 사람 사진과 모든 의류 원본을 한 요청에 넣는다. 유료 중복 생성을 막기 위해 생성 요청을 자동 재시도하지 않는다.
5. `GET /api/fittings/jobs/{id}/`로 완료를 확인하고 `/image/`로 JPEG 결과를 받는다. 성공·실패 모두 StyleMate 서버의 입력 바이트를 지운다. API가 제공하면 생성 비용도 작업 상태에 기록한다.
6. 앱은 개인 저장소에 결과를 저장하고 서버 작업을 `DELETE`한다. 입력·미수신 결과는 서버에서 만료 시에도 제거한다. 작업·결과의 TTL은 900초이며 미수신 결과는 최대 40개로 제한한다.

앱이 접수한 작업 ID·토큰을 저장한 뒤 통신이 끊기면 WorkManager가 기존 작업의 조회를 재시도한다. 사진을 다시 올리거나 유료 생성을 반복하지 않으며, 결과 저장 후 서버 작업을 삭제한다. 서버가 재시작되거나 결과 TTL이 지나면 다시 생성해야 한다. 최초 접수 응답을 받기 전에 연결이 끊긴 경우에는 접수 여부를 확인할 수 없으므로 자동 재접수하지 않는다.

피팅 완료 알림은 저장된 선택이 없을 때 ON으로 시작한다. 사용자가 선택한 OFF는 유지하고 모든 코디에서 같은 계정의 벨 설정을 공유한다. Android 13 이상에서는 알림이 ON이고 권한이 없으면 피팅 화면을 열 때 시스템 권한을 요청한다. 거부하면 OFF로 저장하며 별도 확인 팝업은 띄우지 않는다.

작업 조회·이미지·삭제에는 로그인 계정과 `X-Fitting-Token`이 모두 필요하다. 다른 계정은 404를 받는다. 원본·결과는 서버 파일이나 DB에 저장하지 않는다. 외부 제공사의 사진 보관·삭제는 StyleMate 서버의 삭제와 별개이며 앱 동의 문구에 외부 전송과 보관 정책을 명시한다.

## 검증

실제 유료 생성 대신 로컬 HTTP 응답과 합성 사진을 사용한다. 아래 커버리지는 소프트웨어 흐름에 대한 수치이며 모델의 착장 품질이나 실제 제공사의 응답시간을 평가하지 않는다.

| 과정 | 자동 검증 항목 | 주요 테스트 |
| --- | --- | --- |
| 사진·체형 선택 | 갤러리 선택·취소·손상 사진, 기본 체형 입력, 24개 모델 자산·선택 기준 | `FittingFlowTest`, `PhotoFittingTest`, `VirtualFittingModelsTest` |
| 의류·작업 접수 | 원본 바이트·사진 유형, 상의·하의·아우터 순서, 동의·인증·입력 제한 | `FittingWorkerTest`, `TryOnClientTest`, `apps/fittings/tests.py` |
| 생성·대기열 | 제공사 1회 호출, 동시 호출 제한, 10건 동시·30건 연속 처리, 실패·취소·자동 만료 | `test_fitting_generation.py`, `test_fitting_jobs.py` |
| 백그라운드·복구 | 중복 접수 차단, 작업 체크포인트, 통신 단절 후 재개, 만료·로그아웃·계정 변경 | `FittingFlowTest`, `FittingWorkerTest`, `TryOnClientTest`, `FittingTaskStoreTest` |
| 저장·알림·다시 열기 | 개인 결과 저장·갤러리 저장 픽셀, 알림 ON/OFF·권한 차단, 알림 결과 화면·다른 계정 차단, 결과 재사용 | `FittingFlowTest`, `PhotoFittingTest`, `FittingRepositoryTest` |
| 사진 삭제 | 서버 처리 성공·실패·만료 시 입력 삭제, 앱 접수 후 임시 사진 삭제, 결과 수신 후 서버 작업 삭제 | 백엔드 작업·API 테스트 및 Android 작업·흐름 테스트 |

앱의 전체 흐름 테스트는 `frontend/app/src/androidTest/java/com/swpp/stylemate/data/tryon/`, 화면 테스트는 같은 소스 루트의 `home/PhotoFittingTest.kt`에 있다. WorkManager의 [공식 테스트 도구](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/worker-impl)인 `work-testing`과 기존 MockWebServer를 테스트 의존성으로 사용한다. 기본 앱 생성자는 유지하고, 테스트에서만 서버 주소와 계정을 주입한다. 이전 FASHN 결과나 테스트 DB를 가져오는 호환 로직은 없다.

### 커버리지 결과 — 2026-11-05

| 범위 | 문장·줄 C0 | 분기 C1 |
| --- | ---: | ---: |
| 백엔드 `apps/fittings` | **100.0% (311/311)** | **98.8% (79/80)** |
| Android `data/tryon` + `ui/tryon`, 단위·기기 테스트 통합 | **95.0% (647/681)** | **80.8% (422/522)** |
| 위 Android 범위 중 피팅 화면 `FittingPreview.kt` | 93.3% (319/342) | 73.7% (171/232) |

- 피팅 전용 검증은 백엔드 49건, Android 단위 40건, 기기 39건이다. 기본 알림 ON·저장된 OFF 유지·권한 허용/거부·다른 코디에서 설정 유지를 포함한다. 최종 기기 실행은 Android 16/API 36 에뮬레이터에서 실패·건너뜀 없이 완료했다.
- 전체 회귀 검증은 pytest 266건 통과·기존 1건 건너뜀, Django 217건 통과, Android 단위 215건 통과다. APK·계측 APK 빌드와 린트도 통과했다. 린트 오류는 0개이며 의존성 업데이트 안내 등을 포함한 경고 31개가 남는다.
- 백엔드는 coverage.py 문장·분기, Android는 JaCoCo 소스 파일의 줄·분기 카운터를 사용한다. 서로 다른 지표를 하나의 비율로 합산하지 않는다. Android 수치는 위 두 패키지에 한정하며 앱 전체 커버리지가 아니다.
- 모든 예외·플랫폼 분기가 실행된 것은 아니다. 학교 서버·실제 OpenRouter 통합 검증과 이미지 품질 평가는 별도다.

### 재실행

백엔드 폴더에서 기존 개발 환경으로 실행한다. 보고서는 `htmlcov/`, `coverage.json`에 생성된다.

```sh
python -m coverage erase
python -m coverage run -m pytest -q
python -m coverage run --append manage.py test apps --settings=config.test_settings --noinput
python -m coverage html
python -m coverage json -o coverage.json
python scripts/coverage_by_component.py coverage.json
```

Android는 `frontend/`에서 테스트 기기를 지정한다. 아래는 Windows PowerShell 예시이며, 에뮬레이터가 실행 중이어야 한다.

```powershell
$env:ANDROID_SERIAL = "emulator-5556"
.\gradlew.bat -PandroidTestCoverage "-Pandroid.testInstrumentationRunnerArguments.class=com.swpp.stylemate.home.PhotoFittingTest,com.swpp.stylemate.data.tryon.FittingWorkerTest,com.swpp.stylemate.data.tryon.FittingFlowTest" createDebugAndroidTestCoverageReport createAggregatedCoverageReport
python scripts/coverage_by_layer.py app/build/intermediates/aggregated_code_coverage_data/global/collectDebugAggregatedCoverage/debugAppAggregatedXmlReport.xml --package-prefix com/swpp/stylemate/data/tryon --package-prefix com/swpp/stylemate/ui/tryon
```

Android 13 이상에서 알림 게시까지 검증하려면 테스트 앱의 알림 권한을 허용한다. CI는 새 에뮬레이터에 APK를 설치한 후 권한을 부여한다. 개인 폰에서는 에뮬레이터 사용을 권장하며, `ANDROID_SERIAL`로 대상 기기를 명시한다. `gradle.properties`의 `android.injected.androidTest.leaveApksInstalledAfterRun=true`는 기기 테스트 종료 후 앱 삭제로 로컬 데이터가 사라지는 것을 방지한다.

CI는 백엔드 피팅 테스트를 기존 테스트에 포함하고, Android 단위 테스트와 전용 에뮬레이터 작업에서 피팅 커버리지를 보고한다. Android HTML 보고서는 `app/build/reports/aggregated_code_coverage_html_report/global/index.html`이며 CI 아티팩트에도 업로드한다. 실제 사진·키·DB·커버리지 원본 결과는 커밋하지 않는다.

실측 비교와 모델 선택 과정은 [모델 비교 문서](model-comparison-2026-10-30.md), 내장 모델 사진은 [가상 모델 문서](virtual-models.md)를 따른다.
