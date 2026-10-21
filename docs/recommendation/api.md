# 추천 API

모든 요청·응답은 JSON(UTF-8)이다. 계정 코디 API와 옷장 DB 분석·저장·조회에는 [로그인 API](../login/README.md)의 Bearer 토큰이 필요하며 본인 데이터만 사용한다. 날씨·라벨·비저장 분석·코디 미리보기는 비로그인에서도 제공한다. 체형은 기존대로 요청의 `body`로 받는다(앱의 `BodyProfileJson` 형식 그대로, 또는 `{height, chest, waist, hip, shoulder, inseam, …}` cm 값). 서버 쪽에서 체형을 읽는 곳은 `apps/body_profiles/profile.py`의 `body_from_request()` 하나다. 사용자 DB의 선호 정보를 추천 계산에 새로 연결하지 않는다.

현재 비로그인 앱은 `POST /api/outfits/preview/`에 기존 추천 요청과 `wardrobe` 배열(로컬 옷의 UUID·속성·치수·라벨)을 전달한다. 요청 전체는 최대 1MiB이며 옷 ID 중복·잘못된 필드를 검증한다. 별도 100벌 제한은 두지 않는다. 앱은 메모·사진 경로·분석 캐시를 제외하고 전송하며, 같은 바이트 제한을 초과하면 계정 가져오기를 안내한다. 로컬 메모는 유지한다. 기존 추천 계산 결과를 반환하고 DB에 저장하지 않는다. 결과 ID는 같은 조합에 대해 유지되며 앱이 코디 저장 여부를 로컬에 관리한다. `POST /api/outfits/preview/feedback/`은 해당 코디·구성 옷·기존 취향으로 피드백을 계산한다(최대 64KiB). 계산은 온라인이며 서버 DB에 기록하지 않는다. 새 추천 알고리즘이나 사용자 DB 취향 연동은 추가하지 않는다.

## 옷 등록: 추천 라벨

### 분석 응답의 details

`POST /api/wardrobe/analyze/?garment=<측정 종류>` 응답에 `details`가 추가됐다. `garment`(선택)는 측정 화면에서 고른 종류이며 AI 힌트로만 쓴다. `attributes`의 `subcategory`·`styles`는 이제 AI 제안이다(분류와 맞지 않으면 null). 상의·아우터는 앞면 사진을 보낸다.

```json
{"id": "…", "model": "gemini-3.1-flash-lite", "attributes": {"name": "스트라이프 니트 조끼", "category": "top", "…": "…"},
 "details": {"print": "스트라이프", "neckline": "브이넥", "collar": null, "design_details": ["드롭숄더"], "materials": ["니트"]}}
```

값은 K-Fashion 목록(`recommendation/vocab.py`의 PRINTS·NECKLINES·COLLARS·DETAILS·MATERIALS)에서만 나온다. 디테일은 최대 3개, 소재는 겉으로 분명할 때만 최대 2개다. 하의는 넥라인·옷깃이 없고, 신발이나 알 수 없으면 `null`이다. DB에는 저장하지 않는다.

### POST /api/wardrobe/labels/

저장하지 않고 라벨만 계산한다. DB가 없어도 동작한다. `body`가 null이면 체형과 무관한 라벨(계절·색·카테고리·details)만 채우고 핏·기장·작음은 비운다. `garment_type`(선택)은 측정 종류다.

```json
{"attributes": {"name": "베이지 니트", "category": "top", "subcategory": "knitwear", "colors": ["#D6BE9A"], "styles": [],
                "fit_type": null, "leg_shape": null, "rise_type": null, "skirt_shape": null},
 "dimensions": {"unit": "cm", "chest_width_half": {"value": 52, "source": "arcore_manual", "method": "flat_underarm_to_underarm", "reference": null}},
 "body": {"height_cm": 163, "gender": "female", "measurements": [{"type": "chest", "value_cm": 84}]},
 "details": {"print": "무지", "neckline": "라운드넥", "collar": null, "design_details": [], "materials": ["니트"]},
 "garment_type": "long_sleeve_top"}
```

응답 `{"labels": {…}}` (신발은 `null`):

```json
{"fit": "루즈", "fit_basis": "가슴 여유 20 cm · 가슴둘레 84 cm 기준", "length": "노멀", "length_basis": "총장 63 cm · 상체 길이의 1.4배",
 "sleeve": "긴팔", "sleeve_basis": "소매 58 cm · 팔 길이의 104%", "season": "간절기", "drop_shoulder": true,
 "prints": ["무지"], "neckline": "라운드넥", "collar": null, "design_details": [], "materials": ["니트"], "kf_category": "니트웨어", "kf_colors": ["베이지"],
 "too_small": false, "too_small_reason": null, "body_basis": {"height": 163, "chest": 84}, "confirmed": false}
```

`kf_colors`는 등록 색상의 앞 두 개를 변환한 순서이며 같은 색상명이 두 번 나올 수 있다. 추천 입력의 `색상`·`서브색상`에 대응하고 3번 이후의 색상은 사용하지 않는다. 저장 요청의 `kf_category`는 옷 분류와 일치하는 허용 카테고리만 받는다.

허용값은 `GET /api/wardrobe/options/`의 `label_options`(분류별 fit·length·sleeve·season·prints·neckline·collar·design_details·materials, `limits`)에 있다. 오류: 체형·속성·치수·측정 종류가 잘못되면 400 `INVALID_LABEL_REQUEST`.

### PUT /api/wardrobe/items/{id}/

`labels`를 선택 키로 받는다. 사용자가 확인한 라벨을 `confirmed: true`로 보낸다. 앱은 라벨과 함께 저장할 때 `attributes.fit_type`·`leg_shape`를 null로 보낸다(핏은 라벨에만). 키가 없으면 기존 라벨을 유지하고, 분류를 신발로 바꾸면 비운다. 허용값 밖이면 400 `INVALID_GARMENT`. 조회 응답에도 `labels`가 들어 있다.

## 코디

### POST /api/outfits/recommend/

저장된(saved) 내 옷만으로 코디를 만들고, 만든 코디를 코디 테이블에 `source: "recommended"`로 기록한다.

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `body` | O | 체형. 앱 형식이면 `preferred_fit`·`preferred_styles`도 기본값으로 쓴다 |
| `weather` 또는 `temp` | O | `GET /api/weather/` 결과 그대로(체감온도·비·눈 사용) 또는 기온 숫자 |
| `pref_fit` | | slim·regular·loose |
| `styles` | | 미니멀·캐주얼·스트릿·러블리·클래식·스포티 중 최대 6개 |
| `preference` | | 기기에 둔 취향 (feedback 응답의 `preference`) |
| `seen_keys` | | 이미 보여 준 코디의 `key` 목록 (다른 코디 보기) |
| `anchor_id` | | 이 옷을 꼭 넣은 코디 (garment id) |
| `count` | | 1–5, 기본 3 |
| `occasion` | | 상황 메모 (지금은 기록만) |

응답:

```json
{"outfits": [{"id": "…", "source": "recommended", "saved": false, "name": "", "key": "w:<id>+w:<id>", "score": 73,
              "components": {"compat": 62, "body": 89, "color": 74, "season": 30, "taste": 68},
              "reasons": [{"kind": "color", "text": "베이지 · 블랙 뉴트럴 조합이라 차분하고 실패가 적어요.", "sub": "배색 규칙 + K-Fashion 색 조합"}],
              "context": {"temp": 16.0, "band": "선선한 가을", "pref_fit": "loose", "styles": ["미니멀"]},
              "feedback": null, "created_at": "…",
              "items": [{"garment_id": "…", "slot": "top", "name": "베이지 니트", "colors": ["#D6BE9A"],
                         "image_url": "/api/wardrobe/items/<id>/image/", "note": "내 옷 · 내 몸 기준 루즈 (가슴 여유 20 cm · 가슴둘레 84 cm 기준)"}]}],
 "notes": ["내 옷 '작은 셔츠'는 가슴둘레보다 4 cm 작아요. 추천에서 뺐어요."],
 "band": {"label": "선선한 가을", "outer": "recommended"}, "context": {"…": "…"}}
```

`items`는 아우터·상의·하의 순서다. 422 오류는 `{"error", "hint"}`(한국어 안내)를 준다: `WARDROBE_TOO_SMALL`(몸에 맞는 상의·하의가 없음), `ANCHOR_NOT_FOUND`, `ANCHOR_TOO_SMALL`, `NO_MATCH`. 400 `INVALID_REQUEST`, 415 `JSON_REQUIRED`, 503 `DATABASE_NOT_CONFIGURED`.

### 코디 테이블

| 메서드·경로 | 동작 |
| --- | --- |
| `GET /api/outfits/` | 저장한 코디(코디북), 수정 시각·ID 내림차순. 최대 20개·항목 합계 128KiB, `next`가 있으면 `?after=<next>`로 커서를 그대로 전달하여 계속 조회 |
| `POST /api/outfits/` | `{garment_ids, source: "user"\|"chat", name?, context?}`로 코디 저장. 상의·하의 각 1벌 필수, 아우터 선택. 201 |
| `GET /api/outfits/{id}/` | 코디 하나 |
| `PUT /api/outfits/{id}/` | `{saved?, name?}` 코디북 저장·이름 |
| `POST /api/outfits/{id}/feedback/` | `{feedback: "liked"\|"disliked"\|null, preference}` → `{outfit, preference}`. 새 preference를 기기에 저장한다 |

테이블: `Outfit`(id, source, saved, name, key, score, components, reasons, context, feedback, 시각)과 `OutfitItem`(outfit, garment, slot, note; 코디당 슬롯 하나). 소유자 컬럼은 user table과 함께 추가한다. 옷을 지우면 그 옷이 든 코디 항목도 지워진다.

### GET /api/weather/?lat=&lon=

Open-Meteo 현재 날씨(기본 서울): `{temp, apparent, precipitation, rain, snow, wind, label, source, band}`. 실패하면 502 `WEATHER_UNAVAILABLE`. 10분 캐시.

## 채팅 코디 수정

### POST /api/outfits/chat/

로그인한 사용자의 저장된 옷장에서 코디 수정안을 만든다. 계정 인증이 필요하며 이 요청은 코디 테이블에 저장하지 않는다.

비로그인 사용자는 `POST /api/outfits/preview/chat/`에 같은 필드와 기기에 저장한 옷의 `wardrobe` 배열을 보낸다. `wardrobe` 형식은 `/api/outfits/preview/`와 같다. 응답 이미지 주소는 `local:<옷 ID>`이며 서버 DB를 사용하지 않는다. 비로그인 채팅 코디 저장은 기기 코디북에 한다.

```json
{
  "body": {"height_cm": 170, "gender": "unspecified", "measurements": []},
  "weather": {"temp": 16, "apparent": 15, "rain": false, "snow": false},
  "garment_ids": ["현재-상의-id", "현재-하의-id"],
  "locked_ids": ["현재-하의-id"],
  "directions": ["brighter", "formal"],
  "text": "상의는 흰 셔츠로 바꿔줘",
  "preference": null,
  "seen_keys": [],
  "history": [{"role": "user", "text": "이전 요청"}, {"role": "assistant", "text": "이전 응답"}]
}
```

- `body`, 현재 `garment_ids` 필수. 날씨는 기존 추천 API와 같이 `weather` 또는 `temp`로 전달한다.
- `directions`와 `text` 중 하나 이상 필요. `text`는 최대 400자, `history`는 최대 6개(각 500자).
- 버튼 값: `brighter`, `darker`, `warmer`, `cooler`, `formal`, `casual`, `looser`, `fitted`, `colorful`, `simpler`, `different`.
- `locked_ids`는 사용자가 UI에서 직접 잠근 옷이며 현재 코디에 포함된 옷만 허용한다. 모델이 해석한 `keep_slots`는 해당 요청에만 적용하고 `locked_ids`에 추가하지 않는다. 앱도 서버 응답으로 잠금을 새로 만들지 않는다. 잠금은 서버의 후보·코디 구성 제약이며 점수에 따른 변경을 허용하지 않는다.
- 버튼만 사용하면 모델 호출이 없다. 자유 입력은 서버의 `CHAT_PROVIDER=auto|gemini|openrouter`로 해석한다. `auto`는 `OPENROUTER_API_KEY`가 있으면 OpenRouter, 그 외 Gemini를 사용한다. OpenRouter 모델은 `OPENROUTER_CHAT_MODEL`, Gemini 모델은 `GEMINI_TEXT_MODEL`로 지정한다. 키는 서버 `.env`에만 둔다.
- 모델은 변경/유지 슬롯·방향·색 제외·특정 옷·아우터 제거·확인 질문을 구조화한다. 잠금과 충돌하면 변경 전에 확인한다. 모델 실패 시 대체 해석 없이 503 `CHAT_MODEL_UNAVAILABLE`로 응답한다.

응답 공통 필드: `{status, message, outfit, changed_ids, locked_ids, unlock_ids, notes, parsed_by}`.

| status | 동작 |
| --- | --- |
| `updated` | `outfit`에 기존 코디 응답 형식의 수정안. 새 점수는 표시하지 않는다 (`score: null`). 다음 수정 기준으로 사용 |
| `unchanged` | 조건을 만족하는 다른 코디 없음. 기존 코디 유지 |
| `clarify` | `message`의 확인 질문 표시. 기존 코디 유지 |
| `unlock` | `unlock_ids`의 잠금 해제 동의를 먼저 받는다. 동의 시 동일 요청에서 해당 잠금만 빼고 재요청 |
| `undo` | 앱이 보관한 이전 코디로 복원 |

400 `INVALID_CHAT_REQUEST`, 422 `CURRENT_GARMENT_MISSING`, 429 `CHAT_BUSY`, 503 `CHAT_MODEL_UNAVAILABLE`은 메시지와 함께 같은 요청의 재시도를 제공한다.

저장은 기존 `POST /api/outfits/`에 `{garment_ids, source: "chat", name?, context: {chat_save_id: "클라이언트 UUID"}}`로 요청한다. 같은 `chat_save_id`의 재요청은 기존 코디를 반환하여 응답 유실 후 재시도로 인한 중복 저장을 방지한다. `chat_save_id`는 최대 64자다.

앱 대화는 홈 코디별로 메모리에 유지되며 뒤로 가기·재진입·화면 회전에 보존된다. 앱 프로세스 종료 후 대화 복원은 지원하지 않는다.
