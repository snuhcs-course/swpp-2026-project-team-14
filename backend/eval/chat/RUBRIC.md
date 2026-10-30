# 채팅 AI 평가 기준 (Rubric)

평가 대상은 채팅의 AI 부분인 **LLM 요청 해석**(`recommendation/chat_intent.py`의 `parse`)이다.
사용자 문장을 수정 의도 JSON(action, 바꿀/유지할 슬롯, 방향, 피할/원하는 색, 지정한 옷, 아우터 빼기)으로 바꾸는 단계다.
해석 이후의 후보 필터링·코디 선택은 규칙 기반 코드라 기존 단위 테스트(`apps/outfits/test_chat.py`)가 검증한다.

이 파일과 `cases.json`은 **평가를 처음 실행하기 전에 커밋**하며, 결과를 보고 케이스나 기준을 바꾸지 않는다.

## 평가셋 (`cases.json`)

- 고정 케이스 **34개**: 단일 방향 8, 슬롯 한정 5, 유지+변경 3, 부정·색 4, 상황→방향 4, 되돌리기 2, 특정 옷 지정 3, 지원 안 함·모순 3, 잠금·대화 기록 2
- 모든 케이스는 같은 가상 옷장 14벌(`scripts/seed_chat_wardrobe.py`의 샘플과 같은 구성)을 쓴다.
- 케이스 입력: 요청 문장, 현재 코디, 잠근 옷(→ `locked_slots`), 직전 대화 기록
- `expect`에는 그 케이스에서 **판단에 필요한 필드만** 적는다. 적지 않은 필드는 채점하지 않는다.

## 케이스 채점 (자동, 사람 판단 없음)

`expect`의 항목을 모두 만족하면 **통과**, 하나라도 어기면 **실패**다.

| 항목 | 만족 조건 |
|---|---|
| `action` | 출력 action이 같음 |
| `wants_include` | 각 {slot, want}에 대해 같은 want가 있음. slot이 null이면 어느 slot이든, slot이 지정되면 그 slot 또는 null(전체)이면 인정 |
| `wants_exclude` | 해당 want가 어느 slot에도 없음 |
| `change_slots_include` / `_exclude` | change_slots에 모두 있음 / 하나도 없음 |
| `keep_slots_include` / `_exclude` | keep_slots에 모두 있음 / 하나도 없음 |
| `avoid_colors_include` | avoid_colors에 모두 있음 |
| `target_colors_include` / `_exclude` | target_colors에 모두 있음 / 하나도 없음 |
| `target_garment_ids` | target_garment_ids가 정확히 같음(순서 무관) |
| `remove_outerwear` | 값이 같음 |

## 치명적 오류

사용자가 바로 "반대로 했다"고 느끼는 오류다. 케이스 실패와 별도로 센다.

1. 케이스 `critical`에 적힌 항목을 어김
   - 부정 반전: "밝게 하지 마"인데 brighter (`wants_exclude`)
   - 색 반전: 피하라는 색을 원하는 색으로 넣음 (`target_colors_exclude`)
   - 유지 무시: 유지하라는 슬롯을 유지 목록에서 빠뜨림 (`keep_slots_include`)
2. (모든 케이스) 옷장에 없는 옷 ID를 `target_garment_ids`에 넣음
3. (모든 케이스) 같은 슬롯을 `keep_slots`와 `change_slots`에 동시에 넣음

## 오류 분류

- **형식 오류**: 응답이 JSON이 아니거나 스키마 검증(`chat_intent.validate`)을 통과하지 못함 → 해당 케이스 실패 + 형식 오류로 집계
- **인프라 오류**: 네트워크·API 키·요청 한도 등 모델 밖의 실패 → 채점하지 않고 해당 케이스를 다시 실행한다. 인프라 오류가 남은 실행은 결과로 쓰지 않는다.

## 통과 기준

다음을 **모두** 만족하면 통과다.

| 지표 | 기준 |
|---|---|
| 케이스 통과율 | **80% 이상** (34개 중 28개 이상) |
| 치명적 오류 | **0건** |
| 형식 오류 | **0건** |

## 실행 조건

- 모델: 서비스와 같은 `gemini-3.1-flash-lite`, temperature 0
- 케이스당 1회 호출, 결과(케이스별 출력·통과 여부·사유, 집계, 실행 일시, 모델명)는 파일로 저장해 커밋한다.
