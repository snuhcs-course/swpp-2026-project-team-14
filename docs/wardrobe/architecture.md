# StyleMate 기능 구조

옷장 기능은 팀의 `frontend/`·`backend/`에서 실행한다. Android는 공통 앱, Django는 `manage.py`와 `config.wsgi`를 사용한다.

```text
swpp-2026-project-team-14/
|-- frontend/app/src/
|   |-- main/java/com/swpp/stylemate/
|   |   |-- MainActivity.kt                       # 공통 앱 진입
|   |   |-- data/
|   |   |   |-- BodyMeasurements.kt              # 체형 치수·선호 정보 모델
|   |   |   |-- LocalUserStore.kt                # 계정별 프로필·옷장 캐시·추천 취향·온보딩 초안
|   |   |   |-- BodyProfileStore.kt              # 체형·독립 스타일·온보딩 상태 저장 연결
|   |   |   |-- AuthRepository.kt                # 인증·프로필·세션·만료·응답 제한·계정 목록 분할 조회
|   |   |   |-- GuestImportRepository.kt         # 가져오기 후보·충돌 조회·지문·계정별 재시도 처리
|   |   |   |-- AuthSessionStore.kt              # Keystore AES-GCM 암호화 세션 보관·백업 제외
|   |   |   |-- recommendation/                 # 계정 코디·외부 검색 API·계정별 취향 저장
|   |   |   |-- RemoteBodyAnalyzer.kt            # 체형 분석 API 호출
|   |   |   `-- wardrobe/
|   |   |       |-- WardrobeRepository.kt         # 계정 서버 옷 조회·저장·캐시·인증 사진 요청
|   |   |       |-- GarmentAnalysis.kt            # 계정 인증 사진 분석·서버 초안 생성
|   |   |       |-- WornGenerationRepository.kt  # 인증된 생성·결과 조회·이미지 선택 요청, 자동 재생성 없음
|   |   |       |-- WornPhotoRepository.kt       # 착용 원본 업로드·자동 후보·여러 초안 준비·일괄 저장 요청
|   |   |       |-- GarmentLandmarks.kt           # 인증된 자동 측정점 요청·응답 검증
|   |   |       |-- MeasurementGeometry.kt       # AR 광선·평면 교점과 실측 경로 계산
|   |   |       |-- CaptureFiles.kt               # 옷 사진 임시 캐시·재개 중인 촬영 파일 보존
|   |   |       `-- CaptureType.kt                # 측정 종류 → 하의 세부 종류 기본값
|   |   `-- ui/
|   |       |-- StyleMateApp.kt                   # 로그인 필수 진입·계정 온보딩/탭·수동 가져오기·만료 시 ViewModel 폐기
|   |       |-- components/ScreenComponents.kt   # 체형·옷장 공통 상단바 교체 슬롯·하단 버튼·섹션 제목
|   |       |-- theme/Theme.kt                    # 크림·테라코타 공통 테마
|   |       |-- profile/
|   |       |   |-- AuthScreens.kt               # 로그인 진입 화면·회원가입·비밀번호 표시·오류 안내
|   |       |   |-- AuthViewModel.kt             # 중복 제출 방지·시도 제한 대기·포그라운드 검증·만료 안내
|   |       |   |-- GuestImportViewModel.kt      # 수동 가져오기·읽기 취소·충돌 선택·저장/새로고침 재시도
|   |       |   |-- GuestImportDialog.kt         # 가져오기 확인·항목별 교체 선택·결과 팝업
|   |       |   |-- BodyProfileViewModel.kt      # 체형 분석·온보딩 초안·핏/무드 서버 저장·완료·계정 연결
|   |       |   |-- OnboardingScreen.kt          # 공통 상단바·8페이지·등록 개수 현황·선택적 분석·계정 옷 등록 반복·완료
|   |       |   |-- PhotoInputScreen.kt          # 체형 사진·기본 정보 입력·온보딩 상단바 연결
|   |       |   |-- ReviewScreen.kt              # 체형 결과·치수 수정·온보딩 확인과 재분석 저장
|   |       |   `-- MyProfileScreen.kt           # 핏·무드 즉시 저장·컬러 편집·계정 카드/로그아웃·기존 데이터 가져오기·체형 상세
|   |       `-- wardrobe/
|   |           |-- WardrobeScreen.kt            # 옷장 목록·등록 방식 선택 시트·옷 선택 메뉴·온보딩/사진/편집 연결
|   |           |-- WardrobeViewModel.kt         # 계정 목록·저장·삭제/중복 방지·외부 추천 상태
|   |           |-- ExternalRecommendationDialog.kt # 선택 옷 메뉴·추천 속성·무신사 바로가기 팝업
|   |           |-- GarmentAnalysisViewModel.kt  # 분석 진행·실패·초안 복원·사진 변경 시 이전 분석 취소
|   |           |-- WardrobeColorField.kt       # 색상표·사진 좌표 변환·픽셀 색상 선택
|   |           |-- GarmentEditor.kt             # 특징·실측·메모 편집·초안 복원·하단 고정 저장
|   |           |-- GarmentEstimates.kt          # 실측과 분리한 읽기 전용 추정값·범위 표시·편집 후 무효화
|   |           |-- WornBatchRegistration.kt     # 사진 업로드 영역·후보 선택·원본 분석/생성·기존 편집과 승인 저장 연결
|   |           |-- WornBatchReviewScreen.kt     # 서비스/미리보기 공용 카드·전체 선택·원본 비교·접이식 치수·처리 스피너
|   |           |-- WornBatchViewModel.kt        # 원본 분석 멱등성·순차 생성·부분 실패/제외·저장·취소 후 상태 보호
|   |           |-- WornRegistration.kt          # 한 벌 직접 사각형 드래그·대상 분석 보관, 앱 진입 버튼 숨김
|   |           |-- WornGenerationViewModel.kt   # 원본 분석·생성 요청 ID·상태 조회·승인·촬영 파일 수명·취소 상태 보호
|   |           |-- WornGenerationReview.kt      # 원본/생성 이미지 비교·승인/거절·명시적 크롭/유료 재생성 선택
|   |           |-- GarmentLabels.kt             # 추천 라벨 확인·수정·실패 후 수동 확인·저장 가능 상태
|   |           |-- FrontPhoto.kt                # 상의·아우터 앞면 사진 카드
|   |           `-- capture/
|   |               |-- MeasurementActivity.kt  # 세션 복원·인증 측정점·실측 촬영·확인·수정
|   |               `-- MeasurementCamera.kt    # AR 카메라·촬영 프레임 고정
|   |-- debug/
|   |   |-- AndroidManifest.xml                  # 개발 빌드 전용 UI 미리보기 Activity(앱 목록 미표시, adb로 실행)
|   |   `-- java/com/swpp/stylemate/ui/wardrobe/
|   |       |-- WornPreviewActivity.kt            # 개인 앱 파일의 기존 이미지·예시 값으로 UI만 체험, 네트워크/저장 없음
|   |       `-- WornPreviewReviewScreen.kt        # 서비스 공용 확인 화면에 로컬 이미지·예시 상태/설정만 제공
|   |-- main/res/drawable/ic_remove_garment.xml # 카드 우상단 의상 삭제 아이콘
|   |-- main/res/drawable-nodpi/                # 생성한 온보딩 환영·6종 무드 사진
|   |-- test/java/com/swpp/stylemate/             # 인증·만료·계정 캐시·추천 요청·재시도 검사
|   |   |-- profile/
|   |   |   |-- AuthViewModelTest.kt              # 세션 복원·가입/로그인·제한시간·중복 차단·만료·로그아웃 검사
|   |   |   |-- GuestImportViewModelTest.kt       # 동의·충돌 결정·부분 실패·중복 저장 방지·재시도·취소 검사
|   |   |   `-- ProfileViewModelTestSupport.kt    # 제어 가능한 Main·메모리 세션·HTTP 응답 게이트·가져오기 대역
|   |   `-- wardrobe/
|   |       |-- WardrobeViewModelTest.kt          # 목록·저장 명세의 캐시/오류/재시도·빈 목록·중복 요청 차단 검사
|   |       |-- GarmentAnalysisViewModelTest.kt   # 분석·초안 복원·재시도·사진 변경과 화면 종료 시 취소 검사
|   |       |-- WornBatchViewModelTest.kt         # 후보 선택·순차 생성·부분 실패·비용 중복 방지·편집/저장·초기화 검사
|   |       |-- WornGenerationViewModelTest.kt    # 생성 조회/재생성·이미지 승인/거절·작업 취소·임시 사진 수명 검사
|   |       `-- WardrobeModelTestSupport.kt       # 가상 시간·ViewModel 정리·사진/생성 요청 대역
|   `-- androidTest/java/com/swpp/stylemate/      # 로그인 차단·로그아웃/만료·계정 온보딩·옷장·측정점 검사
|       |-- wardrobe/WornRegistrationTest.kt      # 자동 후보 체크·슬라이더 없음·일괄 저장 조건·원본 크롭·기존 진입 검사
|       `-- wardrobe/WornPreviewTest.kt           # 계정/서버 없이 선택·실패 이유·등록 모의 동작·확대 원본 비교 검사
|-- backend/
|   |-- config/
|   |   |-- settings.py                          # 공통 API·사용자 모델·MySQL·사진·모델 설정
|   |   |-- urls.py                              # 체형·옷장 API 연결
|   |   `-- test_settings.py                     # 외부 서비스 없는 옷장 테스트 환경
|   |-- apps/
|   |   |-- users/
|   |   |   |-- models.py                        # 사용자·체형·취향·토큰 해시 저장
|   |   |   |-- auth.py                          # 토큰 발급·만료 확인·DB 및 인증 접근 제한
|   |   |   |-- pagination.py                    # 계정 목록 위치 고정 커서·계정/종류 검증·개수/바이트 제한
|   |   |   |-- limits.py                        # DB 공유 카운터·5회 실패 잠금·IP 요청 제한
|   |   |   |-- imports.py                       # 인증 가져오기·버전 충돌·사진·코디 연결 보존·분류 변경 제한
|   |   |   |-- test_imports.py                  # 재전송·충돌·계정 격리·연결·사진 실패 정리 검사
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
|   |   |   |-- views.py                         # 인증된 계정 코디 분할 조회·옷 UUID 검증·추천/피드백 API
|   |   |   |-- migrations/0002_outfit_user.py    # 코디 소유 사용자 외래 키 추가
|   |   |   `-- migrations/0003_alter_outfit_user.py # 무소유 코디 정리·소유자 필수 전환
|   |   `-- wardrobe/
|   |       |-- urls.py                          # 옷장 API 경로
|   |       |-- views.py                         # 분석·저장·조회·의상/관련 코디 원자적 삭제·라벨
|   |       |-- media.py                         # 삭제 커밋 후 전체 계정의 파일 참조 확인·미사용 사진 정리
|   |       |-- analysis.py                      # 이미지 검증·OpenRouter 원본 분석·공통 비전 요청/응답·오류 정규화
|   |       |-- worn.py                          # 고정 ONNX 의류 분할·OpenRouter Gemini 펼침 생성·오류 정규화
|   |       |-- worn_views.py                    # 계정별 생성 상태·요청 멱등성·이미지 승인·비교 사진 접근
|   |       |-- worn_detection.py                # OpenRouter 원본 후보·치마/바지 겹침 식별·한 사람/최대 세 벌 검증·원본 크롭
|   |       |-- worn_estimates.py                # 원본 착용 관찰·본인 체형·기존 여유량 규칙으로 참고 치수/범위 계산
|   |       |-- worn_photo_views.py              # 소유 원본/후보 접근·멱등 초안 준비·승인한 여러 옷 원자적 저장
|   |       |-- management/commands/prepare_worn_model.py # 고정 모델 다운로드·SHA-256 검증
|   |       |-- landmarks.py                     # HRNet 추론·좌표 복원·치수별 경로 계산
|   |       |-- schema.py                        # 속성·선택지·details 검증·Gemini 복잡도 제한용 응답 형식 축소
|   |       |-- editor.py                        # 치수 정의·측정값·저장 요청(추천 라벨 포함) 검증
|   |       |-- models.py                        # 소유 사용자·옷 정보 저장·코디에 사용 중인 분류 변경 검증
|   |       |-- migrations/0001_initial.py        # 옷장 테이블 최초 생성
|   |       |-- migrations/0002_garment_labels.py # 추천 라벨 컬럼 추가
|   |       |-- migrations/0003_garment_user.py   # 옷 소유 사용자 외래 키 추가
|   |       |-- migrations/0004_alter_garment_user.py # 무소유 옷 정리·소유자 필수 전환
|   |       |-- test_delete.py                   # 의상/코디 삭제·권한·커밋/롤백·공유 사진 보존·저장소 오류 검사
|   |       |-- tests.py                         # AI 계약·DB 저장·재조회 검사
|   |       |-- test_worn.py                     # 착용 사진 대상 분석·계정 초안 격리·NULL 치수·수동 라벨 저장 검사
|   |       |-- test_generation.py               # 원본 분석 보존·생성 승인·거절·중복/실패/계정 격리 검사
|   |       |-- test_worn_batch.py               # 겹친 옷 후보 계약·여러 사람 거절·일괄 저장 원자성/멱등성·사진 격리 검사
|   |       |-- test_worn_estimates.py           # 체형/원본 단서 부족·추정/실측 격리·자동 라벨·계정 격리·변경 시 무효화 검사
|   |       |-- migrations/0008_garment_estimated_dimensions.py # 실측 컬럼 보존·추정 치수 전용 컬럼 추가
|   |       |-- migrations/0007_worngeneration_candidate_id_wornphoto_and_more.py # 원본 후보 모델·후보별 생성 초안 고유 제약
|   |       |-- migrations/0006_worngeneration_garment_request_key_and_more.py # 생성 초안·출처·요청 ID 고유 제약
|   |       |-- migrations/0005_garment_image_source.py # 기존 사진 보존·착용 사진 출처 구분
|   |       `-- test_landmarks.py                # 원본 사진의 자동 측정점·좌표 변환 검사
|   |-- recommendation/                          # 저장 라벨 → 모델 입력, 계절·색·모델 어휘 정규화
|   |   `-- external.py                          # 단일 옷의 상대 속성 예측·외부 검색 URL 생성
|   |-- body_analysis/                           # MediaPipe 체형 분석
|   |-- wardrobe/garment-landmarks/              # HRNet 가중치·메타데이터·라이선스
|   |-- models/segformer_clothes.onnx            # 고정 SegFormer 의류 분할 가중치, Git 제외·이미지 빌드 시 포함
|   |-- scripts/prepare_landmarks.py             # 빌드 시 HRNet 다운로드·검증·ONNX 변환
|   `-- tests/                                   # 체형 분석·추천 모델 입력 계약·공통 API 회귀 검사
`-- docs/
    |-- login/viewmodel-testing.md              # 로그인·가져오기 ViewModel의 기대 동작·실행·커버리지
    `-- wardrobe/                               # 옷장 설계·실행 안내
        `-- wardrobe-viewmodel-spec.md          # 옷장 ViewModel별 테스트 명세·취소 회귀·변경 전후 커버리지
```

촬영·저장 흐름은 다음과 같다.

`로그인 → 옷장 → AR 촬영 → 자동 점 확인·수정 → cm 계산 → Gemini 분석 → 사용자 편집 → 계정 서버 저장`

- Android: 체형·옷장은 같은 서버 주소와 공통 `StyleMateTheme`·화면 컴포넌트를 사용한다.
- 측정: HRNet은 원본 사진의 2D 점을 제안하고, Android ARCore가 바닥 평면을 기준으로 cm를 계산한다.
- 분석: 상의·아우터는 앞면 사진, 하의는 측정 사진을 보낸다. Gemini는 이름·분류·색상을 생성하고 세부 종류·스타일을 제안한다. 응답은 `id`·`model`·`attributes`와 추천용 `details`(무늬·넥라인·옷깃·디테일·겉으로 분명한 소재, 저장하지 않음)다.
- 추천 라벨: 저장 전 `POST labels/`가 치수와 체형으로 핏·기장·소매·계절 등을 계산하고, 사용자가 확인·수정한 값을 `labels`로 함께 저장한다. 설계는 [추천 문서](../recommendation/README.md)를 따른다.
- 저장: Android는 인증된 계정 API와 계정별 캐시를 사용한다. 옷·코디·사진은 MySQL·서버 사진 저장소에 보관한다.
- 착용 사진: 본인이 입은 사진이라는 전제로 최대 세 벌을 제안한다. 상의·아우터는 각 한 벌이며 하의는 보통 한 벌이지만, 바지 위에 치마를 겹쳐 입었고 각각 구분 가능하면 치마·바지를 별도 후보로 제안한다. 원본에서 보이는 핏·기장을 자동 제안하고 서버 계정 체형으로 가능한 단면·총장을 대략 추정한다. 실측 `dimensions`는 NULL이고 추정값·범위·근거·규칙 버전은 `estimated_dimensions`에 따로 보관한다. 부족한 값은 NULL이며 수동 핏/기장을 강제하지 않는다. 후보 선택을 유지한 채 생성하고 마지막 등록이 이미지 승인·일괄 저장을 겸한다. 부가 수정·확대 비교와 명시적 원본 크롭 대체를 제공한다. 여러 사람·액세서리·같은 종류의 중복 후보는 지원하지 않는다.
- DB 의존성: 체형·측정점·라벨·날씨를 포함한 모든 서비스 API에 계정 DB 설정과 Bearer 인증이 필요하다.

가입·로그인·로그아웃·프로필 API와 계정별 옷·코디 접근 제한, 로그인 필수 화면·만료·계정 전환이 구현되었다. 게스트 사용·미리보기 API는 제거했고, 기존 데이터 가져오기는 인증 후 기능으로 유지한다. 소유자 외래 키는 필수이며 전환 마이그레이션은 소유자 없는 기존 행만 정리한다. 8페이지 온보딩은 기존 체형 분석·옷 등록을 재사용하고 로컬 진행·편집 초안을 복원한다. S3·추천 치수 비교는 미구현이다. 저장 범위와 인증 계약은 [로그인 문서](../login/README.md)를 따른다.

화면 기준은 [디자인](design.md), 데이터·API 규칙은 [명세](wardrobe-spec.md), 등록 과정은 [데이터 흐름](wardrobe-flow.md), 실행·배포는 [실행 안내](local-development.md)를 따른다.

의상 삭제는 기존 계정 API와 저장소를 사용한다. `WardrobeDeleteTest.kt`는 서버 실패·재시도·중복 차단·계정 캐시 제거를, `WardrobeScreenTest.kt`는 카드 아이콘·확인창·취소·실패 화면을 검사한다. `HomeRoute.kt`·`HomeViewModel.kt`는 삭제 후 홈 추천·코디북의 이전 상태를 비우고 갱신한다.
