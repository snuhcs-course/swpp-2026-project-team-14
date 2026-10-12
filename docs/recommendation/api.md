# 추천 API

모든 요청·응답은 JSON(UTF-8)이며 인증은 아직 없다. 체형은 요청의 `body`로 받는다(앱의 `BodyProfileJson` 형식 그대로, 또는 `{height, chest, waist, hip, shoulder, inseam, …}` cm 값). 서버 쪽에서 체형을 읽는 곳은 `apps/body_profiles/profile.py`의 `body_from_request()` 하나다.

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
| `GET /api/outfits/` | 저장한 코디(코디북), 최근 순 |
| `POST /api/outfits/` | `{garment_ids, source: "user"\|"chat", name?, context?}`로 코디 저장. 상의·하의 각 1벌 필수, 아우터 선택. 201 |
| `GET /api/outfits/{id}/` | 코디 하나 |
| `PUT /api/outfits/{id}/` | `{saved?, name?}` 코디북 저장·이름 |
| `POST /api/outfits/{id}/feedback/` | `{feedback: "liked"\|"disliked"\|null, preference}` → `{outfit, preference}`. 새 preference를 기기에 저장한다 |

테이블: `Outfit`(id, source, saved, name, key, score, components, reasons, context, feedback, 시각)과 `OutfitItem`(outfit, garment, slot, note; 코디당 슬롯 하나). 소유자 컬럼은 user table과 함께 추가한다. 옷을 지우면 그 옷이 든 코디 항목도 지워진다.

### GET /api/weather/?lat=&lon=

Open-Meteo 현재 날씨(기본 서울): `{temp, apparent, precipitation, rain, snow, wind, label, source, band}`. 실패하면 502 `WEATHER_UNAVAILABLE`. 10분 캐시.
