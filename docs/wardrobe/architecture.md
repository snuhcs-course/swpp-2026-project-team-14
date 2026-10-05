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
|   |       |-- analysis.py                      # 이미지 검증·Gemini 요청·응답 처리
|   |       |-- landmarks.py                     # HRNet 추론·좌표 복원·치수별 경로 계산
|   |       |-- schema.py                        # 속성 타입·한국어 선택지·Gemini 계약·입력 검증
|   |       |-- editor.py                        # 치수 정의·측정값·저장 요청 검증
|   |       |-- models.py                        # 사진 경로·현재 옷 속성·치수·메모 저장
|   |       |-- migrations/0001_initial.py        # 최종 옷장 테이블 최초 생성
|   |       |-- tests.py                         # AI 계약·DB 저장·재조회 검사
|   |       `-- test_landmarks.py                # 원본 사진의 자동 측정점·좌표 변환 검사
|   |-- body_analysis/                           # MediaPipe 체형 분석
|   |-- wardrobe/garment-landmarks/              # HRNet 가중치·메타데이터·라이선스
|   |-- scripts/prepare_landmarks.py             # 빌드 시 HRNet 다운로드·검증·ONNX 변환
|   `-- tests/                                   # 체형 분석·공통 API 회귀 검사
`-- docs/wardrobe/                               # 옷장 설계·실행 안내
```

촬영·저장 흐름은 다음과 같다.

`옷장 → AR 촬영 → 자동 점 확인·수정 → cm 계산 → Gemini 분석 → 사용자 편집 → MySQL 저장`

- Android: 체형·옷장은 같은 서버 주소와 공통 `StyleMateTheme`·화면 컴포넌트를 사용한다.
- 측정: HRNet은 원본 사진의 2D 점을 제안하고, Android ARCore가 바닥 평면을 기준으로 cm를 계산한다.
- 분석: Gemini는 이름·분류·색상을 생성한다. 응답은 `id`·`model`·`attributes`다.
- 저장: 옷 속성·치수·메모·사진 경로는 MySQL, 옷 사진은 서버 파일 저장소에 보관한다. 최초 AI 결과는 별도 복제하지 않는다.
- DB 의존성: 체형 분석·측정점 API는 DB 없이 실행된다. 옷장 분석·조회·저장은 MySQL 설정이 필요하다.

S3·사용자 구분·체형 프로필 영구 저장·추천 치수 비교는 미구현이다.

화면 기준은 [디자인](design.md), 데이터·API 규칙은 [명세](wardrobe-spec.md), 등록 과정은 [데이터 흐름](wardrobe-flow.md), 실행·배포는 [실행 안내](local-development.md)를 따른다.
