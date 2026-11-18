# Login-required transition: validation

Branch: `main-merge-1017`. Work was performed in the existing checkout without changing branches.

## Completed checks

| Check | Result |
| --- | --- |
| Django account, wardrobe and outfit suites | 110 passed |
| Backend pytest | 142 passed |
| Android JVM unit tests | 95 passed; 0 failures, errors or skipped tests |
| Android debug APK (`assembleDebug`) | Passed |
| Android instrumentation Kotlin compilation (`compileDebugAndroidTestKotlin`) | Passed |
| Android lint (`lintDebug`) | 0 errors, 0 fatal issues, 32 warnings |
| Django migration drift (`makemigrations --check --dry-run`) | No changes detected |
| Whitespace (`git diff --check`) | Passed |

Django command: `python manage.py test apps.users apps.wardrobe apps.outfits --settings=config.test_settings --noinput`.
Backend command: `python -m pytest`.
Android tasks: `:app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:lintDebug`.

The API tests verify that missing/invalid/revoked tokens are rejected before analysis providers run, guest-only routes are removed, and shared options remain accessible with authentication. Existing authenticated legacy-data import remains available and is included in the passing Django and JVM suites. Client tests cover account separation, authenticated requests, expiry handling and onboarding cache behavior.

APK: `frontend/app/build/outputs/apk/debug/app-debug.apk` (31,722,224 bytes).
Lint report: `frontend/app/build/reports/lint-results-debug.html`.
JVM test report: `frontend/app/build/reports/tests/testDebugUnitTest/index.html`.

## Environment limits

Device/emulator UI tests were not executed. ADB could not initialize its settings directory (`Cannot mkdir \.android: Permission denied`), and no ADB server was listening. A full instrumentation APK assembly attempt also encountered access denied on the existing Gradle cache JAR `androidx.collection:collection-jvm:1.5.0`. Instrumentation Kotlin compilation passed, but this does not establish a passing device test run or a successfully packaged instrumentation APK.

External model/API calls are replaced by test doubles. Actual Gemini responses, production MySQL and Azure deployment were not exercised. No production migrations, account-data deletion, commit or push were performed.

The current API contract and user flow are documented in [README.md](README.md).

## 2026-11-18 UX 1차 개선

사용자 지시에 따라 기존 `main` 작업 폴더에서 구현했다. 브랜치·worktree 생성, 커밋·푸시, 운영 DB 마이그레이션은 수행하지 않았다. 인증·추천 계산·서비스 API는 변경하지 않았다.

| 검사 | 결과 |
| --- | --- |
| Android debug APK·계측 APK | 빌드 통과 |
| Android JVM 단위 테스트 | 최종 전체 318개 통과 |
| Android lint | 오류 0개, 경고 38개, 힌트 1개 |
| Django users·wardrobe·outfits | 격리 DB에서 219개 통과 |
| Backend pytest | 260개 통과, 기존 1개 건너뜀, XML 환경 오류 12개 |
| UI 회귀 | 인증·온보딩·옷장·편집·초안·사진·채팅·피팅 관련 52개를 분할 실행하여 통과 확인 |
| 소형 화면 | 320dp에서 포커스된 체형 입력·오류·분석 버튼, 사진 취소·가이드 왕복, 두 등록 방식 선택의 3개 테스트 통과 |
| 실제 앱 조작 | 로컬 서버·기존 계정에서 옷 이름 편집 → 홈 → 옷장 왕복 유지, 버리기 확인, 촬영 취소 → 필터 변경 확인 |

UI 회귀는 `UxPhaseOneTest`, `OnboardingTest`, `AuthenticationFlowTest`, `PhotoFittingTest`, `AppNavigationTest`, `GarmentEditorTest`, `FrontPhotoTest`, `WardrobeScreenTest`와 `WornRegistrationTest.wardrobeOffersBothRegistrationPaths`를 대상으로 했다. 사진은 합성 이미지를 사용하고 피팅은 저장된 테스트 결과·가짜 권한 응답을 사용했다. 실제 유료 분석·생성은 호출하지 않았다. 기존 결과 재진입 시 알림 권한 거부 상태에서도 이미지가 열리고 벨·권한 요청이 없는 것을 검사했다.

최종 검사 명령은 `assembleDebug testDebugUnitTest lintDebug`와 대상 클래스를 지정한 `connectedDebugAndroidTest`다. 소형 화면 검사는 에뮬레이터를 840×1680px·420dpi로 설정하여 320dp 너비에서 실행했다. 실제 앱 화면에서도 키 입력값과 분석 버튼이 키보드 위에 표시되는 것을 확인했다.

추가 확인 사항과 한계:

- 로컬 Homebrew Python 3.12의 `pyexpat`가 시스템 libexpat에 없는 `_XML_SetAllocTrackerActivationThreshold` 심볼을 요구하여 pytest의 체형 API 12개가 DB 준비 단계에서 실패했다. 애플리케이션 서버 코드·시스템 Python은 이번 작업에서 수정하지 않았다.
- 기존 `AuthViewModelTest.refreshSession_server401ExpiresTheDisplayedSession`가 한 차례 세션 화면 상태 해제와 저장소 정리 사이의 시점에서 실패했다. 단독 및 이후 전체 재실행은 통과했으며, 간헐성은 별도 확인 대상으로 남긴다.
- 320dp에서 길던 키 기준 설명은 의미를 유지한 짧은 문구로 정리했다. 별도 시각 디자인 변경은 하지 않았다.
- 실기기 AR 측정 완료 후 재촬영, 실제 외부 생성 품질·비용, 운영 환경은 미검증이다. 로컬 DB의 미적용 outfits 마이그레이션은 적용하지 않았다.
- 일반 편집 초안은 탭 왕복에 보존하며 앱 강제 종료 후의 복원까지 보장하지 않는다. 기존 온보딩 저장 정책은 유지한다. 다른 사진으로 피팅 재생성하는 N3는 보류했다.

## 2026-11-18 홈 화면 개선 검증

기존 작업 폴더에서 홈 UI·현재 코디 선택만 변경했다. 기존 공통 흑백 테마와 다른 창의 변경을 보존했으며 인증·추천 계산·서버 API는 수정하지 않았다. 상세 동작은 [추천 문서](../recommendation/README.md#android-홈-화면)를 따른다.

- `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` 통과. JVM 단위 테스트 319개 통과, lint 오류 0개·경고 37개·힌트 1개.
- `connectedDebugAndroidTest`의 `com.swpp.stylemate.home.HomeScreenTest` 9개 통과. 실제 측정된 320×540dp 영역에서 3벌 사진·피팅·저장·채팅 버튼 노출, 좌우 넘기기·점 버튼 선택과 행동 대상, 상세 정보 펼침, 주의 문구 유지, 추천 실패 재시도, 불러오기·드래그 중 조작 차단, 빈 상태 이후 새 코디 세트의 첫 선택을 검사했다. 초기 한 건은 인접 페이지의 중복 샘플 이름에 따른 선택자 충돌이었으며 샘플 이름을 구분한 뒤 전체 통과했다.
- `HomeViewModelTest`는 코디 선택 시 추천 요청·취향 값이 유지되는지와 선택한 ID로 저장하는지를 추가 검사한다. 처리 중 선택 차단도 기존 회귀 검사에 포함했다.
- Android 17 에뮬레이터의 실제 앱·로컬 서버·기존 계정에서 2벌·3벌 배치, 코디 넘기기, 코디 저장·해제, 피팅 진입·화면 내 닫기, 채팅 진입·홈 복귀를 확인했다. 저장 테스트 후 기존 미저장 상태로 복원했고 유료 분석·생성은 실행하지 않았다. 날씨 API가 실패한 환경에서 기존 18°C 대체 안내도 표시됐다.
- 추가 관찰: 피팅 창에서 `adb input keyevent 4`를 보냈을 때 앱이 백그라운드로 이동한 경우가 있었다. 화면 내 `닫기`는 정상 복귀했다. 시스템 뒤로 가기의 OS별 동작은 별도 확인 대상으로 남기고 공용 피팅 구현은 이번 홈 배치 작업에서 변경하지 않았다. 실기기·큰 글자·가로 화면은 이번 작업에서 직접 확인하지 않았다.

### 홈 후속 피드백 반영

좋아요·별로예요를 같은 크기의 엄지 버튼으로 맞추고 선택 받침·접근성 선택 상태를 추가했다. 대화 수정도 테두리 버튼으로 표시한다. 코디북 저장은 미저장 `＋`·저장 체크로 구분한다. 코디 정보 토글·옷 목록·출처 보조 문구를 제거하고 추천 문장을 바로 표시하며 첫 문장을 강조한다. 추천 별점은 develop의 기존 표시 규칙을 재사용해 반별 단위·5개 별로 표시한다. 서버 점수 계산은 유지한다.

- `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` 통과. 전체 JVM 321개 통과, lint 오류 0개·경고 37개·힌트 1개.
- `HomeScreenTest` 11개 통과. 기존 코디 선택·드래그·실패 재시도·320×540dp 검증과 함께 저장 상태, 피드백 선택·해제·교체, 추천 문장·별점 상시 표시, 출처·옷 목록 제거, 점수 없음·서비스 주의 문구 유지를 검사했다.
- `StarRatingTest`는 develop의 반별 표시와 상하한·문자열 규칙을 검사한다. 인증·계정 데이터·추천 서버·유료 분석/생성은 변경하지 않았다.
- 실제 계정의 에뮬레이터 화면에서 사진 로딩 후 세 버튼 행, 미저장 `＋`, 5개 별, 강조한 첫 추천 문장과 스크롤 후 나머지 문장을 확인했다. 실기기·큰 글자·가로 화면은 이번 후속 작업에서 직접 확인하지 않았다.
