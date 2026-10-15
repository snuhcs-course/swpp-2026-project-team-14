# StyleMate 기능 구조

옷장 기능은 팀의 `frontend/`·`backend/`에서 실행한다. Android는 공통 앱, Django는 `manage.py`와 `config.wsgi`를 사용한다.

```text
swpp-2026-project-team-14/
|-- frontend/app/src/
|   |-- main/java/com/swpp/stylemate/
|   |   |-- MainActivity.kt                       # 공통 앱 진입
|   |   |-- data/
|   |   |   |-- BodyMeasurements.kt              # 체형 치수·선호 정보 모델
|   |   |   |-- LocalUserStore.kt                # 비로그인·계정별 메타데이터와 옷 사진 로컬 저장
|   |   |   |-- BodyProfileStore.kt              # 체형·독립 스타일·온보딩 상태 저장 연결
|   |   |   |-- AuthRepository.kt                # 인증·프로필 API·세션 복원·만료·세션별 요청 검증
|   |   |   |-- AuthSessionStore.kt              # Keystore AES-GCM 암호화 세션 보관·백업 제외
|   |   |   |-- recommendation/                 # 코디 로컬 저장·취향 저장·서버 계산 요청
|   |   |   |-- RemoteBodyAnalyzer.kt            # 체형 분석 API 호출
|   |   |   `-- wardrobe/
|   |   |       |-- WardrobeRepository.kt         # 게스트 로컬·계정 서버 옷 조회·저장·인증 사진 요청
|   |   |       |-- GarmentAnalysis.kt            # 게스트 비저장 분석·계정 인증 분석 요청
|   |   |       |-- GarmentLandmarks.kt           # 자동 측정점 요청·응답 검증
|   |   |       |-- MeasurementGeometry.kt       # AR 광선·평면 교점과 실측 경로 계산
|   |   |       |-- CaptureFiles.kt               # 옷 사진 임시 캐시 관리
|   |   |       `-- CaptureType.kt                # 측정 종류 → 하의 세부 종류 기본값
|   |   `-- ui/
|   |       |-- StyleMateApp.kt                   # 공통 탭·인증 진입·계정/게스트 저장소 및 ViewModel 전환
|   |       |-- components/ScreenComponents.kt   # 체형·옷장 공통 상단바·하단 버튼·섹션 제목
|   |       |-- theme/Theme.kt                    # 크림·테라코타 공통 테마
|   |       |-- profile/
|   |       |   |-- AuthScreens.kt               # 로그인 팝업·회원가입·비밀번호 표시·오류 안내
|   |       |   |-- AuthViewModel.kt             # 중복 제출 방지·시도 제한 대기·포그라운드 검증·만료 안내
|   |       |   |-- BodyProfileViewModel.kt      # 체형 분석·계정 체형 저장 및 삭제·로컬 상태 연결
|   |       |   `-- MyProfileScreen.kt           # 스타일 편집·계정 표시·체형 상세·프로필 촬영 화면과 연결
|   |       `-- wardrobe/
|   |           |-- WardrobeScreen.kt            # 옷장 목록·오류 재시도·카드 정렬·사진 확인·편집 연결
|   |           |-- WardrobeViewModel.kt         # 목록·저장 상태
|   |           |-- GarmentAnalysisViewModel.kt  # 분석 진행·실패 상태
|   |           |-- WardrobeColorField.kt       # 색상표·사진 좌표 변환·픽셀 색상 선택
|   |           |-- GarmentEditor.kt             # 특징·실측·메모 편집과 하단 고정 저장
|   |           |-- GarmentLabels.kt             # 추천 라벨 확인·수정·실패 후 수동 확인·저장 가능 상태
|   |           |-- FrontPhoto.kt                # 상의·아우터 앞면 사진 카드
|   |           `-- capture/
|   |               |-- MeasurementActivity.kt  # 실측 촬영·측정점 확인·수정
|   |               `-- MeasurementCamera.kt    # AR 카메라·촬영 프레임 고정
|   |-- test/java/com/swpp/stylemate/             # 인증 API·만료·오래된 응답 차단·로컬 저장·체형 계약 검사
|   `-- androidTest/java/com/swpp/stylemate/      # 인증 입력·Keystore 암호화·옷장 UI·탭 연결·측정점 검사
|-- backend/
|   |-- config/
|   |   |-- settings.py                          # 공통 API·사용자 모델·MySQL·사진·모델 설정
|   |   |-- urls.py                              # 체형·옷장 API 연결
|   |   `-- test_settings.py                     # 외부 서비스 없는 옷장 테스트 환경
|   |-- apps/
|   |   |-- users/
|   |   |   |-- models.py                        # 사용자·체형·취향·토큰 해시 저장
|   |   |   |-- auth.py                          # 토큰 발급·만료 확인·DB 및 인증 접근 제한
|   |   |   |-- limits.py                        # DB 공유 카운터·5회 실패 잠금·IP 요청 제한
|   |   |   |-- profile.py                       # 체형 허용 필드·16종 치수·중첩 메타데이터 검증
|   |   |   |-- urls.py                          # 가입·로그인·로그아웃·내 정보 경로
|   |   |   |-- views.py                         # 인증·프로필 조회 및 부분 수정 API
|   |   |   |-- migrations/0001_initial.py        # 사용자 테이블 최초 생성
|   |   |   |-- migrations/0002_authtoken.py      # 만료 가능한 토큰 해시 테이블 생성
|   |   |   |-- migrations/0003_authratelimit.py  # 인증 요청·실패 횟수 공유 테이블 생성
|   |   |   |-- tests.py                         # 계정·토큰·프로필·계정 간 접근 차단 검사
|   |   |   |-- test_security.py                 # 시도 제한·만료 경계·잘못된 프로필 입력 검사
|   |   |   `-- test_migrations.py               # 기존 무소유 데이터 정리·소유 데이터 보존 검사
|   |   |-- body_profiles/                      # 체형 분석 요청 처리
|   |   |-- outfits/
|   |   |   |-- models.py                        # 코디·소유 사용자·옷 연결 저장
|   |   |   |-- views.py                         # 계정 코디 API·비로그인 비저장 계산 API
|   |   |   |-- migrations/0002_outfit_user.py    # 코디 소유 사용자 외래 키 추가
|   |   |   `-- migrations/0003_alter_outfit_user.py # 무소유 코디 정리·소유자 필수 전환
|   |   `-- wardrobe/
|   |       |-- urls.py                          # 옷장 API 경로
|   |       |-- views.py                         # 분석 초안·저장·수정·조회·추천 라벨 미리보기
|   |       |-- analysis.py                      # 이미지 검증·Gemini 요청·응답 처리
|   |       |-- landmarks.py                     # HRNet 추론·좌표 복원·치수별 경로 계산
|   |       |-- schema.py                        # 속성 타입·한국어 선택지·Gemini 계약(추천용 details 포함)·입력 검증
|   |       |-- editor.py                        # 치수 정의·측정값·저장 요청(추천 라벨 포함) 검증
|   |       |-- models.py                        # 소유 사용자·사진 경로·옷 속성·치수·메모·추천 라벨 저장
|   |       |-- migrations/0001_initial.py        # 옷장 테이블 최초 생성
|   |       |-- migrations/0002_garment_labels.py # 추천 라벨 컬럼 추가
|   |       |-- migrations/0003_garment_user.py   # 옷 소유 사용자 외래 키 추가
|   |       |-- migrations/0004_alter_garment_user.py # 무소유 옷 정리·소유자 필수 전환
|   |       |-- tests.py                         # AI 계약·DB 저장·재조회 검사
|   |       `-- test_landmarks.py                # 원본 사진의 자동 측정점·좌표 변환 검사
|   |-- recommendation/                          # 저장 라벨 → K-Fashion 입력, 계절·주색/서브색·모델 어휘 정규화
|   |-- body_analysis/                           # MediaPipe 체형 분석
|   |-- wardrobe/garment-landmarks/              # HRNet 가중치·메타데이터·라이선스
|   |-- scripts/prepare_landmarks.py             # 빌드 시 HRNet 다운로드·검증·ONNX 변환
|   `-- tests/                                   # 체형 분석·추천 모델 입력 계약·공통 API 회귀 검사
`-- docs/wardrobe/                               # 옷장 설계·실행 안내
```

촬영·저장 흐름은 다음과 같다.

`옷장 → AR 촬영 → 자동 점 확인·수정 → cm 계산 → Gemini 분석 → 사용자 편집 → 기기 로컬 저장`

- Android: 체형·옷장은 같은 서버 주소와 공통 `StyleMateTheme`·화면 컴포넌트를 사용한다.
- 측정: HRNet은 원본 사진의 2D 점을 제안하고, Android ARCore가 바닥 평면을 기준으로 cm를 계산한다.
- 분석: 상의·아우터는 앞면 사진, 하의는 측정 사진을 보낸다. Gemini는 이름·분류·색상을 생성하고 세부 종류·스타일을 제안한다. 응답은 `id`·`model`·`attributes`와 추천용 `details`(무늬·넥라인·옷깃·디테일·겉으로 분명한 소재, 저장하지 않음)다.
- 추천 라벨: 저장 전 `POST labels/`가 치수와 체형으로 핏·기장·소매·계절 등을 계산하고, 사용자가 확인·수정한 값을 `labels`로 함께 저장한다. 설계는 [추천 문서](../recommendation/README.md)를 따른다.
- 저장: 현재 Android 비로그인 경로는 앱 전용 JSON과 사진 파일을 사용한다. 인증된 서버 저장 API는 MySQL·서버 사진 저장소를 사용한다.
- DB 의존성: 체형·측정점·비저장 옷 분석·로컬 옷 기반 코디 계산은 DB 없이 실행된다. 계정 옷·코디 조회·저장은 MySQL 설정과 인증이 필요하다.

가입·로그인·로그아웃·프로필 API와 계정별 옷·코디 접근 제한, 앱 인증 화면·만료·계정 전환·비로그인 로컬 저장이 구현되었다. 소유자 외래 키는 필수이며 전환 마이그레이션은 소유자 없는 기존 행만 정리한다. 계정 가져오기, 새 8페이지 온보딩과 S3·추천 치수 비교는 미구현이다. 저장 범위와 인증 계약은 [로그인 문서](../login/README.md)를 따른다.

화면 기준은 [디자인](design.md), 데이터·API 규칙은 [명세](wardrobe-spec.md), 등록 과정은 [데이터 흐름](wardrobe-flow.md), 실행·배포는 [실행 안내](local-development.md)를 따른다.
