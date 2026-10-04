# StyleMate 기능 구조

옷장 기능은 팀의 `frontend/`·`backend/`에서 실행한다. Android는 공통 앱, Django는 `manage.py`와 `config.wsgi`를 사용한다.

```text
swpp-2026-project-team-14/
|-- frontend/app/src/
|   |-- main/java/com/swpp/stylemate/
|   |   |-- MainActivity.kt                       # 공통 앱 진입
|   |   |-- data/
|   |   |   |-- BodyMeasurements.kt              # 체형 치수·선호 정보 모델
|   |   |   |-- RemoteBodyAnalyzer.kt            # 체형 분석 API 호출
|   |   |   `-- wardrobe/
|   |   |       |-- WardrobeRepository.kt         # 옷 목록·사진 조회와 저장 요청
|   |   |       |-- GarmentAnalysis.kt            # Gemini 분석 API 호출
|   |   |       |-- GarmentLandmarks.kt           # 자동 측정점 요청·응답 검증
|   |   |       |-- MeasurementGeometry.kt       # AR 광선·평면 교점과 실측 경로 계산
|   |   |       `-- CaptureFiles.kt               # 옷 사진 임시 캐시 관리
|   |   `-- ui/
|   |       |-- StyleMateApp.kt                   # 온보딩·홈·옷장·마이프로필 탭
|   |       |-- components/ScreenComponents.kt   # 체형·옷장 공통 상단바·하단 버튼·섹션 제목
|   |       |-- theme/Theme.kt                    # 크림·테라코타 공통 테마
|   |       |-- profile/                         # 체형 촬영·수정·프로필 화면
|   |       `-- wardrobe/
|   |           |-- WardrobeScreen.kt            # 옷장 목록·오류 재시도·카드 정렬·사진 확인·편집 연결
|   |           |-- WardrobeViewModel.kt         # 목록·저장 상태
|   |           |-- GarmentAnalysisViewModel.kt  # 분석 진행·실패 상태
|   |           |-- WardrobeColorField.kt       # 색상표·사진 좌표 변환·픽셀 색상 선택
|   |           |-- GarmentEditor.kt             # 특징·실측·메모 편집과 하단 고정 저장
|   |           `-- capture/
|   |               |-- MeasurementActivity.kt  # 실측 촬영·측정점 확인·수정
|   |               `-- MeasurementCamera.kt    # AR 카메라·촬영 프레임 고정
|   |-- test/java/com/swpp/stylemate/             # 체형 계약·실측 기하 단위 검사
|   `-- androidTest/java/com/swpp/stylemate/      # 옷장 UI·사진 색상 선택·탭 연결·측정점 검사
|-- backend/
|   |-- config/
|   |   |-- settings.py                          # 공통 API·MySQL·사진·모델 설정
|   |   |-- urls.py                              # 체형·옷장 API 연결
|   |   `-- test_settings.py                     # 외부 서비스 없는 옷장 테스트 환경
|   |-- apps/
|   |   |-- body_profiles/                      # 체형 분석 요청 처리
|   |   `-- wardrobe/
|   |       |-- urls.py                          # 옷장 API 경로
|   |       |-- views.py                         # 분석 초안·저장·수정·조회
|   |       |-- analysis.py                      # Gemini 호출·이미지 검증
|   |       |-- landmarks.py                     # HRNet 추론·측정 경로 복원
|   |       |-- schema.py                        # 이름·분류·HEX 색상표·선택 속성 검증
|   |       |-- editor.py                        # 실측·사용자 입력 검증
|   |       |-- models.py                        # 사진 경로·옷 JSON·메모 저장
|   |       |-- migrations/                      # 옷장 테이블 생성·JSON 구조 변경
|   |       |-- tests.py                         # AI 계약·DB 저장·재조회 검사
|   |       `-- test_landmarks.py                # 원본 사진의 자동 측정점·좌표 변환 검사
|   |-- body_analysis/                           # MediaPipe 체형 분석
|   |-- wardrobe/garment-landmarks/              # HRNet 가중치·메타데이터·라이선스
|   |-- scripts/prepare_landmarks.py             # 빌드 시 HRNet 다운로드·검증·ONNX 변환
|   `-- tests/                                   # 체형 분석·공통 API 회귀 검사
`-- docs/wardrobe/                               # 옷장 설계·실행 안내
```

체형 분석 확인 → 옷장 탭 → AR 촬영 → 자동 점 확인 → cm 계산 → Gemini 이름·분류·색상 분석 → 사용자 편집 → MySQL 저장 순서다. 체형과 옷장 API는 같은 서버 주소를 사용한다.

체형·옷장 화면은 공통 `ScreenScaffold`·`PrimaryActionBar`·`SectionTitle`과 `StyleMateTheme`를 사용한다. 글꼴·여백·카드 기준은 [디자인](design.md#3-공통-디자인-기준--현재-구현)에 정리한다.

옷장의 `pattern`·상대 기장 `length`·착용 정보와 격식·여밈·디테일·어깨 구조·넥라인 속성은 제거했다. 러블리·소매 선택 속성도 제거하고, 색상은 HEX 배열로 저장한다. 마이그레이션은 색 이름을 색상값으로 변환하며 기존 속성 JSON을 정리하고 `user_properties` 컬럼을 삭제한다. 어깨너비 등 치수와 측정 출처는 유지하며 측정점 좌표는 저장하지 않는다. 원본 사진에 HRNet을 적용하고 누끼 모델은 사용하지 않는다. 체형 사진은 메모리 처리 후 폐기하며 옷 사진만 옷장 저장소에 보관한다.

체형 분석·자동 측정점에는 DB가 필요 없다. 옷장 분석 초안·목록·저장에는 MySQL 연결이 필요하며 미설정 시 503을 반환한다. S3·사용자 구분·체형 프로필 영구 저장·추천 치수 비교는 미구현이다. 실행 설정은 [local-development.md](local-development.md), 계약은 [wardrobe-spec.md](wardrobe-spec.md)를 따른다.
