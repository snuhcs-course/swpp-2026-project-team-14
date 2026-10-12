# 코디 추천

사용자 옷장에 있는 옷으로 오늘의 코디를 만든다. K-Fashion(AI Hub)은 어떤 옷끼리 어울리는지 배우는 학습 데이터로만 쓰고, 추천에 K-Fashion 옷을 보여 주지 않는다. API 계약은 [api.md](api.md)를 따른다.

## 흐름

`옷 등록(현우) → Gemini 분석 + details → 치수·체형으로 추천 라벨 계산 → 사용자 확인·수정 → 저장 → 홈: 날씨 + 내 옷장 → 코디 3개 → 좋아요·별로예요·코디북 저장`

- 체형은 지금은 기기(`BodyProfileStore.kt`)에 있고 요청마다 같이 보낸다. 서버는 `apps/body_profiles/profile.py`의 `body_from_request()` 한 곳에서만 체형을 읽으므로 user table이 생기면 이 함수만 바꾸면 된다.
- 취향(좋아요·별로예요로 배운 속성 가중치)도 기기(`TasteStore.kt`)에 두고 요청마다 보낸다. 서버는 상태를 갖지 않는다.
- 신발은 K-Fashion에 없어 이번 추천에서 뺐다. 채팅으로 코디를 고치는 기능은 주현 님 담당이라 여기서 다루지 않는다(홈 화면의 `onChat` 자리만 열어 둠).

## 구조

```text
backend/
|-- recommendation/                 # 추천 엔진 (Django 비의존)
|   |-- labeling.py                 # 치수 + 체형 → 핏·기장·소매·계절·색·카테고리 라벨, 저장 전 검증
|   |-- search.py                   # 후보 필터 → 조합 점수 → 배색 → 다양성(MMR). mode="wardrobe"가 앱 경로
|   |-- wardrobe.py                 # 옷장 옷(라벨) → K-Fashion 속성 Item
|   |-- compat.py · masked.py       # PMI 궁합, 팀원 마스킹 트랜스포머(onnxruntime) 쌍 점수
|   |-- fit.py · body.py · body_rules.py · rules/  # 여유분 표, 체형 판정, 출처 있는 체형 규칙표
|   |-- season.py · weather.py · color.py · preference.py · explain.py  # 날씨, 배색, 취향, 설명 문장
|   |-- vocab.py                    # K-Fashion 값 목록과 앱 코드 매핑
|   |-- models/                     # 학습 결과 (아래 표)
|   `-- demo/                       # 로컬 테스트 페이지 (배포 이미지에서 제외)
|-- apps/outfits/                   # 코디 테이블, 추천·저장·피드백 API, 날씨
|-- apps/wardrobe/                  # (현우) 분석 details, labels 컬럼, POST labels/
|-- apps/body_profiles/profile.py   # body_from_request(): 체형을 읽는 유일한 곳
`-- scripts/kfashion/               # 전처리·통계·학습·평가 (로컬 데이터 필요)
frontend/app/src/main/java/com/swpp/stylemate/
|-- data/recommendation/            # OutfitRepository (API), TasteStore (기기 취향)
|-- ui/home/                        # 오늘의 코디, 코디북
`-- ui/wardrobe/GarmentLabels.kt    # 등록 화면의 "내 몸 기준 라벨" 섹션
```

## 추천 라벨 (등록 시)

`labeling.label(attributes, dimensions, body, details)`가 계산하고, 앱이 근거와 함께 보여 준 뒤 사용자가 확인한 값을 `labels`로 저장한다. 사용자가 고친 값이 언제나 우선한다.

| 라벨 | 계산 | 구간 |
| --- | --- | --- |
| 핏 (상의·아우터) | 가슴 여유 = 가슴단면 × 2 − 가슴둘레 | 상의 0–6 타이트, 6–12 노멀, 12–20 루즈, 20+ 오버사이즈 / 아우터는 6 cm씩 위 |
| 핏 (바지) | 밑단 ÷ 허벅지 단면, 엉덩이 여유 | > 1.0 벨보텀, ≥ 0.85 또는 여유 ≥ 12 와이드, < 0.6이고 여유 < 6 스키니, 그 외 노멀 |
| 핏 (스커트) | 엉덩이 여유 | < 2 스키니, > 12 루즈, 그 외 노멀 |
| 작음 | 여유 < 0, 허리단면 × 2 < 허리둘레 − 2 | 추천에서 제외하고 이유를 표시 |
| 기장 | 상의 총장 ÷ 상체길이, 아우터 총장 ÷ 키, 바지 인심 ÷ 다리 안쪽, 스커트 총장 ÷ 다리 바깥 | 상의 < 1.15 크롭, > 1.7 롱 / 아우터 0.30·0.42·0.52·0.62 / 바지 0.25·0.55·0.85·1.05 / 스커트 0.42·0.55·0.8 |
| 소매 | 소매길이 ÷ 팔 길이 | 0.08·0.2·0.5·0.85 경계로 민소매·캡·반팔·7부·긴팔 |
| 드롭숄더 | 어깨너비 > 체형 어깨 + 4 cm | |
| 계절 | 카테고리·소재·소매·기장의 보온 점수 (`masked.season_token`) | 여름·간절기·겨울 |
| 색·카테고리 | HEX → K-Fashion 21색 중 CIELab 거리가 가장 가까운 색, 서브카테고리 → K-Fashion 카테고리 | |
| 무늬·넥라인·옷깃 | 옷장 분석 Gemini 호출의 `details` (추가 호출 없음) | K-Fashion 값만 허용 |
| 소재 | 서브카테고리 기본값(청바지 → 데님 등), 사용자가 고름 | 최대 2개 |

체형 값이 없으면 키 비율로 대신한다(상체 0.26, 팔 0.34, 다리 바깥 0.6). 라벨 없이 저장된 옷은 추천할 때 그 자리에서 계산하고 저장하지 않는다.

## 점수

옷마다 거르기 → 조합 점수 → 배색 → 다양성 순서다.

1. **거르기**: 몸보다 작은 옷, 다시 보지 않기, 날씨에 맞지 않는 옷(그 슬롯에 남는 옷이 없으면 감점하고 사용). 내 옷은 성별·선호 핏으로 거르지 않고 점수만 낮춘다.
2. **조합**: 날씨 구간의 템플릿(상의+하의, 아우터+상의+하의)마다 모든 조합을 계산한다.
   - 총점 = 0.30 궁합 + 0.25 체형·핏 + 0.15 배색 + 0.15 날씨 + 0.15 취향
   - 궁합 = PMI 점수와 마스킹 모델 쌍 점수를 각각 "실제 K-Fashion 코디 중 몇 %보다 나은가"로 바꿔 반씩 더한 값
   - 마스킹 쌍 점수 = ½(상의를 보고 하의를 예측한 확률 + 하의를 보고 상의를 예측한 확률), 옷 한 벌당 한 번 추론
3. **배색**: 뉴트럴·톤온톤·보색 규칙과 K-Fashion 색 조합.
4. **다양성**: MMR로 서로 다른 3개를 고르고, 이미 보여 준 코디(`seen_keys`)는 크게 감점한다. 비·눈 오는 날은 스웨이드·린넨 등을 감점한다.

옷장 6벌 기준 추천 한 번은 약 10 ms다(CPU, onnxruntime).

## 모델

| 파일 (`recommendation/models/`) | 내용 | 크기 |
| --- | --- | --- |
| `stats.json` | 슬롯·속성 쌍 PMI(축소 추정)와 로지스틱 회귀 가중치, 실제 코디 점수 분위수 | 277 KB |
| `masked_model.onnx` | 팀원 설계 마스킹 속성 트랜스포머 (임베딩 128, 2층, 4헤드, 입력 길이 64 고정) | 1.8 MB |
| `masked_vocab.json` | 토큰 사전, FITB 결과, 쌍 점수 분위수(`pair_calibration`) | 7 KB |
| `decision_calibration.json` | 테스트 페이지 결정 확률의 온도 보정값 | 1 KB |

모두 집계 통계와 가중치뿐이며 K-Fashion 이미지·라벨 원본은 들어 있지 않다.

**평가 (빈 칸 맞히기, 4지선다, 우연 25%)**

| 검증 코디 5,000개 | 전체 슬롯 | 상의+하의 코디 |
| --- | --- | --- |
| PMI | 44.8% | 44.5% |
| 마스킹 모델 | 45.4% | 42.8% |
| 마스킹 + PMI | 50.1% | 48.2% |
| 앱 점수 (쌍 점수 + PMI 분위수 평균) | - | **48.8%** |

**상의+하의 데이터만으로 재학습한 결과** (`train_masked.py --require 상의,하의`, 학습 코디 105,066개): 같은 상의+하의 평가에서 앱 점수 48.5%, 마스킹 42.3%, 마스킹+PMI 47.9%로 전체 데이터 모델보다 모두 조금 낮았다. 원피스·아우터 코디도 상의·하의 관계를 배우는 데 도움이 되는 것으로 보고 전체 데이터 모델을 유지했다.

### 다시 만들기

K-Fashion zip을 `<repo>/kfashion_dataset/K-Fashion 이미지/`(`Training/`, `Validation/`)에 두고 `backend/`에서 실행한다. 이 폴더는 `.gitignore`에 있다.

```bash
.venv/Scripts/python scripts/kfashion/build_stats.py                 # PMI·궁합 통계 → catalog/stats.json
.venv/Scripts/python scripts/kfashion/train_masked.py --export         # 마스킹 모델 (CPU 약 15분, torch 필요)
.venv/Scripts/python scripts/kfashion/eval_masked.py --models "../kfashion_dataset/K-Fashion 이미지/catalog" --calibrate "../kfashion_dataset/K-Fashion 이미지/catalog"
```

결과 파일을 `recommendation/models/`로 복사한다. 테스트 페이지용 컷아웃은 `build_catalog.py`, 전처리 예시 JSON은 `export_preprocessed.py`가 만든다(둘 다 데이터 폴더에만 저장).

## 테스트 페이지

```bash
cd backend
.venv/Scripts/python -m recommendation.demo --port 8010
```

- `/wardrobe`: 앱과 같은 경로. 샘플 옷장 → 체형 기준 라벨 → 내 옷만으로 코디 (데이터 없이 동작)
- `/`, `/stylist`: K-Fashion 카탈로그로 엔진·결정 단계를 보는 페이지 (로컬 컷아웃 필요). 자유 입력 채팅과 Gemini 요청 해석은 넣지 않았다.

## 데이터와 개인정보

- K-Fashion 이미지 데이터: AI Hub(한국지능정보사회진흥원) 제공. 원본·컷아웃·전처리 파일은 재배포할 수 없어 저장소와 배포 이미지에 넣지 않는다.
- 추천 요청에는 체형 숫자만 들어가며 서버는 체형을 저장하지 않는다. 사진은 옷장 사진만 쓴다.

## 남은 일

- user table 연결: `body_from_request()`를 request.user 기반으로 바꾸고 `Outfit`·`Garment`에 owner를 둔다.
- 신발 추천(K-Fashion에 없음), 채팅 편집 연결(`HomeScreen(onChat = …)`, `POST /api/outfits/` source=chat).
- (여유가 되면) 옷장 밖의 옷 추천: 같은 엔진이 K-Fashion 카탈로그에서 고르는 경로(`mode="new"`, 테스트 페이지 `/`)가 이미 있다.
