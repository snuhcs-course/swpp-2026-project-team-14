# 옷장 ViewModel 테스트 명세

## 범위와 기준

- 대상: `WardrobeViewModel.refresh`, `save`, `clearSaveState`와 이 동작 사이의 중복 요청 차단.
- 기준: [데이터 흐름](wardrobe-flow.md)의 목록 실패·저장 실패·동일 ID 재저장 규칙, [인증 계약](../login/README.md), [의상 API](wardrobe-spec.md).
- 기대 결과는 사용자가 확인하는 목록·오류·저장 완료 상태와 계정 캐시를 기준으로 정의한다. 구현에 맞추어 실패한 테스트의 기대 결과를 변경하지 않는다.
- 테스트는 JVM에서 실제 ViewModel·Repository·임시 계정 캐시를 연결하고 MockWebServer로 응답을 제어한다. 실제 서버·DB·유료 AI API는 호출하지 않는다.
- 기존 삭제·외부 추천 테스트를 유지한다. 1차는 목록·저장, 2차는 아래의 분석·착용 사진 ViewModel로 확장한다. 카메라 기기 동작과 실제 AI·실측 정확도는 별도 검증 범위다.

## `refresh()`

의도: 저장된 캐시를 먼저 보여 주고, 조회가 끝나면 계정 서버의 전체 목록과 편집 기준으로 갱신한다.

전제: 로그인한 계정과 해당 계정의 캐시를 사용한다.

| 사례 | Given / When / Then — 기대 결과 `[Unit]` | Tested by `WardrobeViewModelTest` |
| --- | --- | --- |
| 캐시·진행 상태 | 저장된 옷 `cached`와 미저장 초안 `draft`가 있고 서버 응답이 대기 중이다. 화면을 열면 `cached`만 표시하고 로딩을 유지한다. 응답 후 서버 옷 `remote`와 최신 편집 기준으로 교체한다. | `refresh_cachedItems_showsSavedCacheUntilRemoteCompletes` |
| 빈 목록 경계 | 이전 캐시가 있어도 서버의 확정 목록이 비어 있으면 화면과 목록 캐시를 빈 상태로 교체한다. | `refresh_emptyRemoteList_clearsOldItemsAndCache` |
| 여러 페이지 | 서버 목록이 두 페이지면 마지막 페이지까지 성공하기 전에는 일부 목록으로 캐시를 교체하지 않는다. 완료 후 두 페이지의 옷을 순서대로 표시한다. | `refresh_pagedList_publishesAllItemsAfterLastPage` |
| 목록 실패·재시도 | 목록 요청이 실패하면 캐시를 유지하고 오류와 재시도 가능한 상태를 표시한다. 다시 조회하여 성공하면 오류를 없애고 서버 목록을 표시한다. | `refresh_listFailure_keepsCacheAndAllowsRetry` |
| 편집 기준 실패·캐시 없음 | 목록은 성공했지만 편집 기준 요청이 실패하고 캐시도 없으면 서버 목록은 유지하고 오류를 표시한다. 로딩은 종료한다. | `refresh_optionsFailureWithoutCache_keepsItemsAndReportsError` |
| 편집 기준 실패·캐시 있음 | 편집 기준 요청이 일시 실패하고 캐시가 있으면 기존 기준을 사용한다. 목록 갱신은 성공하고 불필요한 오류를 표시하지 않는다. | `refresh_optionsFailureWithCache_usesCachedOptions` |
| 인증 만료 | 목록 요청이 401이면 캐시가 있어도 성공으로 처리하지 않는다. 만료 오류를 표시하고 기존 캐시를 유지한다. | `refresh_expiredSession_preservesCacheAndReportsExpiry` |
| 잘못된 서버 응답 | 목록 형식이 잘못되면 이전 목록과 캐시를 유지하고 오류를 표시한다. | `refresh_invalidListResponse_keepsCacheAndReportsError` |
| 조회 중 중복 동작 | 조회가 진행 중이면 추가 조회·저장·삭제 요청을 보내지 않는다. | `refresh_whileLoading_blocksRefreshSaveAndDelete` |

## `save(id, payload)`

의도: 기존 분석 ID로 편집 내용을 저장하고, 서버가 확정한 값을 화면과 계정 캐시에 반영한다.

전제: 조회·저장·삭제가 진행 중이지 않다. 필드 검증의 상세 규칙은 서버 계약을 따른다.

| 사례 | Given / When / Then — 기대 결과 `[Unit]` | Tested by `WardrobeViewModelTest` |
| 기존 항목 수정 | 옷 `first`, `second`가 있을 때 `first`를 저장하면 서버 응답의 값으로 같은 항목만 교체한다. 순서·개수와 `second`는 유지하고 캐시도 갱신한다. | `save_existingItem_replacesOnlyItsRowUsingServerResult` |
| 새 항목 추가 | 목록에 없는 분석 ID를 저장하면 확정 응답을 목록 맨 앞에 추가한다. 기존 항목은 유지한다. | `save_newItem_prependsSavedItemAndUpdatesCache` |
| 빈 목록·동일 ID 재저장 | 빈 옷장에 한 벌을 저장하고 같은 ID로 다시 저장해도 한 벌만 남는다. 두 번째 확정 값으로 갱신한다. | `save_emptyWardrobeAndRepeatedId_keepsOneUpdatedItem` |
| 실패·재시도 | 저장이 거절되면 기존 목록·캐시를 유지하고 저장 오류를 표시한다. 같은 ID와 편집값으로 재시도할 수 있으며 성공하면 오류가 사라진다. | `save_failure_preservesItemsAndAllowsRetry` |
| 저장 중 상태·중복 동작 | 응답 전에는 기존 목록·캐시를 유지하고 저장 중 상태를 표시한다. 추가 저장·조회·삭제 요청을 보내지 않는다. | `save_whilePending_blocksSaveRefreshAndDelete` |
| 인증 만료 | 저장 요청이 401이면 완료로 처리하지 않고 저장 오류를 표시한다. 기존 목록·캐시는 유지한다. | `save_expiredSession_keepsItemsAndReportsExpiry` |

## `clearSaveState()`

의도: 편집 화면이 처리한 저장 완료·오류 알림을 소비한다.

- 저장 완료 ID 또는 저장 오류가 있으면 둘을 초기화하고 의상 목록·계정 캐시는 유지한다. `[Unit]`
- Tested by `WardrobeViewModelTest.clearSaveState_clearsOutcomeWithoutChangingItems`.

## 실행·측정

Android 프로젝트 `frontend/`에서 실행한다.

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest --tests 'com.swpp.stylemate.wardrobe.WardrobeViewModelTest'
.\gradlew.bat --no-daemon createDebugUnitTestCoverageReport
python scripts/coverage_by_layer.py app/build/reports/coverage/test/debug/report.xml
```

모든 테스트는 `Thread.sleep` 대신 응답 도착·상태 조건·해제 가능한 요청 게이트로 대기한다. 완료 후 ViewModel과 가짜 서버를 해제하고 Main dispatcher를 복원한다.

### 기준 측정 — 2026-11-05

측정 기준은 `develop`의 `6dec3a6`이며 JVM 테스트 175개가 통과한 기존 실행 데이터로 보고서를 생성했다. 같은 실행 종류와 집계 규칙으로 변경 후 수치를 비교한다. 기기 테스트 커버리지는 합산하지 않는다.

| 범위 | 변경 전 라인 C0 | 변경 전 분기 C1 |
| --- | --- | --- |
| `WardrobeViewModel`과 해당 코루틴 클래스 | 75.0% (57/76) | 50.0% (20/40) |
| 옷장 패키지의 모든 ViewModel | 15.5% (57/368) | 9.5% (20/211) |
| 앱 전체 ViewModel | 34.5% (365/1058) | 30.6% (218/713) |

### 1차 검증 결과 — 2026-11-05

| 검사 | 결과 |
| --- | --- |
| 새 `WardrobeViewModelTest` | 16개 통과, 실패·오류·건너뜀 없음 |
| 전체 Android JVM 테스트 | 175개 → 191개, 전부 통과 |
| `assembleDebug` | 통과 |
| `lintDebug` | 통과 |
| 명세와 테스트 연결 | 명세 16개와 테스트 메서드 16개 일치 |

| 범위 | 라인 C0 변경 전 → 후 | 분기 C1 변경 전 → 후 |
| --- | --- | --- |
| `WardrobeViewModel`과 해당 코루틴 클래스 | 75.0% (57/76) → 93.4% (71/76) | 50.0% (20/40) → 82.5% (33/40) |
| 옷장 패키지의 모든 ViewModel | 15.5% (57/368) → 19.3% (71/368) | 9.5% (20/211) → 15.6% (33/211) |
| 앱 전체 ViewModel | 34.5% (365/1058) → 35.8% (379/1058) | 30.6% (218/713) → 32.4% (231/713) |
| 앱 데이터 계층 | 74.2% (1117/1505) → 74.4% (1119/1505) | 49.3% (572/1160) → 49.7% (577/1160) |

1차의 두 보고서는 같은 코드·설정·집계 방식의 JVM 실행 데이터다. 1차에서는 앱 기능 코드·의존성·기존 테스트를 변경하지 않았으며, 목표 수치에 도달하기 위해 검사 대상을 제외하지 않았다. 전체 계층 집계는 기존 `frontend/scripts/coverage_by_layer.py`를 사용하고, 옷장 집계는 같은 ViewModel 분류 규칙에 옷장 패키지 조건을 추가했다.

1차에서는 분석·착용 사진 ViewModel을 포함하지 않았으므로 옷장 전체나 앱 전체 ViewModel의 라인 커버리지 목표 60%를 달성한 상태가 아니었다. 아래 2차에서 이 세 ViewModel을 추가한다.

## 2차: 분석·착용 사진 ViewModel

### 실행 경계와 테스트 방법

- Android `Context`·`Bitmap`을 읽거나 파일을 쓰는 준비 과정과 ViewModel 상태 처리를 분리한다. 기존 화면이 사용하는 생성자와 메서드는 유지하며, 실제 실행에서는 기존 Repository와 파일 처리 함수를 연결한다.
- JVM에서는 같은 상태 처리 경로에 가짜 응답·파일 콜백을 전달한다. 유료 API나 실제 사용자 사진은 사용하지 않는다. 전송 규칙·서버 검증은 기존 Repository·백엔드·기기 테스트와 함께 평가한다.
- `StandardTestDispatcher`와 `runTest`로 코루틴 시간을 제어한다. 1.2초 간격의 상태 조회도 가상 시간으로 진행하므로 실제로 수십 초를 기다리지 않는다.
- 서비스 대역은 업로드 ID·생성 요청 ID·호출 순서·요청 본문을 기록한다. 상태 조회가 유료 생성 요청으로 바뀌거나 재시도 ID가 바뀌는 오류를 검사한다.

### `GarmentAnalysisViewModel.select` / `analyze`

전제: 계정은 로그인되어 있다. 실제 이미지 읽기와 서버 호출은 준비 경계에서 수행한다.

| 기대 결과 `[Unit]` | Tested by `GarmentAnalysisViewModelTest` |
| --- | --- |
| 초안을 복원하고 같은 사진 선택 시 유지한다. 사진을 바꾸면 이전 결과·오류를 초기화한다. | `select_restoresDraftAndChangingPhotoClearsIt` |
| 분석 중에는 중복 분석을 차단하고 완료 후 해당 사진의 결과를 표시한다. | `analyze_successPublishesResultAndBlocksDuplicateRequest` |
| 실패하면 사진과 오류를 유지하고 같은 사진으로 재시도할 수 있다. | `analyze_failureKeepsPhotoAndRetryCanSucceed` |
| 예외 메시지가 없으면 기본 오류 문구를 표시한다. | `analyze_exceptionWithoutMessageShowsFallback` |
| 다른 사진 선택 후 이전 분석의 늦은 결과가 새 사진을 덮어쓰지 않는다. | `select_newPhotoCancelsOldAnalysisWithoutPublishingItsResult` |
| 사진 선택을 해제하면 분석을 취소하고 빈 상태로 돌아간다. | `select_nullCancelsAnalysisAndReturnsToEmptyState` |
| ViewModel 해제 시 진행 중 분석을 취소하며 오류 결과로 바꾸지 않는다. | `clearingViewModelCancelsPendingAnalysis` |

### `WornBatchViewModel`

전제: 한 사람의 원본 사진에서 제안된 후보를 등록한다. 생성은 명시적으로 선택한 옷에만 요청한다.

| 함수·기대 결과 `[Unit]` | Tested by `WornBatchViewModelTest` |
| --- | --- |
| `detect`: 인코딩 바이트를 한 번 업로드하고 준비된 후보를 선택 상태로 표시한다. | `detect_readyPhotoSelectsCandidatesAndUploadsEncodedBytesOnce` |
| `detect`: 원본 분석 진행 중에는 기존 원본 ID를 조회하며 다시 업로드하지 않는다. | `detect_pendingPhotoPollsExistingSourceWithoutReuploading` |
| `detect`: 인식 실패 이유를 표시하고 새 사진 선택 시 새 요청 ID를 사용한다. | `detect_failedPhotoShowsReasonAndCanSelectAnotherPhoto` |
| `detect`: 인코딩 실패 시 서비스를 호출하지 않고 오류를 표시한다. | `detect_encodingFailureKeepsErrorWithoutCallingService` |
| `refreshPhoto`: 업로드 응답 유실은 같은 요청 ID로 확인하고, 이미 원본 ID가 있으면 조회만 한다. | `refreshPhoto_lostResponseReusesUploadOperationId` |
| `select`: 준비 전 후보를 선택·해제할 수 있지만 초안 준비 후 선택 집합은 바꾸지 않는다. | `select_togglesCandidatesOnlyBeforeDraftsArePrepared` |
| `generateSelected`: 후보가 없으면 준비·생성 요청을 보내지 않는다. | `generate_noSelectionDoesNotPrepareOrGenerate` |
| `generateSelected`: 순차 생성하고 한 벌 실패가 다른 성공 결과를 지우지 않는다. | `generate_pendingDraftsAreSequentialAndOneFailurePreservesOtherResults` |
| `generateSelected`: 이미 준비된 생성 결과를 다시 생성하지 않는다. | `generate_readyDraftsAreNotRegenerated` |
| `generateSelected`: 생성 POST 이후 상태는 GET으로 조회하며 유료 요청을 반복하지 않는다. | `generate_runningStatusUsesGetForPollingAndOnePaidPost` |
| `generateSelected`: 크레딧 오류가 나면 다음 유료 호출을 중단한다. | `generate_creditErrorStopsRemainingPaidRequests` |
| `generateSelected`: 응답 유실·연결 오류를 자동 재생성으로 바꾸지 않는다. | `generate_networkFailureStopsWithoutAutomaticRetry` |
| `refresh`: 명시적으로 고른 원본 영역 이미지는 조회 후에도 유지한다. | `refreshItem_preservesExplicitCropChoiceAndDoesNotGenerate` |
| `regenerate`: 사용자가 명시적으로 요청한 경우에만 새 생성 ID를 사용한다. | `regenerate_usesNewRequestIdOnlyAfterExplicitAction` |
| `include`·`chooseImage`·`editLabel`·`edit`·`save`: 포함한 승인 항목만 저장하고 편집값·확인 라벨을 전송한다. | `edit_saveSendsOnlyIncludedApprovedItemsAndConfirmsLabels` |
| `save`: 미승인 항목 또는 빈 선택은 저장하지 않는다. | `save_unreviewedOrEmptySelectionDoesNotSendSave` |
| `save`: 실패해도 생성 결과·선택값을 유지하고 재시도한다. | `save_failureKeepsResultsForRetry` |
| 작업 중에는 중복 분석·생성·편집·선택·저장을 차단한다. | `busy_blocksDuplicateDetectionSelectionEditingAndSave` |
| `reset`: 취소 후 즉시 새 작업을 시작해도 이전 작업이 새 로딩 상태를 해제하지 않는다. | `reset_thenNewDetectionKeepsNewOperationBusyUntilItsOwnResponse` |
| 분석 조회 횟수 제한 후에는 추가 업로드 없이 수동 확인 가능한 원본을 유지한다. | `detect_pollLimitLeavesManualRefreshWithoutAnotherUpload` |
| 생성 조회 횟수 제한 후에도 유료 POST는 한 번이며 미완료 결과는 저장할 수 없다. | `generate_pollLimitNeverRepeatsPaidPost` |

### `WornGenerationViewModel`

전제: 한 벌의 원본 사진과 분석 초안으로 생성 결과를 검토한다. 임시 사진은 명시적으로 이관하기 전까지 해당 ViewModel이 소유한다.

| 함수·기대 결과 `[Unit]` | Tested by `WornGenerationViewModelTest` |
| --- | --- |
| `start`: 원본 분석 후 같은 초안 ID로 한 번 생성하고 결과를 표시한다. | `start_analyzesOriginalThenGeneratesWithStableDraftId` |
| `start`: 진행 중이거나 초안이 있으면 원본 분석·생성을 다시 시작하지 않는다. | `start_whilePendingOrAlreadyPreparedDoesNotAnalyzeAgain` |
| `start`: 원본 분석 실패 후 재시도 시 사진과 업로드 ID를 유지한다. | `start_analysisFailureRetainsPhotoAndOperationIdForRetry` |
| 상태 조회는 GET이며 추가 생성 POST를 보내지 않는다. | `poll_runningStatusUsesGetAndDoesNotRegenerate` |
| 조회 제한에 도달하면 수동 확인 안내를 표시하고 유료 요청을 반복하지 않는다. | `poll_limitShowsManualRefreshAndNeverRepeatsPaidPost` |
| `refresh`: 조회 실패 후 초안을 유지하고 새 유료 호출 없이 다시 조회할 수 있다. | `refresh_failureKeepsDraftAndCanRetryWithoutAnotherPaidPost` |
| `regenerate`: 명시적인 재생성만 새 생성 요청 ID를 만든다. | `regenerate_requiresExplicitActionAndCreatesNewOperationId` |
| `review`: 생성 이미지 승인 성공 후에만 승인 결과를 제공한다. | `review_generatedApprovesOnlyAfterServiceSuccess` |
| `review`: 원본 영역 승인 시 추가 생성하지 않는다. | `review_cropApprovesOriginalWithoutAnotherGeneration` |
| `review`: 거절은 승인 결과를 제공하지 않는다. | `review_rejectionDoesNotApproveTheDraft` |
| `review`: 승인 요청 실패 시 초안을 유지하고 재시도할 수 있다. | `review_failureKeepsDraftAndAllowsRetry` |
| `reset`: 이전 사진만 지우고 이전 작업의 종료가 새 작업의 로딩 상태를 바꾸지 않는다. | `reset_thenNewStartKeepsNewOperationBusyAndDeletesOnlyOldPhoto` |
| `transferred`: 사진을 이관한 후 ViewModel을 해제해도 이관한 사진은 삭제하지 않는다. | `transferred_doesNotDeletePhotoWhenViewModelIsCleared` |
| `onCleared`: 소유 중인 임시 사진은 ViewModel 해제 시 정리한다. | `clearingViewModelDeletesTemporaryCapture` |

### 취소·재시작 회귀

초기 확장 검사에서 42개 중 40개가 통과하고 두 `reset_thenNew...` 테스트가 실패했다. 두 화면 모두 이전 작업의 `finally`가 새 작업의 `busy=true`를 `false`로 덮어쓰는 것이 원인이었다. 취소된 코루틴은 오류·완료 상태를 갱신하지 않도록 수정한다. 새 작업이 끝날 때까지 로딩과 중복 요청 차단을 유지한다는 기대 결과는 변경하지 않는다.

### 2차 검증 결과 — 2026-11-05

| 검사 | 결과 |
| --- | --- |
| `GarmentAnalysisViewModelTest` | 7개 통과 |
| `WornBatchViewModelTest` | 21개 통과 |
| `WornGenerationViewModelTest` | 14개 통과 |
| 전체 Android JVM 테스트 | 233개 통과, 실패·오류·건너뜀 없음. 기존 175개 + 1차 16개 + 2차 42개 |
| `assembleDebug`·`lintDebug` | 통과 |
| 취소·재시작 회귀 | 수정 전 두 테스트 실패, 수정 후 두 테스트를 포함한 42개 통과 |

| 대상 | 1차 라인 C0 → 2차 | 2차 분기 C1 |
| --- | --- | --- |
| `WardrobeViewModel` | 93.4% → 93.4% (71/76) | 82.5% (33/40) |
| `GarmentAnalysisViewModel` | 0% → 79.3% (23/29) | 80.0% (8/10) |
| `WornBatchViewModel` | 0% → 89.5% (136/152) | 86.4% (57/66) |
| `WornGenerationViewModel` | 0% → 86.5% (77/89) | 68.8% (22/32) |
| 옷장 전체 ViewModel 분류 | 19.3% → 77.5% (316/408) | 61.1% (135/221) |
| 앱 전체 ViewModel 분류 | 35.8% → 56.8% (624/1098) | 46.1% (333/723) |

2차는 실행 경계 분리와 취소 오류 수정을 포함하므로 코드 분모가 달라졌다. 측정 대상 제외나 분류 규칙 변경은 없으며, 기존 집계 규칙에 따라 코루틴·이름에 `ViewModel`이 있는 Kotlin 파일 보조 클래스까지 합산한다. 개별 네 행은 해당 ViewModel과 코루틴 중첩 클래스 기준이므로 보조 클래스를 포함한 전체 행과 단순 합산 값이 다를 수 있다.

옷장 전체의 라인 60%·분기 40% 목표를 달성했다. 2차 종료 시 앱 전체 ViewModel 라인은 56.8%였으며, 이어진 [로그인·가져오기 테스트](../login/viewmodel-testing.md)에서 전체 273개 테스트와 ViewModel 라인 69.5%·분기 61.4%를 확인했다. Android 이미지·파일 연결, 기기 UI·AR·실제 AI 품질은 이 JVM 측정에 포함하지 않는다. 위 표는 PR 게시 전 로컬 검증 결과이며, 원격 실행 결과는 PR의 Checks와 위키 Testing Documentation에서 확인한다.
