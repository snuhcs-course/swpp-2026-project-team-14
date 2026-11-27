# StyleMate 기능 구조

옷장 기능은 팀의 `frontend/`·`backend/`에서 실행한다. Android는 공통 앱, Django는 `manage.py`와 `config.wsgi`를 사용한다.

## 옷장봉 보기 (2026-11-25, #105)

`WardrobeCollection`은 기존 계정 목록과 콜백을 유지하면서 검색, 카테고리 요약, 전체 Grid와 옷장봉 보기를 연결한다. `WardrobeCarousel`은 고정된 봉과 개별 옷걸이를 Canvas로 그리고, 선택 위치 주변 최대 9개 의류에 비균등 간격·연속 회전·깊이·스프링 스냅을 적용한다. `GarmentHangingGeometry`는 투명 이미지의 윗부분 윤곽에서 어깨·목선·허리의 상대 위치를 한 번 추정하고 이미지와 함께 캐시한다. 긴 소매와 치마 밑단이 옷걸이 폭에 영향을 주지 않도록 상의 위쪽 10–18%, 하의 위쪽 2.5–8%를 사용한다. 비대칭 사진은 옷의 위치를 보정해 고리가 목선 중심에 온다. 상의는 목선 안의 우드 옷걸이, 아우터는 굵은 어깨 옷걸이, 하의는 허리 양끝의 앞쪽 집게와 뒤쪽 바를 사용한다. 이는 실루엣 기반 추정으로 기울어진 사진이나 잘못 분할된 윤곽은 오차가 남을 수 있다.

의류와 옷걸이는 고리를 기준으로 같은 변환을 받는다. 흰 배경의 전체 폭 옷장봉은 중앙이 양끝보다 화면 폭의 3%(8–14dp) 낮은 고정된 U자 포물선이다. 고리의 세로 위치는 봉과 같은 곡선에서 계산한다. 중앙은 정면, 첫 번째 측면은 약 37도, 가장자리는 최대 52도로 회전한다. 중앙에서 멀어질수록 간격을 좁히며 크기는 중심의 100%에서 가장자리의 90%까지 연속적으로 변한다. 회전과 이동은 연속적으로 계산해 중간에 꺾이는 구간을 만들지 않는다. 스와이프 중 고리가 곡선을 따라 움직여 봉과 분리되지 않는다. 비착용 소품은 선반 아래쪽에 정렬한다. 연속 위치는 구성 단계에서 읽지 않고 그래픽 레이어에서 읽으며, 겹침 순서는 중앙 항목 변경 시 갱신한다. 투명도는 ModulateAlpha로 처리해 의류마다 별도 합성 버퍼를 만들지 않는다. 중심 이동은 손가락 거리와 맞추며 거의 임계 감쇠인 스프링과 고리 기준 ±1도 이내의 작은 흔들림을 사용한다. 배경 제거와 이미지 캐시, 실루엣에 맞춘 옷걸이 배치는 유지한다.

`WardrobeCutout`은 인증된 `GET /api/wardrobe/items/<id>/display-cutout/`에서 최대 448px PNG를 한 번 요청한다. 서버는 명확한 고대비 스튜디오 사진에만 빠른 배경 제거를 적용하고 흰 옷에는 기존 CPU SegFormer를 사용한다. 단일 의류 사진은 의류 라벨을 합쳐 상의 일부가 원피스로 오분류되어도 몸통이 지워지지 않게 한다. 착용 사진 크롭은 선택 카테고리를 유지한다. 생성형 AI, 외부 전송, DB 쓰기, 원본 덮어쓰기는 없다. 서버 모델은 기존 `WORN_SEGMENTATION_MODEL` 설정을 사용한다. Android는 32MB, 서버는 24MB 메모리 캐시로 파생 이미지를 제한한다. 진행 중인 요청은 공유하여 화면 밖으로 나간 뒤 돌아와도 중복 다운로드하지 않는다. 서버는 소유권을 캐시 조회보다 먼저 검사하고 원본 내용 해시와 처리 버전으로 변경을 구분한다. 캐시 조회는 다른 이미지의 추론 잠금에 막히지 않는다.

분할이 불확실하거나 모델이 없는 환경에서는 안내와 재시도를 제공하고 원본은 기존 상세 화면에서 볼 수 있다. 겹침·누락된 부분을 새로 생성하지 않는다. 캐러셀의 60FPS는 실기기 성능 측정이 남아 있다.

```text
swpp-2026-project-team-14/
|-- frontend/app/src/
|   |-- main/java/com/swpp/stylemate/
|   |   |-- MainActivity.kt                       # 공통 앱 진입·밝은 테마 시스템 바 아이콘 적용
|   |   |-- data/
|   |   |   |-- BodyMeasurements.kt              # 체형 치수·선호 정보 모델
|   |   |   |-- LocalUserStore.kt                # 계정별 프로필·옷장 캐시·추천 취향·온보딩 초안·피팅 결과 파일 확인
|   |   |   |-- BodyProfileStore.kt              # 체형·독립 스타일·온보딩 상태 저장 연결
|   |   |   |-- AuthRepository.kt                # 인증·프로필·세션·만료·응답 제한·계정 목록 분할 조회
|   |   |   |-- GuestImportRepository.kt         # 가져오기 후보·충돌 조회·지문·계정별 재시도 처리
|   |   |   |-- AuthSessionStore.kt              # Keystore AES-GCM 암호화 세션 보관·백업 제외
|   |   |   |-- recommendation/                 # 계정 코디·외부 검색 API·계정별 취향 저장
|   |   |   |-- tryon/FittingRepository.kt      # 인증 피팅·계정별 결과 저장/존재 확인·알림 설정
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
|   |       |-- StyleMateApp.kt                   # 로그인 필수 진입·계정별 탭/편집 상태 보존·수동 가져오기·만료 시 상태 폐기
|   |       |-- components/ScreenComponents.kt   # 탭/하위 제목 위계·뒤로가기 정렬·상단바 교체 슬롯·하단 버튼·섹션 제목
|   |       |-- components/StarRating.kt         # 서버 보정 점수를 0.5–5개·반별 단위로 표시하는 읽기 전용 추천 별점
|   |       |-- components/OutfitFeedbackButtons.kt # 홈·대화 공용 저장·엄지 평가 보조 버튼·선택/해제 표시
|   |       |-- components/OutfitBoard.kt         # 홈·코디북 공용 코디 사진 배치·아우터 옆 상/하의 구성
|   |       |-- theme/Theme.kt                    # 앱 전체 흑백 팔레트·글자 위계·모서리·시스템 바 스타일
|   |       |-- home/
|   |       |   |-- HomeScreen.kt                # 압축 코디판·좌우 비교·행동/피드백 버튼·별점/추천 문장·재시도
|   |       |   |-- HomeRoute.kt                 # 선택 코디의 채팅/피팅 연결·계정별 코디북 피팅 상태·의상 삭제 후 갱신
|   |       |   |-- CodiBookScreen.kt            # 슬라이드/목록·코디별 복귀 위치·피팅 상태·저장 해제 확인/재시도
|   |       |   `-- HomeViewModel.kt             # 홈 추천/저장/평가·코디북 조회/중복 해제 방지·오류 분리·삭제 시 무효화
|   |       |-- chat/
|   |       |   |-- ChatScreen.kt                # 단일 코디 미리보기·조건 요약/전송·대화·실패 요청 복구
|   |       |   |-- ChatComponents.kt            # 조건·사진 기록 시트·작은 그대로 입기 칩·코디 하단 저장·분리된 평가
|   |       |   |-- ChatPresentation.kt          # 실제 변경 전후 이름·추가/제외 표시·코디 버전 목록
|   |       |   `-- ChatViewModel.kt             # 미해결 요청 문맥·코디별 대화·잠금·재시도·실패 초안 복구·저장/평가
|   |       |-- profile/
|   |       |   |-- AuthScreens.kt               # 로그인 진입 화면·회원가입·비밀번호 표시·오류 안내
|   |       |   |-- AuthViewModel.kt             # 중복 제출 방지·시도 제한 대기·포그라운드 검증·만료 안내
|   |       |   |-- GuestImportViewModel.kt      # 계정별 가져오기 대상 확인·수동 가져오기·충돌 선택·재시도
|   |       |   |-- GuestImportDialog.kt         # 가져오기 확인·항목별 교체 선택·결과 팝업
|   |       |   |-- BodyProfileViewModel.kt      # 체형 분석·온보딩 초안·핏/무드 서버 저장·완료·계정 연결
|   |       |   |-- OnboardingScreen.kt          # 공통 상단바·8페이지·등록 개수 현황·선택적 분석·계정 옷 등록 반복·완료
|   |       |   |-- PhotoInputScreen.kt          # 사진 박스 선택·교체·가이드 왕복·키보드 대응·기본 정보 입력
|   |       |   |-- ReviewScreen.kt              # 체형 결과·치수 수정·온보딩 확인과 재분석 저장
|   |       |   |-- MyProfileScreen.kt           # MY 목록·읽기용 체형 상세·통합 취향·계정 설정/로그아웃 확인·조건부 가져오기
|   |       |   |-- ProfilePreferenceEditor.kt   # 통합 핏/무드·29색 팔레트·명시적 저장·변경 버리기 확인·실패 재시도
|   |       |   |-- ProfilePreferencesViewModel.kt # 계정별 취향 초안·핏/무드 동시 저장·대표 핏 순서·탭 이동 중 요청 유지
|   |       |   |-- BodySummaryFigure.kt          # 온보딩 예시 모델에 실제 저장된 분석 치수 위치 표시
|   |       |   `-- ConnectionsSection.kt        # MY 안의 캘린더 스위치·권한 거부 복구·연결 상태 갱신
|   |       `-- wardrobe/
|   |           |-- WardrobeScreen.kt            # 목록/상세/추천 페이지·복귀 상태·등록/편집·삭제 연결
|   |           |-- WardrobeCollection.kt        # 옷장 헤더·검색·필터·카테고리 요약·전체 그리드·고정 행동
|   |           |-- WardrobeCarousel.kt          # 고정 옷장봉·옷걸이·밀집 원근 배치·드래그·스프링 스냅
|   |           |-- WardrobeCutout.kt            # 인증 사진의 보수적 배경 제거·계정별 메모리 캐시
|   |           |-- GarmentHangingGeometry.kt    # 투명 의류 윤곽의 목선·어깨·허리 지지점 추정
|   |           |-- WardrobeStyle.kt             # 공통 팔레트를 참조하는 옷장 배치·모서리 별칭
|   |           |-- WardrobeViewModel.kt         # 계정 목록·저장·삭제/중복 방지·외부 추천·옷장 분석 상태
|   |           |-- WardrobeAnalysisDialog.kt    # 부족한 옷 분석 결과·내 옷 근거·외부 검색·재시도
|   |           |-- ExternalRecommendationDialog.kt # 이전 팝업 구성(앱의 실제 진입 흐름에서는 미사용)
|   |           |-- GarmentLayout.kt             # 상세/수정 공용 상단바·사진/출처 안내·정보 구획·항목 정렬
|   |           |-- GarmentDetailScreen.kt       # 읽기용 옷 상세·수정/삭제 메뉴·하단 추천·선택 옷 삭제 확인
|   |           |-- GarmentMatchScreen.kt        # 내 옷 사진 추천·상세 연결·별도 무신사 검색
|   |           |-- GarmentAnalysisViewModel.kt  # 분석 진행·실패·초안 복원·사진 변경 시 이전 분석 취소
|   |           |-- WardrobeColorField.kt       # 색상표·사진 좌표 변환·픽셀 색상 선택
|   |           |-- GarmentChoiceField.kt        # 기본 속성/라벨 공용 선택 시트·단일/다중 선택·저장 코드 유지
|   |           |-- GarmentEditor.kt             # 특징·실측·메모 편집·초안 복원·버리기 확인·하단 고정 저장
|   |           |-- GarmentEstimates.kt          # 실측과 분리한 단일 추정 범위·대표값 대체·편집 후 무효화
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
|   |-- main/res/drawable/ic_star_outline.xml     # 미저장 코디용 실제 윤곽선 별
|   |-- main/res/drawable-nodpi/body_capture_guide.png # 생성한 성인 정면/측면 촬영 자세 예시
|   |-- main/res/drawable/ic_clothing.xml         # 이모지 대신 쓰는 의류 벡터
|   |-- main/res/drawable/ic_lock_open.xml        # 변경 가능한 옷 표시
|   |-- main/res/drawable/ic_lock_closed.xml      # 계속 입을 옷 표시
|   |-- main/res/drawable/ic_history.xml          # 수정 버전 기록 진입
|   |-- main/res/drawable/ic_view_slides.xml      # 코디북 슬라이드 보기
|   |-- main/res/drawable/ic_view_list.xml        # 코디북 목록 보기
|   |-- main/res/drawable/ic_chat_conditions.xml # 입력창 안 수정 조건 필터 아이콘
|   |-- main/res/drawable/ic_remove_garment.xml # 카드 우상단 의상 삭제 아이콘
|   |-- main/res/values/themes.xml             # 흰 시작 화면·검정 기본 강조·밝은 시스템 바 기본 리소스
|   |-- main/res/values-v27/themes.xml         # API 27 이상에서 어두운 내비게이션 바 아이콘 적용
|   |-- main/res/drawable-nodpi/                # 생성한 온보딩 환영·6종 무드 사진
|   |-- test/java/com/swpp/stylemate/             # 인증·만료·계정 캐시·추천 요청·재시도 검사
|   |   |-- data/ExistingOnboardingTest.kt        # 기존 취향 계정 건너뛰기·신규 기본값·진행 중 초안 유지
|   |   |-- ui/components/StarRatingTest.kt        # develop 별점 범위·반별 경계값·문자열 검사
|   |   |-- chat/ChatPresentationTest.kt          # 변경 없음·실제 교체·아우터 제외 표시 검사
|   |   |-- chat/ChatViewModelTest.kt             # 대화 상태·재시도·잠금·실패 요청의 세션별 복구 검사
|   |   |-- profile/
|   |   |   |-- AuthViewModelTest.kt              # 세션 복원·가입/로그인·제한시간·중복 차단·만료·로그아웃 검사
|   |   |   |-- ProfilePreferencesViewModelTest.kt # 대표 핏 보존·저장 실패/중복 차단·컬러 초기화 회귀 검사
|   |   |   |-- GuestImportViewModelTest.kt       # 동의·충돌 결정·부분 실패·중복 저장 방지·재시도·취소 검사
|   |   |   `-- ProfileViewModelTestSupport.kt    # 제어 가능한 Main·메모리 세션·HTTP 응답 게이트·가져오기 대역
|   |   `-- wardrobe/
|   |       |-- WardrobeViewModelTest.kt          # 목록·저장 명세의 캐시/오류/재시도·빈 목록·중복 요청 차단 검사
|   |       |-- GarmentEstimatePresentationTest.kt # 중복 대표값 생략·잘못된 범위/값의 표시 대체 검사
|   |       |-- GarmentAnalysisViewModelTest.kt   # 분석·초안 복원·재시도·사진 변경과 화면 종료 시 취소 검사
|   |       |-- WornBatchViewModelTest.kt         # 후보 선택·순차 생성·부분 실패·비용 중복 방지·편집/저장·초기화 검사
|   |       |-- WornGenerationViewModelTest.kt    # 생성 조회/재생성·이미지 승인/거절·작업 취소·임시 사진 수명 검사
|   |       `-- WardrobeModelTestSupport.kt       # 가상 시간·ViewModel 정리·사진/생성 요청 대역
|   `-- androidTest/java/com/swpp/stylemate/      # 로그인 차단·로그아웃/만료·계정 온보딩·옷장·측정점 검사
|       |-- UxHierarchyTest.kt                   # 하단 4:6·250개 코디북·입력 포커스·단일 스피너·공용 선택·가이드·별점 배치
|       |-- UxPhaseOneTest.kt                    # 탭/계정별 초안·저장/버리기·사진 취소/가이드·채팅 조건·가져오기 표시 회귀
|       |-- GlobalThemeTest.kt                   # 실제 앱 탭·로그인/가입·편집/체형/채팅/피팅의 이전 테마 색상 검출·화면 촬영
|       |-- home/HomeScreenTest.kt               # 작은 화면의 행동 노출·선택 상태·별점/추천 문장·실패 재시도 검사
|       |-- chat/ChatScreenTest.kt               # 조건/단일 전송·중복 미리보기 제거·복구 확인·오류 범위·작은 화면 검사
|       |-- chat/OutfitActionLayoutTest.kt       # 사진 아래 별 저장 전환·48dp 자물쇠·저장 중/실패·큰 글자 검사
|       |-- wardrobe/GarmentDetailFlowTest.kt     # 관리 메뉴·상세/수정 배치·추정/출처 안내·내 옷/쇼핑 분리·신발 필터
|       |-- wardrobe/WornRegistrationTest.kt      # 자동 후보 체크·슬라이더 없음·일괄 저장 조건·원본 크롭·기존 진입 검사
|       |-- wardrobe/WardrobeRedesignTest.kt       # 동일 샘플 전후 촬영·로딩/오류 구분·필터 접근성·작은 화면/큰 글자 검사
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
|   |   |   |-- chat.py                          # 계정 옷·확인된 소매/핏 전달·고정 조건의 채팅 미리보기
|   |   |   |-- test_chat.py                     # 미해결 질문 문맥·실제 등록 옷 선택·고정·저장 없는 미리보기 검사
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
|   |   |-- search.py                            # 기존 추천 계산·고정 옷/명시적으로 추가한 종류의 결과 포함
|   |   |-- chat_intent.py                       # 문맥을 이용한 짧은 답변·등록 옷 선택의 구조화 해석
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

- Android: 로그인·홈·옷장·코디북·프로필·체형·등록·편집·채팅·가상피팅은 같은 서버 주소와 공통 흑백 `StyleMateTheme`를 사용한다. 옷장 전용 테마는 제거했으며 공통 내비게이션·모달·시스템 바도 같은 팔레트를 사용한다. 사진·선호 색상 값·AR 측정 색과 기능 로직은 유지한다.
- 측정: HRNet은 원본 사진의 2D 점을 제안하고, Android ARCore가 바닥 평면을 기준으로 cm를 계산한다.
- 분석: 상의·아우터는 앞면 사진, 하의는 측정 사진을 보낸다. Gemini는 이름·분류·색상을 생성하고 세부 종류·스타일을 제안한다. 응답은 `id`·`model`·`attributes`와 추천용 `details`(무늬·넥라인·옷깃·디테일·겉으로 분명한 소재, 저장하지 않음)다.
- 추천 라벨: 저장 전 `POST labels/`가 치수와 체형으로 핏·기장·소매·계절 등을 계산하고, 사용자가 확인·수정한 값을 `labels`로 함께 저장한다. 설계는 [추천 문서](../recommendation/README.md)를 따른다.
- 저장: Android는 인증된 계정 API와 계정별 캐시를 사용한다. 옷·코디·사진은 MySQL·서버 사진 저장소에 보관한다.
- 착용 사진: 본인이 입은 사진이라는 전제로 최대 세 벌을 제안한다. 상의·아우터는 각 한 벌이며 하의는 보통 한 벌이지만, 바지 위에 치마를 겹쳐 입었고 각각 구분 가능하면 치마·바지를 별도 후보로 제안한다. 원본에서 보이는 핏·기장을 자동 제안하고 서버 계정 체형으로 가능한 단면·총장을 대략 추정한다. 실측 `dimensions`는 NULL이고 추정값·범위·근거·규칙 버전은 `estimated_dimensions`에 따로 보관한다. 부족한 값은 NULL이며 수동 핏/기장을 강제하지 않는다. 후보 선택을 유지한 채 생성하고 마지막 등록이 이미지 승인·일괄 저장을 겸한다. 부가 수정·확대 비교와 명시적 원본 크롭 대체를 제공한다. 여러 사람·액세서리·같은 종류의 중복 후보는 지원하지 않는다.
- DB 의존성: 체형·측정점·라벨·날씨를 포함한 모든 서비스 API에 계정 DB 설정과 Bearer 인증이 필요하다.

가입·로그인·로그아웃·프로필 API와 계정별 옷·코디 접근 제한, 로그인 필수 화면·만료·계정 전환이 구현되었다. 게스트 사용·미리보기 API는 제거했고, 기존 데이터 가져오기는 인증 후 기능으로 유지한다. 소유자 외래 키는 필수이며 전환 마이그레이션은 소유자 없는 기존 행만 정리한다. 8페이지 온보딩은 기존 체형 분석·옷 등록을 재사용하고 로컬 진행·편집 초안을 복원한다. S3·추천 치수 비교는 미구현이다. 저장 범위와 인증 계약은 [로그인 문서](../login/README.md)를 따른다.

화면 기준은 [디자인](design.md), 데이터·API 규칙은 [명세](wardrobe-spec.md), 등록 과정은 [데이터 흐름](wardrobe-flow.md), 실행·배포는 [실행 안내](local-development.md)를 따른다.

의상 삭제는 기존 계정 API와 저장소를 사용한다. `WardrobeDeleteTest.kt`는 서버 실패·재시도·중복 차단·계정 캐시 제거를, `WardrobeScreenTest.kt`는 카드 아이콘·확인창·취소·실패 화면을 검사한다. `HomeRoute.kt`·`HomeViewModel.kt`는 삭제 후 홈 추천·코디북의 이전 상태를 비우고 갱신한다.
