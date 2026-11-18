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

## 2026-11-18 대화 수정 화면 개선 검증

기존 `81-uiux-improvements` 작업 폴더에서 대화 UI와 실패 요청의 초안 복구를 수정했다. 브랜치·worktree 생성, 인증·서버 API·추천 계산 변경, DB 마이그레이션, 커밋·푸시는 수행하지 않았다. 조건 선택은 별도 시트, 전송은 하나의 버튼, 현재 코디 사진은 한 곳으로 정리했다. 변경 전후 이름·변경 배지와 저장 상태를 구분하고 평가 조회 실패가 코디 수정 실패로 보이지 않도록 했다. 상세 동작은 [추천 문서](../recommendation/README.md#채팅-편집)를 따른다.

- `assembleDebug testDebugUnitTest lintDebug` 통과. JVM 전체 328개 통과, lint 오류 0개·경고 37개·힌트 1개.
- JVM에 실제 변경 전후·변경 없음·아우터 제외, 조건만 보낼 때 빈 입력문 유지, 실패 요청의 문장·조건 복구와 다른 대화 세션의 복구 차단 등 7개 검사를 추가했다. 복구 시 현재 잠금·코디를 유지하고 자동 전송하지 않는지 검사한다.
- 기존 `AuthViewModelTest.refreshSession_server401ExpiresTheDisplayedSession`의 간헐 실패가 재발했다. 인증 상태가 발행된 뒤 IO 스레드에서 저장소를 비우는 순서를 확인하고 테스트의 메모리 저장소에 완료 신호를 추가했다. 저장소 비우기를 기다린 뒤 기존 null 검사를 수행하도록 테스트만 보완했다. 인증 구현·계약은 변경하지 않았다.
- 관련 UI 23개를 분할 실행해 통과를 확인했다: `ChatScreenTest` 9개, `HomeScreenTest` 11개, `GlobalThemeTest` 2개, `UxPhaseOneTest.groupedChatChoicesReplaceToggleAndCombineWithoutSending` 1개. 조건 교체·해제·동시 선택, 선택만으로 전송하지 않음, 하나의 전송 버튼, 단일 코디 미리보기, 짧은 변경 답변, 평가 오류·재시도 분리, 실패 요청 복구 전 초안 교체 확인, 저장·평가 대상, 최신 대화 버튼의 독립 공간을 검사했다. 초기 신규 UI 검사 3건은 가상 목록의 화면 밖 항목 선택자·입력문과 조건의 합쳐진 텍스트 선택자를 수정한 뒤 통과했다.
- 에뮬레이터를 840×1680px·420dpi의 실제 320dp 너비로 설정하여 채팅 UI 9개를 실행했다. 별도로 높이 360dp에서 사진 요약·사진 시트·전송 접근을 검사했다. 일반 키보드가 실제로 열린 상태의 최종 화면, 실기기·큰 글자·가로 화면은 이번 채팅 작업에서 미검증이다.
- Django `apps.outfits.test_chat apps.outfits.test_chat_feedback`는 `config.test_settings`의 격리 DB에서 39개 통과했다. 외부 모델 호출은 대체 수단을 사용했다. 실제 앱에서는 자연어 없이 조건만 보내는 경로로 코디 교체를 확인했으며 유료 분석·생성은 실행하지 않았다.
- 실제 앱의 320dp·411dp 화면에서 조건 선택 → 닫기 → 선택 조건 유지 → 단일 전송 → 하의 교체를 확인했다. `검정 스커트 → 데님 청바지` 답변과 해당 옷의 변경 배지, 상단의 단일 미리보기·저장 전 상태, 평가 조회 실패의 별도 재시도가 표시됐다. 스크린샷은 `/private/tmp/stylemate-chat-final-conditions.png`, `/private/tmp/stylemate-chat-final-result-small.png`, `/private/tmp/stylemate-chat-final-result.png`에 보관하며 Git에 포함하지 않았다. 에뮬레이터 화면 크기는 원래대로 복구했다.
- 남은 환경 문제: 기존 로컬 서버의 평가 상태 API는 오류를 반환한다. 화면에는 평가 조회 실패와 전용 재시도만 표시하며 수정 결과를 유지한다. API 원인 해결·로컬 DB의 미적용 마이그레이션 적용은 이번 UI 작업에 포함하지 않았다.
- 최종 검사 후 GitHub Desktop의 브랜치 왕복으로 변경분이 자동 stash에 이동했다. 원래 브랜치에서 동일한 15개 파일을 복원했으며 stash는 삭제하지 않았다. 복구용 패치는 `/private/tmp/stylemate-chat-recovery.patch`에 보관했다.

### 조건 선택·평가 조회 오류 후속 수정

- 수정 방향이 모호한 `다른 조합`과 해당 묶음을 조건 시트에서 제거했다. 서버의 방향 코드 계약과 기존 대화 표시 호환성은 유지한다.
- 로컬 MySQL을 읽기 확인한 결과 `outfits_feedbackrecord`·`outfits_feedbacktaste`가 없고 `outfits.0004_feedbacktaste_feedbackrecord` 하나만 미적용 상태였다. 기존 마이그레이션을 해당 로컬 개발 DB에 적용해 두 테이블을 추가했다. 기존 계정·옷·코디·코디 항목의 행 수가 유지되는지 확인했으며 운영 DB와 인증·추천 계산·서버 API 코드는 변경하지 않았다.
- `assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest` 통과. JVM 전체 328개, 채팅 UI·조건 선택 관련 10개 통과, lint 오류 0개·경고 37개·힌트 1개. 격리 DB의 채팅·평가 API 39개도 통과했다.
- 실제 앱에서 조건 시트에 밝기·보온·격식·핏만 표시되는 것을 확인했다. `밝게`는 변경 없음으로 표시됐고, 이후 `시원하게` 조건만 전송해 아우터가 교체됐다. 해당 결과에서 평가 조회 오류 문구가 사라지고 좋아요·별로예요가 미선택·활성 상태로 나타났다. 계정의 평가·취향은 직접 변경하지 않았다. 유료 분석·생성은 실행하지 않았다.
- 후속 화면은 `/private/tmp/stylemate-chat-conditions-updated.png`, `/private/tmp/stylemate-chat-feedback-fixed.png`에 보관했다. 앞서 기록한 로컬 평가 조회 오류는 이번 마이그레이션 적용으로 해결됐다. 실제 키보드·실기기 검증 범위는 앞 기록과 같다.

## 2026-11-18 홈·대화 버튼 위계 및 후속 답변 개선

- 홈의 읽기 전용 추천 별점은 서버 원점수의 순서를 유지하는 3.5–5개·반별 표시로 변경했다. 점수 없는 응답에는 별점을 만들지 않는다. 별점은 코디 사진 아래로 옮기고 이유 제목과 분리했다. 추천 이유에는 옅은 회색 받침을 추가했다. 새로 추천을 테두리 버튼으로 바꾸고 페이지 숫자는 제거하되 점 인디케이터·접근성 순서 설명은 유지했다.
- 가상 피팅·대화 수정은 같은 크기의 검정 주 버튼으로 나란히 표시한다. 저장·좋아요·별로예요는 같은 크기의 작은 테두리 보조 버튼으로 묶었고 홈·대화에서 공용 컴포넌트를 사용한다. 채팅의 큰 상단 저장 버튼과 평가 제목은 제거했다. 조작 영역은 48dp 이상 유지한다.
- 옷 카드의 유지 선택·자물쇠는 고정 체크박스로 바꿨다. 이전 코디·각 답변의 돌아가기 대신 사진과 선택 상태를 보여 주는 수정 기록 시트에서 코디를 고르거나 마지막 수정을 취소한다. 넉넉하게에 기존 서버의 fitted 조건인 딱 맞게를 추가하고 같은 묶음의 교체·해제를 유지했다.
- 질문이 반복되면 최근 6개 메시지 밖으로 최초 요청이 사라지는 코드를 확인했다. 미해결 추가 질문이 3회를 넘으면 최초 요청과 최근 두 차례 대화를 보존한다. 완료된 수정 이후에는 해당 보존 규칙을 적용하지 않는다. 모델의 문맥 안내도 짧은 답변·실제 등록 옷·확인된 소매 정보에 맞춰 보완했다. 인증·계정 격리·API 계약·추천 점수 계산은 유지했다.
- `assembleDebug assembleDebugAndroidTest testDebugUnitTest`와 `lintDebug connectedDebugAndroidTest` 통과. JVM 331개, UI 24개(홈 11·대화 10·조건 선택 1·공통 테마 2), lint 오류 0개·경고 37개·힌트 1개. UI 검사는 320×540dp에서 주요 버튼 노출·행 정렬·저장/평가 대상, 숫자 제거, 선택·해제, 고정 체크, 사진 기록 선택·취소, 실패 복구·짧은 뷰포트를 포함한다.
- 격리 DB의 Django 채팅·평가 API 41개, pytest의 문장 해석·채팅 평가 도구 60개 통과. 소매 라벨이 없는 옷을 긴팔로 단정하지 않는 입력 전달과 동일한 옷만 일치할 때 변경 없음 응답을 검사했다.
- 합성 옷 목록으로 실제 OpenRouter 문장 해석 1건을 실행했다. `I want long sleeves → 종류 질문 → just long sleeve → 종류 질문 → anything`에서 추가 질문 없이 `action=edit`와 등록된 긴팔 상의 ID를 반환했다. 계정 저장·사진 전송·이미지 생성은 실행하지 않았다. 모든 표현·오타에 대한 모델 응답 품질을 검증한 것은 아니다.
- 실제 앱의 홈에서 별점·버튼 위계·이유 받침을 확인했고, 대화에서 고정 체크 → 따뜻하게 조건 전송 → 같은 상의와 함께 아우터 추가 → 수정 기록의 처음 코디 사진 선택 → 처음 코디 복원을 확인했다. 실제 320dp에서도 홈의 두 주 버튼·세 보조 버튼이 가려지지 않았다. 로컬 서버는 새 코드로 다시 실행했고 에뮬레이터 크기는 원래대로 복구했다. 기존 날씨 조회 실패 시 18°C 대체 안내는 유지됐다.
- 화면 기록: `/private/tmp/stylemate-home-hierarchy.png`, `/private/tmp/stylemate-home-hierarchy-small.png`, `/private/tmp/stylemate-chat-aligned.png`, `/private/tmp/stylemate-chat-fit-options.png`, `/private/tmp/stylemate-chat-history.png`. 실제 키보드·실기기·큰 글자·가로 화면은 이번 후속 작업에서 미검증이다. 새 커밋·푸시는 수행하지 않았다.


### 사용자 검토용 후속 수정 — 테스트 보류

- 사용자 요청에 따라 단위·API·UI 테스트와 린트는 실행하지 않았다. 이번 코드에는 앞선 테스트 통과 결과를 적용하지 않는다. `assembleDebug`만 통과했고 Python 수정 파일은 구문 분석만 확인했다. 수정 APK는 기존 앱 데이터 유지 설치로 에뮬레이터에 반영했다.
- 홈·대화 헤더의 우측 행동 버튼을 제거했다. 홈의 새로 추천은 이유 아래로, 대화의 저장·수정 기록은 사진 아래로 옮겼다. 대화의 체크박스·처음 코디·저장 전·마지막 수정 취소를 제거했다. 계속 입을 옷은 자연어 버튼으로 선택하고 평가는 답변 아래 작은 아이콘으로 표시한다. 조건 진입은 입력창 안 필터 아이콘으로 통합했다.
- 없는 아우터 추가 시 기존 상·하의를 유지하는 처리를 모든 옷 변경 불가로 잘못 판단하는 분기를 수정했다. 명시적인 추가 종류를 내부 필수 종류 조건으로 전달하고, 필수 아우터가 두 벌짜리 대체 코디로 사라지지 않도록 했다. 아우터가 없으면 해제할 옷이 있다는 안내 대신 해당 상황을 설명한다. 실제로 계속 입기로 선택한 옷을 바꿀 때만 확인을 제공한다.
- 전체 선택 상태에서도 추가 요청을 보낼 수 있게 클라이언트의 선제 차단을 제거했다. 인증·계정 격리·공개 API 계약·추천 점수식은 유지한다. 후속 확인에서는 기존 UI 선택자·모든 옷 선택 시 전송 차단 기대값을 갱신하고, 아우터 추가·없음·기존 옷 유지·실제 선택 충돌의 회귀 검사를 수행해야 한다. 사용자가 먼저 화면을 확인하며 이번 수정의 아우터 추가 동작은 직접 재현 검증하지 않았다.

## 옷장 상세·추천 화면 — 사용자 확인용

- 옷 카드 선택 시 읽기용 상세로 진입하며 정보 수정은 기존 편집기로 연결한다. 상세에는 사진·분류/색상/스타일 요약·옷 특징 표·치수/추정 치수·메모를 표시한다. 추정값의 출처·범위·실측 아님 안내와 생성 사진 주의사항은 유지한다.
- 추천은 별도 화면에서 실제 내 옷 사진을 표시하고 해당 옷 상세로 연결한다. 외부 검색은 명시적인 무신사 버튼으로 분리했다. 목록·상세·추천의 페이지별 복귀 위치를 보존하고, 저장·삭제 후 이전 추천은 다시 조회한다. 목록 사진 위 삭제 아이콘은 제거하고 상세 하단의 사진·이름이 있는 확인창으로 옮겼다.
- `assembleDebug` 통과 및 기존 데이터 유지 설치 완료. 사용자 요청에 따라 자동 단위/API/UI 테스트·린트는 실행하지 않았다. 기존 테스트의 메뉴·삭제 진입·문구 선택자는 새 흐름에 맞춘 후속 갱신이 필요하다. 인증·API 계약·추천 계산·서버 코드는 이번 옷장 작업에서 변경하지 않았다.
- 실제 앱에서 옷 카드 → 상세, 편집 진입 → 변경 없이 상세 복귀, 추천 → 다른 옷 상세 → 추천 복귀, 추정 치수·메모 표시, 사진·이름이 있는 삭제 확인 → 취소를 확인했다. 실제 삭제·정보 저장·외부 브라우저 열기·계정 전환·키보드 편집·오류 복구는 이번 수동 확인에서 실행하지 않았다.
- 에뮬레이터를 320dp로 바꿨을 때 상세와 스크롤 상태가 유지되고 특징 표·추정 치수 문구가 표시되는 것을 확인했다. 원래 크기로 복구했다. 큰 글자·가로 화면·실기기는 미검증이다. 새 커밋·푸시는 수행하지 않았다.
- 화면은 `/private/tmp/stylemate-garment-detail-new.png`, `/private/tmp/stylemate-garment-match-new.png`, `/private/tmp/stylemate-garment-detail-footer.png`, `/private/tmp/stylemate-garment-detail-small.png`에 보관하며 Git에 포함하지 않았다.

## 2026-11-18 저장 표시·대화 버튼 배치 보완

- 홈의 저장 완료 표시는 체크에서 채운 별로 변경했다. 대화도 공용 저장 버튼을 사용하며 코디 사진 아래 오른쪽에 배치한다. 수정 기록은 왼쪽에 둔다. 저장 중·완료 시 중복 실행을 막고 실패 시 재저장할 수 있다. 비활성 저장 글자 `#5D5D5D`와 받침 `#F5F5F5`의 대비는 6.04:1이다.
- `그대로 입기`는 최소 32dp 높이의 작은 칩으로 표시하고 실제 조작 영역은 최소 48dp를 유지한다. 320dp·200% 글자에서 문구가 여러 줄로 쪼개지는 것을 캡처로 확인해 글자 배율에 맞춘 카드 너비·가로 스크롤로 보완했다. 같은 설정에서 평가 문구가 아이콘을 화면 밖으로 밀어내던 문제도 줄바꿈으로 보완했다.
- `assembleDebug assembleDebugAndroidTest lintDebug` 통과. lint 오류 0개·경고 37개·힌트 1개. 전체 JVM 331개 통과. 기존 전체 잠금 테스트의 전송 차단 기대값을 현재 아우터 추가 요청 허용 동작에 맞췄으며 기능 구현은 변경하지 않았다.
- 홈 회귀 UI 11개와 `OutfitActionLayoutTest` 5개 통과. 홈 저장·해제, 대화의 현재 코디 저장 대상·사진 아래 정렬, 잠금 선택·해제·48dp 영역, 저장 중·완료·요청 중 비활성 및 실패 후 재시도를 검사했다. 320×620dp·글자 200%에서 가로 스크롤로 마지막 옷 선택·평가 버튼 너비와 화면 내 배치, 320×360dp에서 사진 요약·저장·전송 접근을 확인했다. 마지막 큰 글자 보완 후 신규 UI 5개를 재실행해 통과했다.
- 가상 데이터와 사진 대체 영역으로 홈 저장·대화·큰 글자·짧은 화면을 확인했다. 실제 사용자 사진·토큰은 사용하지 않았다.
- 실기기·실제 키보드·실제 서버 저장 요청은 이번 UI 검사에서 미검증이다. 앞선 백엔드 후속 변경의 실제 아우터 추가·잠금 충돌 API 재현은 별도 검증이 필요하다. 기존 백엔드·다른 UX 변경을 보존했으며 브랜치 전환·병합·커밋·푸시는 수행하지 않았다.

## 2026-11-18 develop 병합

- `81-uiux-improvements`에서 `origin/develop`의 `2c66f82`를 병합했다. 병합 커밋은 `f4d0668`이며 푸시는 수행하지 않았다. 병합 전 추적 파일 변경은 stash와 `/private/tmp/stylemate-before-develop-20261118-192109` 백업으로 보관한 뒤 복원했으며, 기존 미커밋 UI 작업은 미스테이징 상태로 유지했다. 새 소스와 화면 파일은 삭제하지 않았다.
- 사용자 선택에 따라 develop의 서버 별점 보정과 반별 표시 범위를 사용한다. 앞선 기록의 클라이언트 3.5–5개 재보정은 제거했다. 새 옷장 분석 진입점은 헤더 우측 대신 카테고리 필터 아래 한 줄 테두리 버튼으로 배치했다.
- develop의 지역별 날씨·캘린더 연동·체형 미입력 추천·날짜/장소 대화·옷장 분석·착용 사진 카메라 진입을 유지하고, 기존 홈·대화·읽기용 옷 상세 UI를 복원했다. 대화의 저장 버튼은 develop의 저장/해제를 연결하며 처리 중 중복 요청은 막는다. 저장 완료 후 비활성을 기대하던 테스트는 새 동작에 맞췄다.
- `assembleDebug compileDebugUnitTestKotlin compileDebugAndroidTestKotlin` 통과. Python 수정 파일 4개의 구문 분석과 충돌 표시·공백 검사도 통과했다. 사용자 요청에 따라 단위/API/UI 테스트와 린트 실행은 보류했다. 테스트 코드 컴파일 성공을 테스트 통과로 간주하지 않는다.
- 에뮬레이터에 기존 데이터 유지 설치하고 로컬 개발 서버를 병합 코드로 재실행했다. 실제 화면의 접근성 정보에서 홈 추천·별점·기존 행동 버튼과 옷장 카테고리 필터 아래 분석 버튼을 확인했다. 병합 후 옷 상세 왕복·분석 실행·저장/해제·계정 전환·캘린더·날씨 변경의 전체 동작은 미검증이다. 유료 분석·생성 및 데이터 삭제는 실행하지 않았다.


## 2026-11-18 옷 상세·수정 화면과 옷장 필터 정리

- 수정·삭제를 헤더의 더보기 메뉴 안에 같은 높이·글자 크기의 관리 항목으로 묶었다. 상세의 주요 행동은 하단 `함께 입을 옷 찾기`, 수정의 주요 행동은 하단 `저장하기`다. 삭제 확인·오류·진행 중 보호는 기존 경로를 유지한다.
- 상세·수정은 `GarmentLayout.kt`의 상단바·사진·출처 안내·정보 구획을 공유한다. 사진 → 기본 정보 → 옷 특징 → 측정/추정 치수 → 메모 순서를 맞췄다. 대표 사진 크기는 너비 기준으로 같으며 큰 글자에서 정보가 줄바꿈되고 전체 내용을 스크롤할 수 있다. 출처는 `AI 이미지`로 짧게 표시하고 원본 분석·부정확성 안내는 눌러서 읽는다.
- 내 옷 추천 목록을 먼저 표시하고, 구분선 아래 별도 영역에 외부 추천 설명과 무신사 연결 버튼을 함께 배치했다. 추천 계산·인증·데이터 모델은 변경하지 않았다. API 저장 payload·초안/버리기·유효성 검사·추정값 유효성 로직은 작업 전 보관한 파일과 대조해 보존했다.
- 추정 치수의 대표값과 예상 범위가 두 치수처럼 읽히던 문제를 범위 하나로 정리했다. 범위가 잘못됐거나 없으면 유효한 대표값만 `약 N cm`로 표시한다. 유효하지 않은 숫자는 숨기며 서버 응답을 수정하거나 추정값을 실측 저장값으로 복사하지 않는다. 기존 서버는 대표값을 범위의 중간값으로 계산하므로 사용자가 본 수치 불일치가 실제 저장 데이터에도 있는지는 이번 작업으로 단정하지 않는다.
- 옷장 제목은 탭과 같은 `옷장`이다. 신발은 데이터·수동 등록/보관에 남아 있지만 착용 사진 후보·코디 추천에는 포함되지 않는다. 빈 신발 필터는 숨기고 기존 신발이 있으면 표시한다. 마지막 신발이 없어지면 전체 선택으로 복귀하고 신발이 다시 생겨도 선택이 되돌아가지 않는다.
- `assembleDebug assembleDebugAndroidTest testDebugUnitTest` 통과. 실행 시점 전체 JVM 367개 통과. 새 치수 표시 검사 2개는 범위와 대표값이 다른 응답, 범위 누락/역전·동일 끝값·잘못된 숫자·입력 JSON 불변을 검사한다.
- UI 13개 통과: `GarmentDetailFlowTest` 6개·기존 `GarmentEditorTest` 3개·`GarmentLabelFlowTest` 2개·탭 초안/저장/버리기 회귀 1개·추정 치수 읽기 전용 검사 1개. 메뉴 위계·선택 대상, 상세/수정 사진 크기·구획 순서, 실제 편집 payload, 내 옷/외부 링크 분리, 짧은 출처 안내, 320×620dp·글자 200%·긴 이름, 빈/기존 신발·필터 복귀를 확인했다. 신발 필터 검사는 카드에도 같은 분류 문구가 있고 목록 헤더가 가상화되는 점을 고려해 선택자를 보완한 뒤 재실행해 통과했다.
- Django `apps.wardrobe.test_worn_estimates apps.wardrobe.test_delete --settings=config.test_settings` 30개 통과. SQLite 메모리 DB·대체 외부 호출을 사용했으며 기존 계정·DB·사진은 삭제하거나 초기화하지 않았다.
- 전체 린트는 기존 `HomeRoute.kt`의 `remember(account.userId) { model.setRegion(...) }`가 Unit을 반환하는 `RememberReturnType` 오류 1건으로 실패했다. HEAD에도 있던 지역 초기화 코드임을 확인했고 다른 창의 홈·코디북 작업과 겹쳐 이 작업에서 수정하지 않았다. 최초 린트 내부 오류 이후 앱·테스트 컴파일을 마치고 별도 실행한 결과다. 린트 전체 통과로 보지 않는다.
- 가상 데이터와 의류 견본으로 상세·수정·추천·치수·큰 글자·옷장을 확인했다. 실기기·실제 서버 저장/삭제·유료 분석/생성은 이번 UI 검사에서 미검증이다.
- `81-uiux-improvements`에서 기존 미커밋 변경을 보존했다. 새 브랜치·worktree·병합·커밋·푸시는 수행하지 않았다. 다른 창에서 홈·코디북·피팅 상태 작업이 진행 중이며 공통 문서의 기존 내용도 유지했다.

## 2026-11-18 코디북 슬라이드·목록 개선

- 홈의 코디 사진 배치를 `OutfitBoard`로 분리해 코디북에서도 사용한다. 기본 슬라이드와 목록을 제목 아래 단일 선택 버튼으로 전환한다. 목록 카드 선택은 해당 코디의 슬라이드로 연결하며 별도 상세·대화 수정 진입은 추가하지 않았다. 목록은 넓은 사진·이름 중심으로 표시하고 반복되는 큰 피팅 버튼·날짜·즉시 저장 해제 별 버튼은 제거했다.
- 코디 ID와 인접 위치를 기준으로 보기 전환·정렬 변경·저장 해제 후 선택을 관리한다. 계정별 탭 저장 상태를 재사용하며 탭 왕복 시 보기·선택·목록 위치를 유지한다. 내용이 짧아 특정 항목을 목록 맨 위로 스크롤할 수 없어도 기존 슬라이드 선택을 덮어쓰지 않는다.
- 저장 해제는 코디 사진·이름이 있는 확인창에서 실행한다. 취소는 요청을 보내지 않는다. 해제 중 중복 요청·확인창 닫기·재조회 경합을 막고, 실패는 해당 확인창의 재시도로 처리한다. 기존 실패 테스트 기대값을 분리된 오류에 맞추고 중복 해제·해제 중 조회 차단 테스트를 추가했으나 실행은 보류했다.
- 계정별 기존 피팅 작업 메타데이터와 결과 파일 존재를 읽어 생성 중·결과 있음·확인 필요 상태와 피팅 진입 문구를 구분한다. 목록의 상태 문구와 슬라이드의 행동 문구를 구분하며, 피팅 생성·알림 권한 요청·새 서버 API는 추가하지 않았다. 인증·계정 격리·추천 계산·API 계약은 유지한다.
- `assembleDebug` 및 `compileDebugUnitTestKotlin compileDebugAndroidTestKotlin` 통과. 자동 단위/API/UI 테스트·린트는 사용자 요청대로 실행하지 않았다. 테스트 코드 컴파일 성공은 테스트 통과를 뜻하지 않는다.
- 에뮬레이터에서 기본 슬라이드 → 두 번째 코디로 넘기기 → 목록 → 슬라이드의 동일 코디 보존, 목록 → 홈 탭 → 코디북의 보기 보존, 목록 카드 → 해당 슬라이드, 저장 해제 확인 → 취소 후 2개 유지, 피팅 생성 전 진입 → 닫기 후 동일 코디 유지를 직접 확인했다. 실제 저장 해제·실패 재시도는 사용자 데이터 변경 없이 확인할 수 없어 실행하지 않았다.
- 320×520dp에서 목록 사진·줄바꿈과 슬라이드 세로 스크롤 후 피팅·저장 해제 버튼 접근을 확인하고 에뮬레이터 크기를 원래대로 복구했다. 생성 중·완료·실패 피팅 상태의 화면 재현, 계정 전환, 큰 글자·가로 화면·실기기는 미검증이다. 유료 생성은 실행하지 않았다.
- 화면은 `/private/tmp/stylemate-codibook-slides.png`, `/private/tmp/stylemate-codibook-list.png`, `/private/tmp/stylemate-codibook-small-list.png`, `/private/tmp/stylemate-codibook-small-slides.png`에 보관하며 Git에 포함하지 않는다. 기존 작업은 보존했고 브랜치 전환·커밋·푸시는 수행하지 않았다.

## 2026-11-19 MY 요약·편집 분리

- 네 번째 탭과 메인 제목을 `MY`로 변경했다. 내 체형 → 핏·무드 요약 → 컬러 기록 → 캘린더 연결 요약 → 계정 관리 순서로 배치하고 메인의 칩·스위치·즉시 저장을 없앴다. 계정과 조건부 기존 데이터 가져오기는 하단에 유지한다. 옷 편집의 체형 분석 안내도 MY 명칭으로 맞췄다.
- 핏·무드·컬러는 별도 편집 화면에서 하단 저장 버튼으로 적용한다. 변경 후 뒤로 가면 계속 수정/버리기를 확인하며 오류 시 선택을 유지한다. 기존 계정 ViewModelStore에 초안·요청을 두어 탭 이동으로 저장을 취소하지 않도록 했다. 계정 폐기 시 함께 정리되며 서버 필드·인증·계정 캐시 계약은 유지한다.
- 대표 핏 변경은 기존 선호 목록의 첫 값 순서만 바꾸고 나머지를 보존한다. 무드의 다중 선택·전체 해제, 컬러의 8개 제한·전체 해제는 유지한다. 컬러 기록의 현재 추천 미반영과 대표 핏의 실제 적용 범위를 편집 위치에 표시했다. 추천 계산과 새 추천 기능은 추가하지 않았다.
- 캘린더는 별도 설정 화면에서만 스위치를 조작할 수 있으며 변경·OS 권한 복귀 후 MY 요약을 갱신한다. 체형 상세는 공통 뒤로 가기, 기본 정보·치수·재분석·보조 삭제 순서로 정리했다. 삭제 확인·오류 처리와 스타일 유지 안내를 보존하고 치수 직접 수정은 추가하지 않았다.
- `assembleDebug compileDebugUnitTestKotlin compileDebugAndroidTestKotlin` 통과 및 공백/충돌 표시 검사 통과. 대표 핏 보존·저장 실패와 중복 차단·컬러 초기화의 JVM 회귀 검사 3개를 추가하고 기존 UI 선택자·저장 기대값을 갱신했다. 사용자 요청에 따라 단위/API/UI 테스트 및 린트 실행은 보류했다. 컴파일 성공을 테스트 통과로 간주하지 않는다.
- 별도로 실행한 에뮬레이터에만 기존 데이터 유지 설치했다. 실제 화면에서 MY 요약에 편집 컨트롤이 없는 상태, 무드·컬러 선택 후 홈 탭 왕복의 초안 유지, 뒤로 가기 확인/계속 수정/버리기, 버린 후 기존 요약 유지, 대표 핏 선택 표시·적용 안내를 확인했다. 에뮬레이터의 서버 포트 연결 전 저장을 시도해 연결 실패 오류와 선택 유지·재저장 접근을 확인한 후 버렸다. 이 요청으로 서버 프로필은 변경하지 않았고 이후 에뮬레이터의 로컬 API 포트 연결만 복구했다.
- 캘린더 설정 진입/복귀와 읽기용 체형 상세의 기본 정보·뒤로 가기를 확인했다. 320×620dp·글자 200%에서 요약의 체형 표시와 컬러 편집 진입·저장 버튼 접근을 확인하고 크기·글자 설정을 복구했다. 연결된 실기기의 앱·설정·입력은 변경하지 않았다.
- 실제 서버 저장 성공·저장 중 탭 이동 후 완료·계정 전환/로그아웃의 새 초안 폐기·체형 삭제·캘린더 권한 거부는 이번 화면 확인에서 미검증이다. 기존 데이터 가져오기의 서버 실행·유료 분석/생성은 실행하지 않았다.
- 캡처는 `/private/tmp/stylemate-my-overview.png`, `/private/tmp/stylemate-my-mood-draft.png`, `/private/tmp/stylemate-my-save-error.png`, `/private/tmp/stylemate-my-fit-editor.png`, `/private/tmp/stylemate-my-color-editor.png`, `/private/tmp/stylemate-my-calendar.png`, `/private/tmp/stylemate-my-body-info.png`, `/private/tmp/stylemate-my-small-overview.png`, `/private/tmp/stylemate-my-small-colors.png`에 보관하며 Git에 포함하지 않는다. 기존 사용자 변경을 보존했고 브랜치 전환·커밋·푸시는 수행하지 않았다.
- 후속 통합 빌드에서 동시 작업 중인 체형 입력 화면의 누락 import와 아직 생성되지 않은 아이콘 참조로 일시 실패했다. 이후 추가된 아이콘 파일을 유지하고 `PhotoInputScreen.kt`에는 `OutlinedButton`·`PaddingValues`·`heightIn` import만 보완했다. 온보딩의 이전 탭 명칭 안내도 `취향은 MY에서, 등록한 옷은 옷장에서`로 맞췄다.
- 보완 후 `assembleDebug compileDebugUnitTestKotlin compileDebugAndroidTestKotlin` 재통과했으며 통합 APK를 에뮬레이터에 기존 데이터 유지 설치했다. 자동 테스트·린트는 실행하지 않았다.


## 2026-11-19 온보딩·옷 등록·대화·코디북 위계 보완

- 기존 확정 체형·취향 또는 초기 조회한 옷이 있는 계정은 반복 온보딩을 건너뛴다. 신규 기본 레귤러만으로 완료 처리하지 않고 진행 중 취향·체형 입력·옷 편집 초안을 보존한다. 새 계정이 일부 정보를 저장한 뒤 앱을 다시 열어도 진행 중 옷 편집을 폐기하지 않는다. 끝점 없는 진행 바, 이모지 대신 의류 벡터, 동일 기준선의 전신 사진 제목·힌트, 테두리 촬영 예시 버튼과 간결한 완료 요약을 적용했다. 촬영 예시는 가상의 성인을 생성한 정면·측면 이미지이며 체형 분석 엔진·환경 복구는 재개하지 않았다.
- 옷장 하단 분석:등록 버튼은 4:6이며 등록 방식 카드는 회색 받침·테두리로 구분한다. 사진 등록은 0명/1명을 허용하고 2명 이상은 거절한다. 0명 사진의 착용 관찰값은 버려 본인 체형 추정에 사용하지 않는다. 인식·생성·저장 진행은 단일 차단 다이얼로그로 표시하며 카드·하단의 중복 스피너는 제거했다.
- 대화의 저장 별·수정 기록은 헤더, 열린/닫힌 자물쇠는 사진 위에 둔다. 입력 중 사진은 포커스를 유지한 채 상단에서만 펼치고 접는다. 송신/수신 색상과 엄지 선택의 진하기를 구분한다. 코디북 보기 전환은 아이콘, 저장 해제는 별·기존 확인창, 페이지 표시는 이전·번호/전체·다음이다. 홈 별점은 사진 우상단, 이유는 분류/문장/구분선으로 정돈했다.
- 상세 색상도 같은 정보 행 정렬·구분선을 사용한다. 사진 출처는 사진 위 배지이며 설명 열기를 유지한다. 기본 정보·계절·추천 라벨은 공용 선택 시트로 통일했다. 단일 라디오 선택의 즉시 닫기, 다중 체크박스의 최대 개수·완료, 표시명/저장 코드·초안·편집 payload를 보존했다.
- `assembleDebug assembleDebugAndroidTest` 성공. `testDebugUnitTest` 실행 시점 전체 JVM 374개 통과(실패·오류·건너뜀 0). 확정 체형이 있어도 진행 중 옷 초안을 복원하는 회귀 검사를 포함한다. Django `apps.wardrobe.test_worn_batch --settings=config.test_settings --noinput` 11개 통과. 격리 DB와 대체 AI 응답을 사용했으며 실제 유료 사진 분석·생성은 실행하지 않았다.
- 서로 다른 관련 UI 검사 25개 통과: `UxHierarchyTest` 7개, `OnboardingTest` 2개, `OutfitActionLayoutTest` 5개, `GarmentDetailFlowTest` 6개, `GarmentEditorTest` 3개, `GarmentLabelFlowTest` 2개. 250개 코디북의 다음 이동·목록 왕복·별 해제 취소/확정, 단일 스피너, 선택 제한, 실제 키보드가 열린 대화의 헤더·포커스·단일 사진·잠금·접기, 가이드 복귀, 사진 안 별점, 저장 코드·편집 payload를 확인했다. 320×620dp·글자 200%·긴 이름 검사를 포함한다.
- 초기 신규 검사에서 가짜 등록 데이터의 필수 labels 누락과 높이를 고정한 테스트 창에 실제 키보드 여백이 겹치는 실패를 확인했다. 필수 데이터를 보완하고 키보드 검사는 실제 MainActivity와 동일한 edge-to-edge·adjustResize·전체 높이로 맞춘 뒤 재실행해 통과했다. 모달은 Activity 뒤 화면 대신 실제 창 전체를 캡처한다.
- `lintDebug`는 기존 `HomeRoute.kt:89`의 Unit 반환 `remember` 때문에 `RememberReturnType` 오류 1건으로 실패했다(경고 39개·힌트 1개). 다른 창의 지역 초기화 동작과 겹쳐 해당 코드는 수정하지 않았다. 전체 린트 통과로 보지 않는다. 문서 링크·신규 구조 경로·`git diff --check`는 통과했다.
- 사용자 승인 후 기존 로컬 Django 서버만 같은 `127.0.0.1:8000 --noreload`로 재시작해 옷 등록 변경을 적용했고 `/healthz/` 200을 확인했다. 체형 분석 복구·모델 실행은 수행하지 않았다. 연결된 SM-S918N에 APK를 `-r -t`로 설치하고 USB 8000 포트 연결을 유지해 앱을 열었다. 계정·DB·사진·앱 데이터 삭제와 초기화는 수행하지 않았다.
- 합성 코디와 의류 견본으로 옷장·등록·로딩·촬영 예시·대화·코디북·홈·상세·수정을 확인했다. 실제 사용자 사진·접근 토큰은 사용하지 않았다.
- 실기기에서 각 화면의 전체 조작·실제 AI의 옷만 있는 사진 판정·생성 성공·체형 분석·TalkBack 음성 청취는 미검증이다. 실기기 설치와 서버 상태 확인을 실제 분석 성공으로 간주하지 않는다.
- 기존 `81-uiux-improvements`와 다른 창의 변경을 보존했다. 겹칠 수 있는 파일은 `HomeScreen.kt`, `CodiBookScreen.kt`, `PhotoInputScreen.kt`, `BodyProfileViewModel.kt` 및 공통 설계·검증 문서다. 현재 파일과 시작 시점 사본을 대조했고 인증·추천 점수·계정별 저장·기존 편집 초안을 유지했다. 브랜치·worktree 생성/전환·병합·커밋·푸시는 수행하지 않았다.


## 2026-11-19 공통 헤더·MY·사진/저장 표시 보완

- 탭과 하위 화면의 제목을 공용 `PageHeader`로 맞췄다. 홈 별점은 사진·인디케이터 아래 별도 행으로 옮겼다. MY는 구분선 목록, 계정 설정 안의 아이디·로그아웃 확인, 메인 캘린더 스위치로 정리했다. 핏·무드는 한 편집 화면에서 같은 형태로 선택하며 함께 저장한다. 컬러는 기존 HEX·8개 제한을 유지한 29색 팔레트다.
- 내 체형 정보는 키·몸무게 요약과 온보딩 예시 모델 위 실제 저장 치수 표시로 바꿨다. 모델은 사용자 체형 재현이 아님을 표시한다. 대화는 윤곽선/채운 별·저장 라벨, 사진 아래 저장/기록, 카드 10dp 여백·동일 높이 변경 표시를 적용했다. 좁은 화면의 큰 글자에서는 사진 접기로 시작하고 펼친 사진 영역의 높이를 제한한다. AI 이미지 배지의 시각적 여백은 줄이고 48dp 조작 영역을 유지했다.
- 옷만 있는 사진을 허용하는 현재 백엔드 코드를 확인하고 `--noreload` 로컬 서버를 최신 코드로 재시작했다. `/healthz/` 정상 응답을 확인했다. 과거 `IMAGE_NO_PERSON` 오류는 사람을 요구하는 문장 대신 사진 재선택과 옷만 있는 사진 지원을 안내한다. 사진 재선택은 새 요청 ID를 사용하며 기존 실패 요청을 자동 재분석하지 않는다. 실제 AI의 사진 판정·유료 생성은 실행하지 않았다.
- 최종 `assembleDebug compileDebugUnitTestKotlin compileDebugAndroidTestKotlin` 성공. 다른 창과의 빌드 출력 충돌을 피하려고 `/private/tmp/stylemate-review-build`만 사용했다. 초기 공유 출력 충돌과 작은 화면 보완 중 Kotlin 수신자 오류를 수정한 뒤 최종 성공을 확인했다. 사용자의 화면 우선 확인 요청에 따라 자동 단위/API/UI 테스트·린트 실행은 보류했다. 테스트 소스 컴파일을 테스트 통과로 간주하지 않는다.
- 에뮬레이터 실제 화면에서 홈 사진/별점 비겹침, MY 통합 취향 진입·선택·버리기 확인·취소 후 기존 요약 유지, 컬러 그룹, 계정 설정·로그아웃 확인 후 취소, 예시 모델의 저장 치수·옷 상세 배지를 확인했다. 대화에서 미저장 윤곽선 별 → 저장됨 → 저장 해제의 실제 서버 왕복을 확인하고 원래 미저장 상태로 복구했다. 메시지 전송·유료 분석·생성은 실행하지 않았다.
- 320×620dp·글자 200%에서 대화 카드가 본문을 가리는 현상을 재현하고 보완했다. 보완 후 사진이 접힌 초기 상태의 본문·저장·입력창과 사진 펼치기/접기 접근을 확인했다. 펼친 사진은 제한된 영역에서 스크롤한다. 화면 크기와 글자 설정은 원래대로 복구했다.
- 연결된 SM-S918N에는 계정·앱 데이터를 유지하는 덮어 설치를 수행했다. USB 8000 포트 연결을 유지하고 홈 데이터·별점 분리와 MY 목록 표시를 확인했다. 실기기 전체 조작, 실제 옷만 있는 사진의 AI 인식 성공, 체형 분석 실행, 캘린더 권한 거부, 새 통합 취향의 실제 서버 저장·계정 전환은 이번 수동 검증에서 확인하지 않았다.
- 화면 캡처는 `/private/tmp/stylemate-review-*.png`에만 두었으며 Git에 추가하지 않았다. 시작 시 기존 변경 86개 파일의 사본을 `/private/tmp/stylemate-review-20261119-before.tar.gz`에 보관했다. 기존 변경·브랜치를 유지했고 새 브랜치/worktree·커밋·푸시는 만들지 않았다.


## 2026-11-19 하위 제목·대화 헤더 복원

- `ScreenComponents.kt`의 공통 `PageHeader`에서 탭 제목 26sp/700·34sp 줄 높이와 뒤로 가기가 있는 하위 제목 20sp/600·28sp 줄 높이를 구분했다. 하위 헤더의 상하 여백은 16→8dp이며 제목·뒤로 가기·오른쪽 행동을 세로 중앙에 맞춘다. 뒤로 가기의 48dp 조작 영역은 유지하고 시작 여백을 20→8dp로 줄였다. 옷 상세·수정·추천·등록, MY 하위 페이지, 회원가입·체형 입력·가이드·가상 피팅과 대화의 공통 헤더에 적용한다.
- 대화의 수정 기록과 저장을 헤더 우측의 시계/되돌림 화살표·빈 별/채운 별로 복원했다. 사진 아래의 반복 행동 행은 제거했다. 현재 코디 대상·저장/해제·실패 재시도·저장 중 중복 차단·버전 복원은 유지한다.
- 옷 카드의 고정 20dp 변경 배지 행을 없애고 사진 왼쪽 아래에 배지를 겹쳐 놓았다. 내부 여백은 좌우 8dp·상하 6dp, 사진/이름 간격은 6dp다. 사진 88dp·두 줄 이름·48dp 자물쇠와 다른 창에서 추가한 큰 글자/작은 높이의 접기·사진 영역 스크롤·키보드 포커스 보존을 유지했다.
- `assembleDebug assembleDebugAndroidTest` 성공. 에뮬레이터에서 관련 UI 12개 통과: `OutfitActionLayoutTest` 5개, `GarmentDetailFlowTest` 6개, `UxHierarchyTest.photoPreviewStaysInlineAndKeepsTheMessageFocused` 1개. 320dp·200% 글자·긴 이름·짧은 화면, 헤더 저장 대상/해제·중복 차단/재시도, 자물쇠, 상세/수정 배치·편집 payload, 실제 키보드의 포커스 유지와 단일 사진을 확인했다. 추가 JVM/API 테스트·린트는 표시 배치만 바뀐 이번 후속 수정에서 재실행하지 않았다.
- 합성 데이터로 대화·큰 글자·짧은 화면·키보드·탭 제목·상세·수정을 확인했다. 실제 사용자 사진·토큰은 사용하지 않았다.
- 연결된 SM-S918N에 `-r -t`로 기존 데이터 유지 설치했다. 실기기 전체 조작·가로 화면·TalkBack 음성 청취는 미검증이다. 서버·인증·추천 계산·편집 초안·계정 데이터는 변경하지 않았다.
- 다른 창에서 같은 대화 파일의 큰 글자·사진 스크롤 보완을 추가하는 것을 확인해 공통 헤더부터 수정한 뒤 최신 코드를 기준으로 표시 부분만 적용했다. 겹칠 수 있는 파일은 `ScreenComponents.kt`, `ChatScreen.kt`, `ChatComponents.kt`, `OutfitActionLayoutTest.kt` 및 디자인/검증 문서다. 기존 브랜치를 유지했으며 브랜치·worktree 생성/전환·커밋·푸시는 수행하지 않았다.


## 2026-11-19 PR #82 최종 커밋 검증

- 기존 `81-uiux-improvements`에서 기능·서버 수정·테스트·문서를 영어 메시지의 개별 커밋으로 구분했다. 새 브랜치·worktree·병합·기존 커밋 재작성은 수행하지 않았다. 홈 검사 기대값 보완은 독립 테스트 커밋에 포함했다.
- 최종 Android 앱·검사 APK 빌드, JVM 375개, Django 사용자·옷장·코디 241개, 추천 모듈 pytest 195개 통과. API 검사는 SQLite 메모리 DB와 대체 외부 응답을 사용했으며 실제 사용자 DB·사진을 초기화하지 않았다.
- 관련 UI는 서로 다른 56개 통과. 최초 통합 실행은 53개 통과·3개 실패였으며, MY 계정 정보의 위치·날씨 문구 분리·서버 별점 기준을 반영한 검사와 빠진 가짜 날씨/추천 응답을 보완한 뒤 해당 3개를 재실행해 통과했다. 대화·코디북·등록/편집·온보딩·MY·계정/탭별 초안과 큰 글자·짧은 화면을 포함한다.
- 홈 지역 복원을 ViewModel 생성 시 초기화하도록 옮겨 첫 날씨 조회 전에 적용되는 순서를 유지했다. 기존 `RememberReturnType` 오류는 해결됐고 최종 `lintDebug`는 오류 0개·경고 39개·힌트 1개다. 과거 절의 린트 실패 기록은 당시 결과로 남긴다.
- 사용자 요청으로 문서용 스크린샷 파일과 이미지 링크를 모두 제거했다. 앱에서 사용하는 촬영 가이드 리소스는 기능 자산으로 유지한다. 공통 디자인·구조·검증 설명과 문서 링크·공백 검사는 유지한다.
- 체형 분석 엔진·환경 복구, 실제 AI 사진 판정·유료 생성·실기기 AR 및 전체 TalkBack 동작은 이번 커밋 검증에서 수행하지 않았다. CI는 푸시한 최종 커밋 기준으로 별도 확인한다.

## 2026-11-19 PR #82 피팅 CI 선택자 수정

- 제공된 CI 로그에서 앱·검사 APK 빌드는 성공했으나 피팅 검사 39개 중 3개가 제거된 `닫기` 텍스트를 찾다가 실패했다. 현재 피팅 화면의 종료 행동은 공통 헤더의 `뒤로` 화살표다.
- `PhotoFittingTest`의 종료 선택자 3곳을 `뒤로` 접근성 설명으로 바꾸고 화면 표시 후 누르도록 했다. 알림 ON/OFF·권한 허용/거부·다른 코디 및 재진입 후 설정 유지 검사는 유지했으며 앱 동작이나 CI의 검사 범위는 변경하지 않았다.
- 로컬 API 37 에뮬레이터에서 CI와 같은 `-PandroidTestCoverage` 설정으로 앱·검사 APK를 빌드하고, `PhotoFittingTest,FittingWorkerTest,FittingFlowTest`의 기기 검사 39개를 실행해 실패·오류·건너뜀 0개를 확인했다. CI에서 실패했던 3개 각각의 통과도 XML 결과로 확인했다.
- `createDebugAndroidTestCoverageReport createAggregatedCoverageReport` 성공. 통합 보고서 생성 과정의 JVM 검사 375개도 실패·오류·건너뜀 없이 통과했으며 기기·통합 HTML 보고서가 생성되었다. 빌드 출력·프로젝트 캐시는 `/private/tmp/stylemate-fitting-ci-*`로 분리했고, 테스트를 위해 허용한 에뮬레이터의 알림 권한은 실행 전 거부 상태로 복구했다.
- 합성 이미지·가짜 계정·대체 서버 응답을 사용했다. 실제 AI·유료 생성·서버 DB 변경은 수행하지 않았으며, GitHub CI의 API 35/x86_64 재실행과 실기기는 이번 확인 범위에 포함하지 않았다.
