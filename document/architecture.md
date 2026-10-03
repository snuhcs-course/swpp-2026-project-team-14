# StyleMate 기능 구조

옷장 기능은 현재 `local-dev/`에서 개발한다. 아래 트리는 촬영·실측·AI 분석·편집·저장 구현과 관련 테스트만 표시한다.

## 구현 구조

```text
swpp-2026-project-team-14/
|-- local-dev/
|   |-- android/app/src/                         # Kotlin·Compose 앱
|   |   |-- main/java/com/stylemate/localdev/
|   |   |   |-- MainActivity.kt                  # 앱 테마와 화면 전환
|   |   |   |-- WardrobeScreen.kt                # 서버 옷장 목록, 촬영 진입, 편집 화면 연결
|   |   |   |-- MeasurementActivity.kt           # 의류 종류·누끼 선택, 자동 측정 경로 표시·수정
|   |   |   |-- MeasurementCamera.kt             # 기울기 안내, 같은 프레임의 사진·AR 자세 고정
|   |   |   |-- GarmentLandmarks.kt              # 측정점 탐지 요청·응답 좌표 검증
|   |   |   |-- MeasurementGeometry.kt           # 카메라 광선·평면 교점, 경로 길이·자세 계산
|   |   |   |-- CaptureFiles.kt                  # 사진 캐시 저장·삭제, 미리보기 생성
|   |   |   |-- GarmentEditor.kt                 # 특징·치수 수정, 착용 정보·메모 입력
|   |   |   |-- WardrobeRepository.kt            # 목록·사진 조회, 저장 요청과 화면 상태
|   |   |   `-- GarmentAnalysis.kt               # Django 분석 요청, 로딩·결과·오류 상태 관리
|   |   |-- test/java/com/stylemate/localdev/
|   |   |   `-- MeasurementGeometryTest.kt       # 높이·각도별 실측 복원, 경로 길이 검증
|   |   `-- androidTest/java/com/stylemate/localdev/
|   |       |-- GarmentEditorTest.kt             # 치수·메모 편집, 분류 변경·입력 오류 검증
|   |       |-- GarmentLandmarksTest.kt          # 탐지 좌표 범위·종류 불일치 검증
|   |       |-- WardrobeScreenTest.kt            # 옷장 화면·필터·촬영 버튼 검증
|   |       `-- CaptureFilesTest.kt              # 사진 파일·미리보기·삭제 검증
|   |-- prepare-landmarks.py                    # HRNet 변환·U²-Net 준비, 실제 API 성능 측정
|   `-- backend/                                # Django 분석·옷장 서버
|       |-- sandbox/
|       |   |-- urls.py                          # 분석·편집·목록·저장·사진 API 연결
|       |   `-- settings.py                      # 서버 환경·Gemini 키 로딩
|       `-- wardrobe/
|           |-- views.py                        # 분석 초안 생성, 저장·수정·조회, 사진 반환
|           |-- analysis.py                     # 이미지 검사·축소, Gemini 호출, 오류 처리
|           |-- landmarks.py                    # HRNet 측정 경로 연결, 누끼 좌표를 원본으로 복원
|           |-- foreground.py                   # U²-Net 배경 제거·의상 영역 추출
|           |-- test_landmarks.py               # 좌표 변환·입력 제한·탐지 API 검증
|           |-- models.py                       # 초안·저장된 옷, 원본 분석·치수·메모 모델
|           |-- migrations/                     # MySQL 옷장 테이블 생성
|           |-- editor.py                       # 편집 선택지, 사용자 입력·치수 검증
|           |-- schema.py                       # Gemini 3개 필드 검증, 수동 입력 사전·한글 표시명
|           `-- tests.py                        # 분석·입력 검증, MySQL 저장·수정·재조회 테스트
`-- document/                                   # 기능 설계와 실행 안내
    |-- design.md                               # 화면 구성·동작
    |-- wardrobe-spec.md                        # 데이터 스키마·API 계약
    |-- wardrobe-types.md                       # 옷 종류·표시명
    |-- wardrobe-flow.md                        # 촬영부터 저장까지의 처리 설계
    `-- local-development.md                    # 앱·서버 실행과 키 설정
```

## 기능 연결

```text
WardrobeScreen → MeasurementActivity
  → GarmentLandmarks → POST /api/wardrobe/landmarks/
  → landmarks.py: HRNet CPU 추론 → 화면에 측정점 표시
  → MeasurementCamera: 사진·AR 평면 고정
  → GarmentLandmarks: 고정 사진 → U²-Net 누끼 → HRNet 재탐지 → 원본 좌표 복원
  → MeasurementGeometry: 경로의 각 점을 AR 평면에 투영 → 구간 거리 합산
  → 사용자: 점 확인·수정
  → CaptureFiles: 사진 임시 저장
  → WardrobeScreen: 사진·치수 확인

분석하기 → GarmentAnalysis → POST /api/wardrobe/analyze/
  → views.py → analysis.py → Gemini gemini-3.1-flash-lite
  → schema.py: 응답 검증·한글 변환
  → models.py: 사진 경로와 분석 초안 보관
  → GarmentEditor: 분석 결과·치수 수정, 메모 입력
  → WardrobeRepository → PUT /api/wardrobe/items/{id}/
  → editor.py: 입력 검증 → MySQL: 옷장에 확정 저장
  → GET /api/wardrobe/items/ → WardrobeScreen: 목록·사진 표시
  → 카드 선택 → GarmentEditor: 저장한 옷 다시 수정
```

## 구현 범위

- **구현:** 상의·아우터 7개, 바지·반바지 7개, 스커트 3개 치수의 자동 측정 경로 표시, AR 촬영·치수 수정, Gemini 이름·카테고리·색상 분석, 결과 수정, 치수·착용 정보·메모 저장, 서버 목록·사진 조회, 저장한 옷 재수정.
- **미구현:** S3 연결, 사용자 식별, 삭제, 상세 종류 프리셋 검색. 개인 루프백 테스트 서버에서만 사용한다.
- 옷 JSON은 MySQL, 사진은 `.local/wardrobe-media/`에 보관한다. 분석 초안은 목록에서 제외하고 저장 후에만 표시한다.
- AR 치수와 AI 특징은 별도 관리한다. 치수는 소수 둘째 자리로 반올림하며 정확도 보증을 의미하지 않는다. 메모·착용 정보는 AI에 보내지 않는다.
- 측정점 모델은 PC 서버의 ONNX Runtime에서 실행한다. 미리보기 프레임은 탐지 후 버리며 DB·외부 API에 보내지 않는다. 탐지와 Gemini 분석은 서버 프로세스당 동시에 한 건만 실행한다.

세부 데이터 흐름은 [wardrobe-flow.md](wardrobe-flow.md), 실행 방법은 [local-development.md](local-development.md)를 따른다.
