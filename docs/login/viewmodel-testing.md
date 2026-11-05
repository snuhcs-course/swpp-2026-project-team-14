# 로그인·기존 데이터 가져오기 ViewModel 테스트

## 범위와 실행 방식

기준은 [로그인 필수 서비스 계약](README.md)이다. 기대 결과는 로그인 상태·진행 표시·오류·재시도·가져오기 동의와 충돌 결정에서 도출한다. 테스트에 맞추어 인증 정책이나 기존 저장 데이터를 변경하지 않는다.

- `AuthViewModel`: 실제 `AuthRepository`, 메모리 세션 저장소, MockWebServer를 연결한다. 서버 응답을 제어하며 단위 테스트에서 실제 계정·운영 서버를 사용하지 않는다.
- 인증 만료 시각과 카운트다운은 같은 가상 시계를 사용한다. 세션 감시 작업은 테스트 종료 시 ViewModel과 함께 취소하며 시간을 무한히 진행하지 않는다.
- `GuestImportViewModel`: 스냅샷·충돌 확인·개별 가져오기·계정 갱신 네 작업의 응답을 제어한다. 기존 `GuestImportRepository` 생성자는 같은 작업에 연결되며 화면에서 사용하는 API는 유지한다.
- 실제 Repository의 전송·영수증·사진·계정 격리는 기존 `AuthRepositoryTest` 17개와 `GuestImportRepositoryTest` 9개를 전체 실행에서 함께 확인한다.
- 카메라·Keystore 암호화·기기 화면은 별도 Android 기기 테스트 범위다. 이번 JVM 측정에 기기 커버리지를 합산하지 않는다.

## `AuthViewModel` 명세

전제: 로그인·가입 입력 규칙은 서버 계약과 같으며, 유효한 세션의 일시적인 연결 실패만으로 로그아웃하지 않는다.

| 대상·기대 결과 `[Unit]` | Tested by `AuthViewModelTest` |
| --- | --- |
| 세션이 없으면 네트워크 요청 없이 시작 준비를 마치고 비로그인 상태를 제공한다. | `restore_withoutSessionBecomesReadyWithoutNetworkRequests` |
| 유효한 세션은 복원 후 내 정보 API로 확인하고 갱신된 프로필을 표시한다. | `restore_validSessionVerifiesProfileAndPropagatesUpdatedAccount` |
| 이미 만료된 세션은 저장소에서 지우고 만료 안내를 표시한다. | `restore_expiredSessionClearsStorageAndExplainsExpiry` |
| 손상된 저장 세션은 로그인 상태로 복원하지 않고 복구 안내와 준비 완료 상태를 제공한다. | `restore_corruptSessionBecomesReadyWithRecoveryNotice` |
| 저장소 자체 오류가 있어도 초기 로딩을 끝내고 안내한다. | `restore_storageFailureDoesNotLeaveStartupBlocked` |
| `open`: 로그인·가입 화면을 전환하면 이전 입력 오류를 지운다. | `open_changesPageAndClearsPreviousFormError` |
| `submit`: 로그인 성공 시 세션을 게시하고 입력 화면을 닫는다. | `submit_loginSuccessClosesFormAndPublishesSession` |
| `submit`: 가입은 비밀번호 확인을 전송하고 성공하면 자동 로그인한다. | `submit_registrationUsesConfirmationAndSignsIn` |
| `submit`: 잘못된 자격 증명은 입력 화면과 오류를 유지하고 재시도할 수 있다. | `submit_wrongCredentialsPreservesFormAndAllowsRetry` |
| `submit`: 비밀번호 확인이 다르면 가입 요청을 보내지 않는다. | `submit_confirmationMismatchNeverSendsRegistration` |
| 제출 중에는 중복 제출·화면 전환·로그아웃·검증 요청을 차단한다. | `submit_pendingLoginBlocksDuplicateSubmissionNavigationAndLogout` |
| 429의 대기시간을 표시하고 `2 → 1 → 0` 동안 재제출을 차단한 뒤 허용한다. | `submit_rateLimitCountsDownAndBlocksRequestsUntilZero` |
| `logout`: 서버 토큰 폐기 성공 후에만 세션을 지우고 로그인 흐름으로 돌아간다. | `logout_successRevokesSessionAndReturnsToLoginFlow` |
| `logout`: 실패하면 세션을 유지하고 안내하며 다시 로그아웃할 수 있다. | `logout_failurePreservesSessionAndAllowsRetry` |
| 저장된 만료 시각 직전에는 세션을 유지하고 정확한 경계부터 해제한다. | `session_expiryAtBoundaryClearsSessionWithoutAnotherRequest` |
| `refreshSession`: 서버 401은 화면 세션과 저장 토큰을 해제하고 만료를 안내한다. | `refreshSession_server401ExpiresTheDisplayedSession` |
| 세션 검증 요청을 중복 실행하지 않으며 로그아웃 시 이전 검증을 취소한다. | `refreshSession_pendingVerificationIsNotDuplicatedAndLogoutCancelsIt` |
| `dismissNotice`: 저장소 관리 계층과 화면의 안내를 함께 해제한다. | `dismissNoticeClearsBothRepositoryAndDisplayedNotice` |
| 화면 해제 후 늦게 도착한 로그인 성공 응답을 저장하거나 오류로 표시하지 않는다. | `clearingViewModelDuringLoginDoesNotSaveLateSession` |

## `GuestImportViewModel` 명세

전제: 로그인 후 사용자가 기존 기기 데이터를 수동으로 가져온다. Repository가 스냅샷·충돌·완료 영수증을 관리하고 ViewModel은 동의·진행·결과 상태를 관리한다.

| 대상·기대 결과 `[Unit]` | Tested by `GuestImportViewModelTest` |
| --- | --- |
| 처음에는 숨김 상태이며 데이터 읽기나 가져오기를 자동 시작하지 않는다. | `initialState_staysHiddenWithoutReadingOrImporting` |
| `open`: 데이터가 있으면 이전에 유지한 항목을 포함해 사용자 동의를 기다린다. | `open_nonemptySnapshotRequiresConsentAndIncludesPreviouslyKeptRecords` |
| `open`: 데이터가 없으면 결과 안내만 표시하고 서버 확인·가져오기를 호출하지 않는다. | `open_emptySnapshotShowsResultWithoutPreviewOrImport` |
| `open`: 읽기 실패는 오류와 재시도를 제공하며 다시 읽을 수 있다. | `open_snapshotFailureShowsRecoverableReadError` |
| `close`: 읽는 중 닫으면 취소하고 늦은 결과가 화면을 다시 열지 않는다. 중복 읽기도 차단한다. | `close_pendingReadCancelsItAndIgnoresLateSnapshot` |
| 동의 화면을 닫으면 검토 상태를 버리고 다음 열기에서 최신 스냅샷을 읽는다. | `close_consentDiscardsReviewAndReopenReadsFreshSnapshot` |
| `confirm`: 충돌이 없으면 제공된 순서로 가져오고 가져옴·유지·실패 개수를 구분한다. | `confirm_withoutConflictsImportsInOrderAndCountsKeptRecords` |
| 충돌이 있으면 쓰기 전에 사용자의 항목별 유지·교체 결정을 기다린다. 처리 후 중복 결정은 무시한다. | `confirm_conflictsWaitForExplicitKeepOrReplaceDecision` |
| 충돌 확인 실패 시 원본 스냅샷과 실패 개수를 유지하고 오류를 안내한다. | `confirm_previewFailurePreservesSnapshotForRetry` |
| 스냅샷·동의·충돌 상태가 준비되지 않으면 확인·결정·재시도가 작업을 시작하지 않는다. | `invalidStageActionsDoNotStartImportOrRetry` |
| 충돌 확인 중에는 닫기·추가 읽기·중복 확인을 차단한다. | `pendingPreviewBlocksCloseAndDuplicateConfirmation` |
| 가져오기 중에는 닫기·추가 읽기·중복 확인·결정·재시도를 차단한다. | `pendingImportBlocksCloseAndDuplicateOperations` |
| 의상 가져오기가 실패하면 그 의상을 참조하는 코디를 가져오지 않는다. | `import_failedGarmentPreventsImportOfItsLinkedOutfit` |
| 네트워크·HTTP 5xx·사진 저장소 오류는 남은 전송과 자동 새로고침을 중단한다. 미완료 항목은 실패 수에 포함한다. | `import_networkOrStorageFailureStopsRemainingRecordsAndRefresh` |
| 개별 항목 검증 실패는 관련 없는 다음 항목의 가져오기를 막지 않는다. | `import_recordValidationFailureAllowsUnrelatedRecordsToContinue` |
| 인증 만료·계정 변경은 작업 취소로 전파하고 나머지 항목을 전송하지 않는다. 화면 폐기는 인증 흐름이 담당한다. | `import_expiredOrChangedSessionCancelsWithoutFurtherRecords` |
| 저장은 성공했지만 계정 갱신만 실패하면 재시도에서 저장을 반복하지 않고 갱신만 수행한다. | `retry_refreshFailureOnlyRefreshesWithoutImportingAgain` |
| 계정 갱신 재시도가 다시 실패해도 기존 가져오기 개수와 갱신 실패 상태를 유지한다. | `retry_repeatedRefreshFailurePreservesPreviousCounts` |
| 일부 항목 실패 후에는 Repository의 최신 미완료 스냅샷을 다시 읽어 완료 항목을 중복 저장하지 않는다. | `retry_failedRecordsUsesNewSnapshotWithoutReimportingCompletedItems` |
| 오류가 많아도 실패 수는 모두 집계하고 화면 오류 목록은 처음 다섯 개로 제한한다. | `import_manyFailuresShowsOnlyFirstFiveWithoutLosingFailureCount` |
| ViewModel 해제 시 진행 중 읽기를 취소하고 이를 가져오기 실패로 표시하지 않는다. | `clearingViewModelCancelsPendingReadWithoutShowingFailure` |

## 실행과 측정

`frontend/`에서 실행한다.

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest --tests 'com.swpp.stylemate.profile.AuthViewModelTest' --tests 'com.swpp.stylemate.profile.GuestImportViewModelTest'
.\gradlew.bat --no-daemon createDebugUnitTestCoverageReport assembleDebug lintDebug
python scripts/coverage_by_layer.py app/build/reports/coverage/test/debug/report.xml
```

기준은 옷장 테스트 확장 후의 233개 JVM 테스트다. 새 테스트는 Auth 19개·GuestImport 21개이며, 명세와 메서드를 연결한다. 기준 커버리지는 `AuthViewModel` 라인 0%·분기 0%, `GuestImportViewModel` 라인 8.3%·분기 2.6%, 앱 전체 ViewModel 라인 56.8%·분기 46.1%다. 전체 결과는 원래 집계 규칙을 유지하며 기기 테스트와 섞지 않는다.

## 검증 결과 — 2026-11-05

| 검사 | 결과 |
| --- | --- |
| `AuthViewModelTest` | 19개 통과 |
| `GuestImportViewModelTest` | 21개 통과 |
| 전체 Android JVM 테스트 | 233개 → 273개, 실패·오류·건너뜀 없음 |
| 기존 인증·가져오기 Repository 테스트 | 17개·9개 모두 전체 실행에 포함되어 통과 |
| `assembleDebug`·`lintDebug` | 통과 |

| 범위 | 라인 C0 변경 전 → 후 | 분기 C1 변경 전 → 후 |
| --- | --- | --- |
| `AuthViewModel`과 코루틴 클래스 | 0% (0/65) → 96.9% (63/65) | 0% (0/52) → 80.8% (42/52) |
| `GuestImportViewModel`과 코루틴 클래스 | 8.3% (7/84) → 96.7% (89/92) | 2.6% (2/76) → 93.4% (71/76) |
| 앱 전체 ViewModel 분류 | 56.8% (624/1098) → 69.5% (769/1106) | 46.1% (333/723) → 61.4% (444/723) |
| 옷장 전체 ViewModel 분류 | 77.5% (316/408) → 77.5% (316/408) | 61.1% (135/221) → 61.1% (135/221) |
| 앱 데이터 계층 | 74.4% (1119/1505) → 74.9% (1127/1505) | 49.7% (577/1160) → 50.9% (591/1160) |

집계는 기존 `frontend/scripts/coverage_by_layer.py`와 같은 클래스 이름 규칙을 유지한다. 가져오기 실행 경계 분리로 ViewModel 라인 분모가 8 증가했으며, 측정 대상 제외나 기존 테스트 약화는 없다. AuthViewModel과 인증·가져오기 Repository의 동작 코드는 변경하지 않았다. GuestImportViewModel은 같은 작업을 전달하는 생성자·콜백 경계만 변경했으며 인증 정책·화면의 호출 방식·가져오기 처리 순서는 유지한다.

앱 전체 ViewModel 라인 60%·분기 40% 목표를 달성했다. 데이터 계층 라인 75% 목표는 74.9%로 아직 미달이며 기기 UI·Keystore·실제 서버 검증은 별도다. 위 표는 PR 게시 전 로컬 검증 결과이며, 원격 실행 결과는 PR의 Checks와 위키 Testing Documentation에서 확인한다.
