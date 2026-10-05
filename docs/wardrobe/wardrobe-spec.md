# Wardrobe 데이터·API 명세

수정일: 2026-10-05 · 기준: 현재 Android·Django 구현

필드 정의는 `backend/apps/wardrobe/models.py`, 속성 검증은 `schema.py`, 치수·저장 요청 검증은 `editor.py`, 실제 API는 `urls.py`·`views.py`를 기준으로 한다. 화면은 [디자인](design.md), 흐름은 [데이터 흐름](wardrobe-flow.md), 실행은 [실행 안내](local-development.md)를 따른다.

## 1. 구현 범위

촬영 → HRNet 측정점 → Android ARCore 치수 계산 → Gemini 분석 → 사용자 수정 → MySQL 저장·조회로 구성한다. Gemini는 이름·분류·대표색만 생성한다. 소재·착용 정보·상품 사이즈·실측을 AI가 생성하지 않는다.

사용자 구분·인증, S3, 별도 초안 테이블, 옷 삭제, 초안 만료·자동 청소, 검색·페이지네이션, 추천 기능은 구현하지 않았다. 이 문서에는 미구현 API를 현재 계약으로 포함하지 않는다.

## 2. 저장 구조

MySQL `wardrobe_garment` 테이블 하나에 옷 한 벌당 한 행을 저장한다. `0001_initial`이 최종 구조를 생성한다.

| 컬럼 | Django 타입 | 의미 |
| --- | --- | --- |
| `id` | UUIDField, 기본키 | 서버 생성 옷 ID |
| `image` | FileField | `garments/UUID.jpg` 파일 경로 |
| `attributes` | JSONField | 현재 속성. 분석 시 제안값, 저장·수정 시 사용자 편집값으로 교체 |
| `dimensions` | JSONField, NULL 허용 | 실측값·측정 출처, 기본 NULL |
| `notes` | TextField | 메모, 기본 빈 문자열, API에서 2,000자 제한 |
| `saved` | BooleanField, 인덱스 | 기본 false. 확정 저장 시 true |
| `created_at` | DateTimeField | 생성 시 자동 기록 |
| `updated_at` | DateTimeField | 저장·수정 시 자동 기록 |

이름·카테고리·색상은 별도 DB 컬럼이 아니라 `attributes` 내부 키다. 최초 AI 결과는 별도 복제하지 않는다. 사진 파일은 `WARDROBE_MEDIA_ROOT` 아래에 저장하며 DB에는 경로만 보관한다. 기본 위치는 `backend/private/wardrobe-media/`다.

사진의 API 경로 `image_url`은 조회 응답에서 생성한다. 측정점 좌표·카메라 행렬·기울기·AI 응답 원문은 옷 테이블에 저장하지 않는다. 체형 정보도 이 테이블에 복제하지 않는다.

## 3. 특징 기준정보

### attributes

이름·분류는 필수다. 저장 요청에는 모든 속성 키가 있어야 하며 미입력 단일값은 null, 배열은 []다. AI가 반환하지 않은 선택 속성도 서버가 이 기본값으로 채운다. 미정 값에 추측한 기본값을 넣지 않는다.

| 필드 | 형식·제약 |
| --- | --- |
| `name` | 앞뒤 공백 제거 후 1~80자 |
| `category` | top / bottom / outerwear / shoes |
| `subcategory` | 분류에 속하는 종류 코드 또는 null. [코드표](wardrobe-types.md) 참고 |
| `colors` | 대문자 #RRGGBB 배열, 편집·저장 시 0~5개, 중복 불가 |
| `styles` | 허용 코드 배열, 최대 3개, 중복 불가 |
| `fit_type` | 디자인 핏 코드 또는 null |
| `leg_shape` | 바지 형태 코드 또는 null |
| `rise_type` | 하의 허리선 코드 또는 null |
| `skirt_shape` | 스커트 형태 코드 또는 null |

#### 스타일 — `styles`

| 코드 | 표시명 |
| --- | --- |
| `minimal` | 미니멀 |
| `casual` | 캐주얼 |
| `street` | 스트릿 |
| `classic` | 클래식 |
| `sporty` | 스포티 |
| `formal` | 포멀 |
| `workwear` | 워크웨어 |

#### 핏 — `fit_type`

| 코드 | 표시명 |
| --- | --- |
| `skinny` | 스키니 |
| `slim` | 슬림 |
| `regular` | 레귤러 |
| `loose` | 루즈 |
| `oversized` | 오버사이즈 |

#### 바지 형태 — `leg_shape`

| 코드 | 표시명 |
| --- | --- |
| `skinny` | 밀착 |
| `slim` | 슬림 |
| `straight` | 일자 |
| `tapered` | 테이퍼드 |
| `semi_wide` | 세미와이드 |
| `wide` | 와이드 |
| `bootcut` | 부츠컷 |
| `flared` | 플레어 |
| `balloon` | 벌룬 |

#### 허리선 — `rise_type`

| 코드 | 표시명 |
| --- | --- |
| `low` | 로우라이즈 |
| `mid` | 미드라이즈 |
| `high` | 하이라이즈 |

#### 스커트 형태 — `skirt_shape`

| 코드 | 표시명 |
| --- | --- |
| `straight` | 일자 |
| `a_line` | A라인 |
| `flared` | 플레어 |
| `pleated` | 플리츠 |
| `pencil` | 펜슬 |
| `other` | 기타 |

#### 분류별 선택 속성

아래 표의 허용 속성도 미입력 시 `null`이다. 허용하지 않는 속성은 반드시 `null`로 보낸다.

| 분류·종류 | 허용 속성 | 반드시 null인 속성 |
| --- | --- | --- |
| 상의·아우터 | `fit_type` | `leg_shape`, `rise_type`, `skirt_shape` |
| 하의·바지류 | `fit_type`, `leg_shape`, `rise_type` | `skirt_shape` |
| 하의·스커트 | `fit_type`, `rise_type`, `skirt_shape` | `leg_shape` |
| 하의·other 또는 종류 미입력 | `fit_type`, `rise_type` | `leg_shape`, `skirt_shape` |
| 신발 | 없음 | `fit_type`, `leg_shape`, `rise_type`, `skirt_shape` |

바지류는 `jeans`, `slacks`, `pants`, `active_pants`, `shorts`다.

`fit_type`은 옷의 디자인 속성이며 실제 사용자에게 맞는지를 판정한 값이 아니다. 상대 기장 length, pattern, 선택 소매 길이, 소재·촉감·신축성·비침·두께·계절은 속성으로 저장하지 않는다. 필요한 자유 설명은 notes에 입력한다.

### 색상

Gemini는 아래 색상표에서 대표색 1~2개를 반환해야 한다. 사용자는 색상표 또는 사진 픽셀에서 선택하여 임의 HEX를 최대 5개까지 저장할 수 있다. 입력 소문자는 대문자로 정규화하며 빈 색상 배열도 사용자 저장에서는 허용한다.

| HEX | 표시명 |
| --- | --- |
| `#202020` | 검정 |
| `#FFFFFF` | 흰색 |
| `#808080` | 회색 |
| `#FFFFF0` | 아이보리 |
| `#D6BE9A` | 베이지 |
| `#795548` | 갈색 |
| `#24344B` | 네이비 |
| `#3975C6` | 파랑 |
| `#4F7952` | 초록 |
| `#7B8052` | 카키 |
| `#C83C3C` | 빨강 |
| `#E88A3D` | 주황 |
| `#E8C547` | 노랑 |
| `#E8A0B0` | 분홍 |
| `#9165AD` | 보라 |
| `#C0C0C0` | 은색 |
| `#C9A447` | 금색 |

### dimensions

단위는 cm다. 각 치수는 value·source·method·reference 네 키를 갖는다. 값은 유한한 양수이며 소수 둘째 자리까지 반올림한다. 화면은 두 자리를 표시하지만 JSON number의 후행 0은 보장하지 않는다.

| source | 의미 |
| --- | --- |
| `arcore_manual` | 사용자가 지정한 점으로 AR 계산 |
| `arcore_assisted` | 자동 제안점으로 AR 계산 |
| `user_measured` | 사용자가 숫자를 입력·수정 |
| `product_chart` | 상품 치수표 출처 코드 |

- method: 아래 항목별 코드 또는 unspecified. 소매길이에는 center_back_via_shoulder_to_cuff도 허용한다.
- reference: null 또는 최대 500자 문자열. 현재 앱은 AR 출처 설명을 만들며, 숫자를 수정하면 null로 보낸다. 치수표 수집·별도 출처 입력 화면은 없다.
- 치수를 사용하지 않으면 dimensions=null이다. 적용 가능한 치수 키를 생략하거나 null로 보내면 해당 항목은 저장하지 않는다. 모든 항목이 비어 있으면 서버가 dimensions=null로 정규화한다.
- 스커트는 허리·엉덩이·총장만 허용한다. 나머지 하의는 아래 하의 치수를 허용한다. 신발은 의류 치수 항목을 허용하지 않는다.
- 적용 불가능한 키, 숫자 대신 문자열·Boolean, 0·음수·NaN·Infinity는 거부한다.

상의·아우터:

| 필드 | 표시명 | method |
| --- | --- | --- |
| `shoulder_width` | 어깨너비 | `flat_shoulder_seam_to_seam` |
| `chest_width_half` | 가슴 단면 | `flat_underarm_to_underarm` |
| `total_length` | 총장 | `back_neck_to_hem` |
| `sleeve_length` | 소매길이 | `shoulder_seam_to_cuff` |
| `hem_width_half` | 밑단 단면 | `flat_body_hem` |
| `cuff_width_half` | 소매끝 | `flat_sleeve_opening` |
| `armhole_straight` | 암홀 | `armhole_top_to_underarm_straight` |

하의:

| 필드 | 표시명 | method |
| --- | --- | --- |
| `waist_width_half` | 허리 단면 | `flat_waistband_relaxed` |
| `hip_width_half` | 엉덩이 단면 | `flat_hip_max_width` |
| `thigh_width_half` | 허벅지 단면 | `flat_thigh_at_crotch` |
| `rise_front` | 앞밑위 | `front_waistband_along_rise_to_crotch` |
| `inseam` | 인심 | `crotch_along_inner_seam_to_hem` |
| `total_length` | 총장 | `waistband_along_side_to_hem` |
| `hem_opening` | 한쪽 밑단 | `flat_single_leg_hem` |

단면은 둘레와 구분하며, 암홀은 직선 측정 기준이다. 총장·앞밑위·인심의 측정 경로가 다르므로 총장=앞밑위+인심으로 검증하지 않는다. AR 값은 인식한 바닥 평면을 기준으로 한 추정값이며 실제 봉제선 길이나 착용 적합성을 보장하지 않는다.

## 4. API 계약

모든 경로는 `/api/wardrobe/` 아래다. 현재 인증·소유권 검증은 없다.

| 메서드·경로 | 동작·정상 응답 |
| --- | --- |
| POST analyze/ | 사진 분석 후 초안 생성, 200 |
| POST landmarks/?garment=종류 | HRNet 측정점 탐지, 200 |
| GET options/ | 필드 표시명·허용 코드·색상표·치수 기준, 200 |
| GET items/ | saved=true인 옷 목록, 200 |
| GET items/{id}/ | 초안 또는 저장된 옷 조회, 200 |
| PUT items/{id}/ | 동일 행의 편집값 전체 교체·확정 저장, 200 |
| GET items/{id}/image/ | saved=true인 옷의 JPEG, 200 |

### 분석

본문은 JPEG·PNG·WebP 이미지 바이트이며 해당 Content-Type을 지정한다. 최대 5 MiB·1,200만 픽셀·단일 프레임만 허용한다. EXIF 방향 적용 후 긴 변 최대 1536px의 JPEG로 정규화하고 메타데이터를 제외한다.

서버는 Gemini gemini-3.1-flash-lite를 호출한다. 성공 시 사진을 파일 저장소에 기록하고 Garment를 saved=false로 생성한다. AR 치수는 이 요청으로 전송하지 않는다. 응답은 id·model·attributes 세 필드다. 한국어 표시명은 options 응답을 사용한다.

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "model": "gemini-3.1-flash-lite",
  "attributes": {
    "category": "top",
    "subcategory": null,
    "styles": [],
    "fit_type": null,
    "leg_shape": null,
    "rise_type": null,
    "skirt_shape": null,
    "name": "흰색 티셔츠",
    "colors": [
      "#FFFFFF"
    ]
  }
}
```

### 편집·확정 저장

PUT은 attributes·dimensions·notes 세 필드를 모두 요구하며, 다른 최상위 키는 거부한다. 최대 요청 크기는 16 KiB다. 부분 수정 PATCH는 제공하지 않는다. 새 행을 만들지 않고 요청 ID의 행에 값을 반영하며 saved=true로 바꾼다. 같은 ID로 반복 저장하면 한 벌을 유지하고 마지막 요청값이 반영된다.

PUT 요청 예시:

```json
{
  "attributes": {
    "category": "top",
    "subcategory": "tshirt",
    "styles": [],
    "fit_type": null,
    "leg_shape": null,
    "rise_type": null,
    "skirt_shape": null,
    "name": "흰색 티셔츠",
    "colors": [
      "#FFFFFF"
    ]
  },
  "dimensions": {
    "unit": "cm",
    "shoulder_width": {
      "value": 45.32,
      "source": "arcore_assisted",
      "method": "flat_shoulder_seam_to_seam",
      "reference": null
    }
  },
  "notes": "찬물 세탁"
}
```

개별 조회·PUT 응답 예시:

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "attributes": {
    "category": "top",
    "subcategory": "tshirt",
    "styles": [],
    "fit_type": null,
    "leg_shape": null,
    "rise_type": null,
    "skirt_shape": null,
    "name": "흰색 티셔츠",
    "colors": [
      "#FFFFFF"
    ]
  },
  "dimensions": {
    "unit": "cm",
    "shoulder_width": {
      "value": 45.32,
      "source": "arcore_assisted",
      "method": "flat_shoulder_seam_to_seam",
      "reference": null
    }
  },
  "notes": "찬물 세탁",
  "image_url": "/api/wardrobe/items/11111111-1111-4111-8111-111111111111/image/",
  "saved": true
}
```

생성·수정 시각은 DB에만 저장하며 현재 위 응답에는 포함하지 않는다. 목록 응답은 `{"items": [...]}`이고 saved=true를 생성 시각 내림차순으로 반환한다. 검색·필터·페이지네이션 쿼리는 처리하지 않는다. 앱의 카테고리 필터는 받은 목록에서 처리한다.

### 측정점

본문은 JPEG·PNG·WebP 바이트이며 최대 1 MiB·100만 픽셀이다. `garment`는 다음 중 하나다.

| 코드 | 촬영 종류 |
| --- | --- |
| `short_sleeve_top` | 반소매 상의 |
| `long_sleeve_top` | 긴소매 상의 |
| `short_sleeve_outerwear` | 반소매 아우터 |
| `long_sleeve_outerwear` | 긴소매 아우터 |
| `trousers` | 긴바지 |
| `shorts` | 반바지 |
| `skirt` | 스커트 |

| 응답 필드 | 의미 |
| --- | --- |
| `model` | 측정점 모델 ID |
| `garment` | 요청한 촬영 종류 |
| `elapsed_ms` | 서버 처리 시간, 밀리초 |
| `points` | 종류 내 1부터 시작하는 `id`, 원본 사진 기준 `x`·`y`, heatmap `score` |
| `suggestions` | 치수 키별 측정 경로. 순서 있는 `[x,y]` 배열 |

좌표는 사진 좌상단을 원점으로 하는 0~1 값이다. 앱은 경로당 2~8개 점을 받으며 찾지 못한 항목은 생략한다. 빈 `suggestions`도 정상이고, `score`는 정확도 확률이 아니다.

`landmarks.py`의 `GARMENTS`는 DeepFashion2 종류별 heatmap 채널 범위와 측정 경로를 정의한다. 채널 범위는 0부터 시작하고 끝 인덱스를 제외하며, 경로의 점 ID는 종류 내 1부터 시작한다. 허벅지 단면은 가랑이 점을 지나고 허리선에 평행한 직선과 바깥 다리 윤곽의 교점으로 계산한다. 이 교점은 HRNet이 직접 예측한 추가 점이 아니다.

좌표는 letterbox를 역변환한 원본 사진 기준이다. HRNet은 cm를 계산하지 않고 Android가 동일 프레임의 AR 카메라·평면 정보로 길이를 계산한다. 누끼 처리와 별도 미리보기 이미지는 없다. 탐지 요청·결과는 서버 DB에 저장하지 않는다.

## 5. 오류·동시 처리

분석·편집·측정점에서 직접 처리하는 오류는 `{"error": "오류코드"}`다. 존재하지 않는 ID 등의 Django 기본 404·405 응답은 이 JSON 형식을 보장하지 않는다.

| 상태 | 코드 예시·조건 |
| --- | --- |
| 400 | INVALID_IMAGE, INVALID_GARMENT, INVALID_GARMENT_TYPE |
| 413 | IMAGE_TOO_LARGE, BODY_TOO_LARGE |
| 415 | IMAGE_REQUIRED, JSON_REQUIRED |
| 422 | IMAGE_NO_GARMENT, IMAGE_MULTIPLE, IMAGE_UNSUPPORTED, IMAGE_UNCLEAR |
| 429 | AI_BUSY, AI_RATE_LIMITED |
| 502 | AI_PROVIDER_ERROR, AI_UNAVAILABLE, AI_INVALID_RESPONSE |
| 503 | DATABASE_NOT_CONFIGURED, AI_NOT_CONFIGURED, AI_AUTH_FAILED, LANDMARK_NOT_CONFIGURED, LANDMARK_FAILED |
| 504 | AI_TIMEOUT |

DB 미설정이면 분석·목록·조회·수정·사진 조회를 차단한다. options와 landmarks는 DB 없이 실행한다. DB 미설정 검사는 연결 장애나 모든 DB 예외를 처리하는 기능은 아니다.

Gemini 분석과 HRNet 탐지는 같은 프로세스의 세마포어 한 개를 공유한다. 처리 중인 요청이 있으면 대기열에 쌓지 않고 429를 반환한다. Gemini 소켓 timeout은 30초, Android 분석 연결·읽기 timeout은 5초·45초다. 분산 잠금이나 자동 재분석은 없다.

분석 성공 후 사진 저장·DB 저장을 시도하며 DB 저장 실패 시 해당 사진 삭제를 시도한다. 외부 API 오류 원문과 키를 클라이언트에 반환하지 않는다. 분석 실패 전용 DB 상태·분석 응답 유실 복구·초안 만료는 구현하지 않았다.

## 6. 변경·검증 기준

필드·허용값 변경 시 models.py·schema.py·editor.py, Android 편집·저장 처리, 테스트와 이 문서를 함께 확인한다. 사용자 입력 검증, 분석 응답, 초안 목록 제외, 동일 ID 재저장, JSON 재조회와 사진 조회를 테스트한다. 마이그레이션은 새 DB에서 초기 구조가 모델과 일치하는지 검증한다.

인증·사용자 소유권, S3, 삭제·초안 청소, 검색·페이지네이션, 추천 연동은 별도 합의·구현이 필요한 범위다. 현재 명세에 해당 기능이 동작하는 것으로 간주하지 않는다.
