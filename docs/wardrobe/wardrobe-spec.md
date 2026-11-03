# Wardrobe 데이터·API 명세

수정일: 2026-10-05 · 기준: 현재 Android·Django 구현

필드 정의는 `backend/apps/wardrobe/models.py`, 속성 검증은 `schema.py`, 치수·저장 요청 검증은 `editor.py`, 실제 API는 `urls.py`·`views.py`를 기준으로 한다. 화면은 [디자인](design.md), 흐름은 [데이터 흐름](wardrobe-flow.md), 실행은 [실행 안내](local-development.md)를 따른다.

## 1. 구현 범위

촬영 → HRNet 측정점 → Android ARCore 치수 계산 → Gemini 분석 → 사용자 수정 → MySQL 저장·조회로 구성한다. Gemini는 이름·분류·대표색을 생성하고 세부 종류·스타일을 제안한다. 추천용 details에는 겉으로 분명한 소재만 제안할 수 있다. 혼용률·상품 사이즈·실측 cm는 AI가 생성하지 않는다. 본인 착용 사진 경로에서는 별도 wearing 관찰로 보이는 핏·기장을 제안하며, 서버 체형 기반 cm 추정은 실측과 분리한다.

옷 분석·측정점·옵션·라벨·저장·조회·사진 요청을 포함한 모든 서비스 API는 [로그인 API](../login/README.md)의 Bearer 토큰으로 인증하며 본인 소유의 옷만 처리한다. Android는 로그인한 계정의 서버 API와 계정별 캐시를 사용한다. 게스트 분석 API는 제거했다. 기존 데이터 가져오기는 로그인 후 동의·충돌 확인·재시도 기능으로 유지한다. S3, 별도 초안 테이블, 옷 삭제, 초안 만료·자동 청소, 검색은 이번 범위 밖이다. 계정 목록은 기존 커서 페이지네이션을 유지한다.

## 2. 저장 구조

MySQL `wardrobe_garment` 테이블 하나에 옷 한 벌당 한 행을 저장한다. `0001_initial`이 테이블을 만들고 `0002_garment_labels`가 추천 라벨 컬럼을 더한다.
`0003_garment_user`가 소유자 외래 키를 추가하고 `0004_alter_garment_user`가 소유자 없는 기존 행을 제거한 뒤 외래 키를 필수로 전환한다.

| 컬럼 | Django 타입 | 의미 |
| --- | --- | --- |
| `id` | UUIDField, 기본키 | 서버 생성 옷 ID |
| `user_id` | ForeignKey, 필수 | 인증된 소유 사용자. 요청 본문으로 변경 불가 |
| `image` | FileField | `garments/UUID.jpg` 파일 경로 |
| `image_source` | CharField | `garment_photo`(기존 사진) / `worn_crop`(착용 원본 크롭) / `ai_flatlay`(사용자가 승인한 AI 재구성) |
| `attributes` | JSONField | 현재 속성. 분석 시 제안값, 저장·수정 시 사용자 편집값으로 교체 |
| `dimensions` | JSONField, NULL 허용 | 실측값·측정 출처, 기본 NULL |
| `estimated_dimensions` | JSONField, NULL 허용 | 서버 계산 참고 치수·범위·근거·추정 출처. 읽기 전용이며 실측으로 전달하지 않음 |
| `notes` | TextField | 메모, 기본 빈 문자열, API에서 2,000자 제한 |
| `labels` | JSONField, NULL 허용 | 내 몸 기준 추천 라벨(핏·기장·소매·계절·무늬·소재 등). 사용자가 확인한 값. 신발은 NULL |
| `saved` | BooleanField, 인덱스 | 기본 false. 확정 저장 시 true |
| `created_at` | DateTimeField | 생성 시 자동 기록 |
| `updated_at` | DateTimeField | 저장·수정 시 자동 기록 |

이름·카테고리·색상은 별도 DB 컬럼이 아니라 `attributes` 내부 키다. 최초 AI 결과는 별도 복제하지 않는다. 사진 파일은 `WARDROBE_MEDIA_ROOT` 아래에 저장하며 DB에는 경로만 보관한다. 기본 위치는 `backend/private/wardrobe-media/`다.

사진의 API 경로 `image_url`은 조회 응답에서 생성한다. 측정점 좌표·카메라 행렬·기울기·AI 응답 원문은 옷 테이블에 저장하지 않는다. 체형 정보도 이 테이블에 복제하지 않는다.

### 착용 사진 한 벌 등록

- `POST /api/wardrobe/analyze-worn/?category=top|bottom|outerwear`: Bearer 인증 후 사용자가 지정한 영역의 JPEG·PNG·WebP를 받는다. 크기 제한·정규화·기존 속성 스키마는 일반 분석과 같다. category는 필수이며 신발은 지원하지 않는다.
- 전용 분석 지시는 선택한 분류의 한 벌만 식별하고 다른 옷·가방의 특징을 제외한다. 사람이 여럿이거나 대상 후보가 여럿이면 `IMAGE_MULTIPLE`, 가림·흐림으로 대상을 식별할 수 없으면 `IMAGE_UNCLEAR`, 응답 분류가 대상과 다르면 `IMAGE_TARGET_UNCLEAR`로 422를 반환한다. 이는 모델 판정이며 검증된 분할 정확도를 보장하지 않는다.
- 성공 응답은 기존 `id`, `model`, `attributes`, `details`와 직렬화된 초안 정보를 포함한다. 이 단계는 원본 영역만 분석하고 생성 결과를 분석하지 않는다. `request_id` UUID를 함께 전송하면 계정별 동일 ID 재전송은 최초 초안과 분석 결과를 재사용한다.
- `worn_crop` 초안 사진은 소유자만 기존 사진 API로 읽을 수 있다. 일반 `garment_photo` 초안 사진의 기존 404 정책은 유지한다. 목록에는 두 종류 모두 확정 저장된 항목만 표시한다.
- `POST items/{id}/generation/`의 JSON은 `{request_id: UUID}`이다. CPU SegFormer로 대상 옷 픽셀을 분리하고 원본 영역·분할 이미지·마스크 세 장을 OpenRouter 이미지 API에 참조로 전송한다. 한 번에 한 장만 생성하며 자동 재시도·모델 대체 호출은 없다. 키와 모델은 서버 설정만 사용한다.
- `GET items/{id}/generation/`은 추가 생성 없이 `status`(pending/running/ready/failed), `decision`, 오류 코드, 비교 이미지 URL, 모델과 마지막 요청 비용(확인 가능한 경우)을 반환한다. 동일 생성 ID의 POST나 실행 중 POST는 새 요청을 시작하지 않는다. 새로운 ID의 명시적 재생성은 비용이 추가될 수 있다. 3분 이상 남은 실행 상태는 중단 오류로 전환하며 자동 재생성하지 않는다.
- `POST items/{id}/review/`는 `{choice: generated|crop|reject}`를 받는다. generated는 준비된 생성 이미지를 선택하고 `ai_flatlay`, crop은 원본 영역을 명시적으로 선택해 `worn_crop`, reject는 거절 상태로 유지한다. 승인만으로 `saved=true`가 되지 않는다.
- 비교 사진은 `items/{id}/worn-image/original|segmented|candidate/`로 소유자만 읽는다. 원본·분할·생성 파일, 원본 분석 details, 상태·선택·마지막 요청 비용은 `WornGeneration` 초안에 보관한다. 일반 사진과 기존 확정 데이터는 변경하지 않는다.
- 기존 `PUT items/{id}/`를 재사용한다. 생성 초안은 generated 또는 crop 선택이 있어야 확정 저장된다. 두 출처 모두 `dimensions=null`과 `labels.confirmed=true`가 필요하며 모르는 핏·기장은 null을 허용한다. 생성 이미지에만 있는 주머니·단추 등을 자동 특징으로 추가하지 않는다.
- 생성 오류는 할당량(`GENERATION_RATE_LIMITED`), 크레딧 부족, 시간 초과, 제공자 오류, 모델 설정 오류 등을 구분한다. 오류 발생 시 원본 크롭으로 자동 전환하거나 저장하지 않는다. 사용자는 다른 사진 또는 명시적 원본 크롭을 선택할 수 있다.
- 화면에서 AI 재구성 또는 원본 크롭 출처를 구분한다. 가려진 부분과 주머니·단추는 추정일 수 있으며, 가림이 큰 사진에는 다른 각도를 권장한다. 기존 AR·한 벌 분석의 다중 의류 거절 지시는 변경하지 않는다.

### 착용 사진 자동 후보·일괄 저장

착용 관찰을 포함하는 분석 요청은 Gemini의 중첩 스키마 복잡도 제한을 피하도록 제공자용 JSON Schema에서 배열 길이와 숫자 범위 제약을 생략한다. 후보 최대 3개, 정수 좌표 0~1000, 최소 영역, 카테고리별 라벨 등의 검증은 서버에서 유지한다. HTTP 400은 `AI_REQUEST_INVALID`, 모델 경로 404는 `AI_MODEL_UNAVAILABLE`로 구분하고, 앱에서 사진 속 옷을 식별하지 못한 오류로 안내하지 않는다. 실패 요청의 재조회는 추가 분석을 실행하지 않으며 사진을 다시 선택하면 새 요청으로 분석한다.

기본 앱 진입은 자동 후보 방식이며 앞의 한 벌 직접 지정 API는 대체 경로로 유지한다. 다음 경로도 모두 Bearer 인증·소유자 제한·`Cache-Control: no-store`를 적용한다.

| 경로 | 본문·동작 |
| --- | --- |
| `POST worn-photos/?request_id=<UUID>` | 기존 사진과 같은 한도의 이미지 바이트. 원본 Gemini 분석 1회로 후보·원본 특징을 제안한다. 동일 계정·요청 ID는 분석을 다시 호출하지 않는다. |
| `GET worn-photos/{id}/` | `id`, `status`(detecting/ready/failed), `error`, `model`, `image_url`, `candidates` 조회. 3분 이상 detecting이면 중단 오류로 전환한다. |
| `GET worn-photos/{id}/image/` | 계정 소유 전체 원본. 공개 media URL을 제공하지 않는다. |
| `GET worn-photos/{id}/candidates/{candidate_id}/image/` | 후보 사각형의 실제 원본 픽셀. 분할·펼친 이미지가 아니다. |
| `POST worn-photos/{id}/prepare/` | `{candidate_ids: [UUID, ...]}`. 선택한 후보만 원본 크롭·Garment·WornGeneration 초안으로 준비한다. 재전송은 같은 ID를 반환하며 Gemini·이미지 생성을 호출하지 않는다. |
| `POST worn-photos/{id}/save/` | `{items: [{id, image_choice, attributes, dimensions, notes, labels}, ...]}`. `image_choice=generated/crop`을 벌마다 명시한다. 모두 검증한 뒤 한 트랜잭션으로 확정한다. 같은 저장의 재전송은 이미 저장된 행을 다시 만들거나 덮어쓰지 않는다. |

후보는 `{id, bounds, attributes, details, crop_url}`이다. `bounds=[left,top,right,bottom]`은 EXIF 방향 보정·정규화한 원본 기준 0~1000 좌표다. Gemini에는 [공식 문서의 box_2d 순서](https://ai.google.dev/gemini-api/docs/image-understanding)인 `[ymin,xmin,ymax,xmax]`로 요청하고, 서버가 앱의 x/y 순서로 변환한다. 상의(`top`)·하의(`bottom`)·아우터(`outerwear`) 각 최대 한 벌, 전체 최대 세 벌만 허용한다. 두 명 이상이면 `IMAGE_MULTIPLE_PEOPLE`, 사람 없음이면 `IMAGE_NO_PERSON`, 식별 가능한 후보 없음이면 `IMAGE_TARGET_UNCLEAR`를 failed 상태로 보관한다. 액세서리·신발·타이츠·원피스와 같은 분류의 여러 겹은 이번 후보 범위에 포함하지 않는다. 모델의 사각형 제안은 분할 정확도나 실제 소유 의류의 숨은 형태를 보장하지 않는다.

이름·색·종류·디테일은 원본 후보 분석값이며 생성 이미지를 다시 특징 분석하지 않는다. 겹친 옷의 특징 혼합 가능성을 비교 화면에서 확인한다. SegFormer의 상체 클래스는 안쪽 상의와 아우터를 합칠 수 있으므로 후보 사각형·분류·원본 특징을 함께 생성 지시에 전달한다. 분할 이미지를 정답 마스크로 취급하지 않는다.

`WornPhoto`는 계정 소유 원본·후보·착용 관찰·분석 상태·요청 ID를 보관한다. 선택된 후보와 생성 초안은 사진·후보 고유 제약으로 연결한다. 초안의 추천 라벨은 기존 계산기를 재사용하며 원본에서 식별되는 핏·기장만 `본인 착용 사진에서 추정` 근거로 제안한다. 식별되지 않는 값은 null이다. 단일 생성 API를 선택 수만큼 호출하므로 이미지 생성 호출 수·비용도 선택 수에 비례한다. 앱은 첫 호출 전 수량을 안내하고, 실패를 자동 재생성하지 않는다.

일괄 저장은 포함한 항목의 소유 사진 연결·명시적 이미지 선택·생성 완료 상태·`dimensions=null`·라벨 형식·`confirmed=true`를 확인한다. 핏·기장 미정은 실패 사유가 아니다. 앱의 등록 체크는 항목 포함 여부 하나이며, 최종 등록 버튼이 선택한 표시 이미지 승인도 겸한다. 하나라도 검증에 실패하면 새로 저장되는 항목이 없다. 실패한 항목을 제외해 나머지를 함께 저장할 수 있고, 원본 크롭 대체는 별도 확인을 거친다. 앱 프로세스 종료 후 미저장 초안 자동 복원과 서버 초안 자동 만료는 아직 제공하지 않는다.

### 착용 사진 참고 치수

전제는 사진 속 착용자가 계정 본인이라는 것이다. 원본 분석의 `wearing`에는 분류별 허용 어휘의 핏·기장, 가림 정도, 허리선 식별 여부, 정면 직립 여부, 식별 가능한 전신 세로 구간과 옷 길이 세로 구간만 포함한다. 사진에 없는 끝점은 추론하지 않는다. 이 관찰은 기존 Gemini 요청에 포함하며 별도 API 호출을 만들지 않는다. 생성 이미지와 생성 마스크의 재구성 형태는 치수 입력으로 사용하지 않는다.

서버는 `request.user.body_profile`에서 입력된 키·가슴·허리·엉덩이 값만 읽는다. 다른 계정 체형, 선호 핏, 평균 체형, 기본 키로 대체하지 않는다. 가슴·엉덩이 단면은 `recommendation/fit.py`의 분류별 핏 여유량 범위를 체형 둘레에 적용해 절반으로 계산하고 불확실성 여유를 추가한다. 허리 단면은 허리선이 식별되는 경우만 계산한다. 총장은 전신·옷 길이 양끝이 보이고 정면 직립으로 관찰될 때만 `계정 키 × 사진 속 옷 세로 길이 / 전신 세로 길이`로 계산한다. 단면과 총장은 정수 cm의 중심값·넓은 예상 범위로 보관한다. 어깨·암홀·숨은 소매·인심 등을 일괄 보충하지 않는다.

아래는 가상의 가슴둘레 90cm·키 165cm 사용자, 루즈 상의와 전신의 40%로 보이는 길이에 대한 예시다.

```json
{
  "dimensions": null,
  "estimated_dimensions": {
    "unit": "cm",
    "source": "body_photo_estimate",
    "method": "body_photo_v1",
    "quality": "rough",
    "assumption": "photo_of_owner",
    "category": "top",
    "subcategory": "tshirt",
    "fit": "루즈",
    "length": null,
    "body_basis": {"chest": 90, "height": 165},
    "measurements": {
      "chest_width_half": {"value": 53, "min": 49, "max": 57},
      "total_length": {"value": 66, "min": 53, "max": 79}
    }
  }
}
```

예상 범위는 조정 가능한 휴리스틱이며 표본으로 검증된 오차 범위·통계적 신뢰구간이 아니다. 체형이나 필요한 사진 단서가 없으면 해당 항목을 생략하고, 남은 항목이 없으면 `estimated_dimensions=null`이다. 화면에는 `추정 치수`, `약 Ncm · 추정`, 예상 범위를 읽기 전용으로 표시한다. `dimensions`의 기존 AR·사용자 실측 출처는 그대로 유지하며 추정 출처를 이 컬럼의 실측 source로 허용하지 않는다. 추정값을 추천의 실측 기반 too_small 판정이나 라벨 재계산의 dimensions로 전달하지 않는다.

추정값은 서버 초안에 저장하고 일반 조회·확정 저장 응답에서 읽는다. 클라이언트의 추정값 주입·수정 요청은 거절한다. 분류·세부 종류·핏·기장을 변경하면 기존 추정은 화면에서 숨기고 서버에서도 무효화한다. 체형 변경만으로 과거 초안을 자동 재계산하거나 기존 사진을 유료 재분석하지 않는다. 이전 등록 옷의 실측·사진·추정 없는 상태도 보존한다.

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

Gemini는 아래 색상표에서 대표색 1~2개를 반환해야 한다. 사용자는 색상표 또는 사진 픽셀에서 선택하여 임의 HEX를 최대 5개까지 저장할 수 있다. 입력 소문자는 대문자로 정규화하며 빈 색상 배열도 사용자 저장에서는 허용한다. 추천에는 배열의 1번을 주색상, 2번을 서브색상으로 사용하고 3번 이후는 제외한다. `attributes.colors`의 최대 5개 원본은 유지한다. 서로 다른 HEX 두 개가 같은 K-Fashion 색상으로 변환되어도 두 위치를 유지하며 3번 색상을 끌어오지 않는다.

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
| GET options/ | 필드 표시명·허용 코드·색상표·치수 기준·추천 라벨 선택지(`label_options`), 200 |
| POST labels/ | 치수·체형(선택)·촬영 종류로 추천 라벨 계산(저장 안 함, DB 불필요), 200 |
| GET items/ | saved=true인 옷 목록, 200 |
| GET items/{id}/ | 초안 또는 저장된 옷 조회, 200 |
| PUT items/{id}/ | 동일 행의 편집값 전체 교체·확정 저장, 200 |
| GET items/{id}/image/ | saved=true인 옷의 JPEG, 200 |

### 분석

본문은 JPEG·PNG·WebP 이미지 바이트이며 해당 Content-Type을 지정한다. 최대 5 MiB·1,200만 픽셀·단일 프레임만 허용한다. EXIF 방향 적용 후 긴 변 최대 1536px의 JPEG로 정규화하고 메타데이터를 제외한다.

서버는 Gemini gemini-3.1-flash-lite를 호출한다. 성공 시 사진을 파일 저장소에 기록하고 Garment를 saved=false로 생성한다. AR 치수는 이 요청으로 전송하지 않는다. 선택 쿼리 `garment`(측정 화면의 종류: short_sleeve_top·long_sleeve_top·short_sleeve_outerwear·long_sleeve_outerwear·trousers·shorts·skirt)는 AI에 힌트로만 전달한다. 상의·아우터는 앞면 사진을 보낸다. 응답은 id·model·attributes·details 네 필드다. attributes의 subcategory·styles는 AI 제안이다. details는 추천 라벨용 무늬(print)·넥라인·옷깃·디테일(design_details, 최대 3)·소재(materials, 겉으로 분명할 때만 최대 2) 제안(K-Fashion 값, 하의는 넥라인·옷깃 없음, 신발·불명확하면 null)이며 attributes와 DB에는 넣지 않는다. 한국어 표시명은 options 응답을 사용한다.

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

PUT은 attributes·dimensions·notes 세 필드를 모두 요구하고 labels는 선택이다(없으면 기존 라벨 유지, 신발로 바꾸면 비움). 그 밖의 최상위 키는 거부한다. labels 값은 `recommendation/labeling.py`의 `validate_labels`로 검증한다. `kf_category`는 해당 분류의 K-Fashion 카테고리 또는 null만 허용한다. `kf_colors`는 순서를 갖는 최대 2개이며 동일한 색상명 두 개도 허용한다. 최대 요청 크기는 16 KiB다. 부분 수정 PATCH는 제공하지 않는다. 새 행을 만들지 않고 요청 ID의 행에 값을 반영하며 saved=true로 바꾼다. 같은 ID로 반복 저장하면 한 벌을 유지하고 마지막 요청값이 반영된다.

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

생성·수정 시각은 DB에만 저장하며 현재 위 응답에는 포함하지 않는다. 계정 목록 응답은 `{"items": [...], "next": "커서 문자열 또는 null"}`이며 saved=true를 생성 시각·ID 내림차순으로 반환한다. 다음 페이지는 `?after=<next>`로 조회하며 커서를 해석하거나 수정하지 않는다. 페이지당 최대 20개·항목 JSON 합계 128KiB이며, 앱은 전체 페이지 수신 후 캐시를 교체한다. 검색·서버 필터는 처리하지 않으며 앱의 카테고리 필터는 받은 목록에서 처리한다. 코디에 연결된 옷의 분류 변경은 409 `GARMENT_CATEGORY_IN_USE`로 거절한다. 상세 계약은 [로그인 문서](../login/README.md)를 따른다.

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

인증·사용자 소유권·계정 목록 분할 조회는 로그인 피처에서 구현했다. S3, 삭제·초안 청소, 검색은 별도 구현이 필요한 범위다. 추천 연동은 [추천 문서](../recommendation/README.md)를 따른다.
