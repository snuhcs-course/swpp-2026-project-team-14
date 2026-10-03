# Wardrobe 기준정보·데이터·API 설계

작성일: 2026-09-26 · 상태: 팀 합의 전 제안 v0.3

## 문서 구성

- 옷 종류 코드·표시명: [옷 종류 사전](wardrobe-types.md)
- 추천 속성·측정 기준: [3. 특징 기준정보](#3-특징-기준정보), 특히 3.4 핏·형태, 3.6 실측, 3.8 추천 활용
- 데이터 모델·입력 규칙: [4. 최종 옷 레코드](#4-최종-옷-레코드)
- API 계약·JSON 예시: [6. API 초안](#6-api-초안)
- 입력 화면: [화면 설계](design.md)
- 처리 순서·실패 복구·현재 구현 범위: [등록 데이터 흐름](wardrobe-flow.md)

2026-10-03 로컬 변경: Gemini 자동 분석은 `name`, `category`, `colors`만 요청한다. `pattern`과 상대 기장 `length`는 저장·편집 스키마에서 제거한다. 길이는 `dimensions.total_length` 등 실측으로 관리한다. 나머지 외관 필드는 사용자 선택 입력으로 유지하며 AI가 생성하지 않는다. 색상 키는 기존 데이터와 호환되는 `colors` 배열을 유지한다.

주요 데이터 구분: `fit_type`은 의류의 디자인상 여유감, `dimensions`는 출처가 명시된 실측 치수, 소재·착용 특성은 사용자 선택 입력으로 관리한다.

## 1. 적용 범위

로컬 구현 예외: `POST /api/wardrobe/analyze/`는 Gemini `gemini-3.1-flash-lite`로 사진의 시각적 특징을 분석한다. 아래 정식 API 초안과 구분되는 개인 개발 API다. 분석 결과와 사진 경로는 MySQL 초안에 저장하고 사진 파일은 `.local/wardrobe-media/`에 저장한다. `saved=false` 초안은 옷장 목록에서 제외한다. 상세 실행은 [로컬 환경](local-development.md)을 따른다.

요청은 JPEG/PNG/WebP 이미지 바이트와 해당 Content-Type이다. 최대 5 MiB·1,200만 픽셀·단일 프레임만 허용하고 긴 변 1536px 이하 JPEG로 변환하여 메타데이터를 제외한다. 응답은 초안 `id`, `model`, 허용된 시각적 필드만 담는 `attributes`, 한글 `display` 목록이다. AR dimensions는 이 요청에 보내지 않으며 AI 응답으로 덮어쓰지 않는다. 종류가 AR 선택과 다르면 앱에서 확인 문구를 표시한다.

Gemini에는 name·category·colors 전용 JSON Schema를 지정하고 서버가 필드·enum을 재검증한다. 누락·추가 필드, 치수·소재·신축성 등 금지 필드, 잘못된 분류 조합은 거부한다. 옷 없음·여러 벌·미지원 종류·판별 불가는 422, 잘못된 이미지는 400/413/415, 키 미설정은 503, 요청 제한은 429, 잘못된 공급자 응답은 502, 시간 초과는 504다. 외부 응답 원문·키·이미지는 로그나 API 오류에 포함하지 않는다. 실호출 품질·지연시간은 아직 검증 전이다.

[design.md](design.md)의 옷장 화면을 구현하기 위한 공통 기준이다. local-dev/에는 분석·편집·MySQL 저장·조회가 구현되어 있다. 아래 정식 계약의 사용자 식별·S3·분리된 초안 모델은 미구현이며 팀 공통 BE와 통합 시 적용한다. 아래 경로·값·제한은 구현 계약을 논의하기 위한 초안이다.

핵심 처리 흐름은 `사진 한 장 → 특징 분석 → 사용자 수정 → 최종 저장 → 옷장 조회`다. 체형 분석·코디 추천·외부 상품 추천은 구현 범위 밖이며, 추천 기능은 확정 저장된 옷 ID와 특징을 사용한다.

### 로컬 편집·저장 계약

| 요청 | 동작 |
| --- | --- |
| `GET /api/wardrobe/options/` | 한국어 표시명·enum·분류별 치수 기준 제공 |
| `PUT /api/wardrobe/items/{id}/` | 초안 확정 저장 또는 저장된 옷 전체 수정 |
| `GET /api/wardrobe/items/` | `saved=true`인 옷만 최신 생성 순서로 반환 |
| `GET /api/wardrobe/items/{id}/` | 단일 옷 정보 조회 |
| `GET /api/wardrobe/items/{id}/image/` | 저장한 옷의 정규화 JPEG 반환 |

PUT 본문은 `attributes`, `dimensions`, `user_properties`, `notes` 네 필드를 모두 포함한다. `attributes`는 위 시각적 필드이며 `user_properties`에는 material_note·touch·stretch·sheerness·thickness·seasons만 허용한다. 원본 AI 제안은 서버의 `original_attributes`에 별도 유지한다. 메모는 최대 2,000자, 줄바꿈을 그대로 보관한다.

치수는 현재 분류에 적용 가능한 항목만 저장한다. 변경하지 않은 AR 값은 `arcore_manual` 또는 `arcore_assisted` 출처를 유지하고, 사용자가 숫자를 입력하거나 수정한 값은 `user_measured`, `method=unspecified`로 저장하여 측정 기준을 임의로 확정하지 않는다. 분류 변경 시 폼의 무효 항목을 초기화한다. 치수가 필요 없으면 null로 보낸다.

측정점 탐지 API는 `POST /api/wardrobe/landmarks/?garment={종류}&background=keep|remove`다. 종류는 `short_sleeve_top`(반팔), `long_sleeve_top`(긴팔), `short_sleeve_outerwear`(반팔 아우터), `long_sleeve_outerwear`(긴팔 아우터), `trousers`(바지), `shorts`(반바지), `skirt`(스커트)다. 본문은 JPEG·PNG·WebP 단일 이미지이며 최대 1 MiB·100만 픽셀이다.

응답은 `model`, `garment`, `elapsed_ms`, `points`(종류 내 1부터 시작하는 id·x·y·score), `suggestions`(치수별 2~8개의 순서 있는 좌표), `background_removed`, `preview_jpeg`, `fallback_fields`다. `fallback_fields`는 누끼에서 누락되어 원본 추론으로 보완한 치수 목록이다. 좌표는 원본 요청 사진의 좌상단 기준 0~1이며 crop·letterbox를 역변환한다. `preview_jpeg`는 배경 제거가 적용된 경우에만 같은 뷰포트 비율의 JPEG를 base64로 반환한다. 원본 사진은 변경하지 않는다. score는 heatmap 점수로 정확도 확률이 아니다.

상의·아우터는 어깨·가슴·총장·소매길이·밑단·소매끝·암홀, 바지·반바지는 허리·엉덩이·허벅지·앞밑위·인심·총장·한쪽 밑단, 스커트는 허리·엉덩이·총장을 자동 제안한다. 경로에 필요한 점을 찾지 못하면 해당 항목을 생략한다. 허벅지 외곽점은 가랑이 높이에서 허리선과 평행한 선과 바깥 윤곽의 교점으로 유도한다. 엉덩이 폭·앞밑위 등은 학습된 기준점에 따른 근사이며 최대 폭이나 실제 곡선을 보증하지 않는다.

cm는 Android가 촬영 프레임의 AR 평면·카메라 행렬로 계산한다. 탐지 요청 사진·누끼·결과는 DB에 저장하지 않는다. 잘못된 종류·배경 모드는 400, 크기 초과 413, 형식 오류 415, 동시 작업 429, 모델 미준비·추론 실패 503이다. 빈 suggestions도 정상이며 직접 지정으로 보완한다.

같은 초안 ID로 반복 저장해도 옷이 중복 생성되지 않는다. 저장 실패 시 편집값을 유지하여 재시도할 수 있다. 잘못된 입력은 400, 16 KiB 초과 JSON은 413, 없는 ID는 404다. 사진·메모·키 원문을 오류 응답에 넣지 않는다. 로컬 API에는 인증·동시 편집 충돌 제어·초안 자동 청소가 없으므로 공용 서비스에 배포하지 않는다. 목록과 저장된 옷은 앱 재실행 후 서버에서 복원하지만 미저장 편집 상태의 프로세스 종료 복구는 보장하지 않는다.

## 2. 데이터 저장과 소유권

| 데이터 | 위치·원칙 |
| --- | --- |
| 확정 옷 정보 | MySQL의 Garment 레코드. 옷 한 벌당 한 행 |
| 사진 | 비공개 S3 객체. DB에는 만료 URL 대신 객체 키 저장 |
| 임시 분석 | MySQL의 GarmentDraft + 임시 S3 사진 |
| Android | 선택 사진·편집 상태·필요한 캐시. 서버가 확정 데이터의 기준 |
| API 키·S3 자격 증명 | 서버에만 보관. 앱·문서·Git에 기록하지 않음 |

로그인 UI를 생략하더라도 소유자 구분이 필요하다. **익명 사용자 + 서버 발급 접근 토큰**을 제안하며 발급·보관·복구 정책은 공통 BE 담당자와 합의한다. 클라이언트의 `owner_id`를 신뢰하지 않고 서버가 인증 문맥에서 결정한다. 사진·임시 등록·옷 조회 및 변경 모두 소유권을 검증한다.

체형 정보는 별도 프로필 모델이 담당하며 옷 레코드에 복제하지 않는다. 앱 삭제 등으로 익명 토큰을 잃었을 때 데이터 복구가 가능한지는 아직 미정이다.

## 3. 특징 기준정보

### 3.1. 스키마를 나누는 기준

추천용 데이터는 확장 가능한 범위로 정의하되, 선택 속성의 입력을 필수로 요구하지 않는다. **사진 기반 특징, 사용자 지정 특성, 의류 실측은 별도 데이터로 관리한다.** 확인되지 않은 값은 미입력 상태로 유지하며, 해당 값이 없어도 추천 기능이 동작해야 한다.

| 데이터 묶음 | 예 | 수집 방법 | 추천에서의 역할 |
| --- | --- | --- | --- |
| 종류·외관 | 티셔츠, 색상, 넥라인, 소매 | 이름·색상·상위 분류만 AI 제안, 세부 속성은 사용자 입력 | 코디 슬롯·색 조합·스타일 |
| 디자인 실루엣 | 오버사이즈 디자인, 와이드 레그 | 상품 정보·사용자 입력 | 원하는 실루엣에 대한 선호 점수 |
| 의류 실측 | 가슴 단면 55 cm, 인심 74 cm | 직접 측정 또는 해당 상품·사이즈의 치수표 | 실제 치수가 있는 경우에만 여유량 비교 |
| 소재·착용 특성 | 소재 메모, 촉감, 신축성, 비침, 두께, 계절 | 사용자가 경험·상품 정보를 참고하여 직접 지정 | 날씨·활동·선호 조건의 보조 판단 |

`category`는 `top / bottom / outerwear / shoes`를 유지한다. `Tops`, `Bottoms`처럼 대소문자·복수형을 혼용하지 않는다. 한글은 화면 표시명으로 사용한다.

### 3.2. 종류와 속성의 중복 제거

저장하는 분류는 `category`(상의·하의·아우터·신발), `subcategory`(티셔츠·청바지 등)의 2단계다. 소매·넥라인·실루엣은 독립 속성으로 저장하며, 같은 의미를 포함하는 garment_type은 저장·API 필드에서 제외한다.

[옷 종류 사전](wardrobe-types.md)의 상세 명칭은 검색·선택용 프리셋으로 유지한다. 예를 들어 사용자가 `와이드 청바지`를 선택하면 `category=bottom`, `subcategory=jeans`, `leg_shape=wide`로 변환한다. 이후 실루엣을 수정하면 상세 표시명도 현재 속성으로 다시 구성한다. 과거 프리셋 코드와의 충돌 검사는 필요하지 않다.

Gemini는 프리셋 코드 대신 이름·상위 카테고리·색상만 반환한다. 소재를 전제로 하는 프리셋은 사용자가 선택할 수 있지만, AI가 소재를 확정하는 경로로 사용하지 않는다. 확인하기 어려운 subcategory는 null로 둔다. 카디건·후드 집업·셔켓은 사전에 따라 outerwear로 분류한다.

### 3.3. 공통 외관 속성

단일 선택 미입력은 null, 다중 선택 미입력은 []다. 다음 표는 제안 enum의 전체 허용값과 표시명을 정의한다. 배열은 중복을 허용하지 않는다.

| 필드 | 허용값: 표시명 | 규칙 |
| --- | --- | --- |
| `colors` | `black`: 검정, `white`: 흰색, `gray`: 회색, `ivory`: 아이보리, `beige`: 베이지, `brown`: 갈색, `navy`: 네이비, `blue`: 파랑, `green`: 초록, `khaki`: 카키, `red`: 빨강, `orange`: 주황, `yellow`: 노랑, `pink`: 분홍, `purple`: 보라, `silver`: 은색, `gold`: 금색, `other`: 기타 | 최대 3개, 대표색 우선 |
| `styles` | `minimal`: 미니멀, `casual`: 캐주얼, `street`: 스트릿, `lovely`: 러블리, `classic`: 클래식, `sporty`: 스포티, `formal`: 포멀, `workwear`: 워크웨어 | 최대 3개, 대표 스타일 우선 |
| `formality` | `relaxed`: 편한 차림, `casual`: 일상 캐주얼, `smart_casual`: 단정한 캐주얼, `formal`: 격식 있는 차림 | 사진 제안 가능, 장소 적합성 보증 아님 |

신발에도 색상·스타일·격식은 적용한다. 신발의 발 치수·발볼·라스트 비교는 별도 기능이며 의류 치수로 대체하지 않는다.

### 3.4. 핏·형태

기존 `fit`은 **`fit_type`으로 변경하고 의류의 디자인상 여유감을 나타내는 필드로 정의한다.** `dimensions`는 실측 정보를 관리하며 fit_type을 포함하지 않는다. `oversized`는 디자인 분류이며 특정 사용자에 대한 실제 착용 여유를 보장하지 않는다.

| 필드 | 적용 범위 | 허용값: 표시명 |
| --- | --- | --- |
| `fit_type` | 상의·하의·아우터 | `skinny`: 스키니, `slim`: 슬림, `regular`: 레귤러, `loose`: 루즈, `oversized`: 오버사이즈 |
| `sleeve_length` | 상의·아우터 | `sleeveless`: 민소매, `short`: 반팔, `elbow`: 팔꿈치 길이, `three_quarter`: 7부, `long`: 긴팔 |
| `neckline` | 상의·아우터 | `crew`: 라운드넥, `v_neck`: 브이넥, `scoop`: 깊은 둥근 넥, `square`: 스퀘어넥, `boat`: 보트넥, `henley`: 헨리넥, `polo`: 폴로, `shirt_collar`: 셔츠 칼라, `open_collar`: 오픈칼라, `band_collar`: 밴드칼라, `turtleneck`: 터틀넥, `mock_neck`: 반목, `hooded`: 후드, `other`: 기타 |
| `shoulder_construction` | 상의·아우터 | `set_in`: 일반 어깨선, `drop_shoulder`: 드롭숄더, `raglan`: 래글런, `dolman`: 돌먼·가오리형, `other`: 기타 |
| `leg_shape` | 바지·반바지·active_pants | `skinny`: 밀착, `slim`: 슬림, `straight`: 일자, `tapered`: 밑단으로 좁아짐, `semi_wide`: 세미 와이드, `wide`: 와이드, `bootcut`: 부츠컷, `flared`: 크게 퍼지는 밑단, `balloon`: 벌룬 |
| `rise_type` | 하의 | `low`: 로우라이즈 디자인, `mid`: 미드라이즈, `high`: 하이라이즈 |
| `skirt_shape` | 스커트 | `straight`: 일자, `a_line`: A라인, `flared`: 플레어, `pleated`: 플리츠, `pencil`: 펜슬, `other`: 기타 |
| `closure` | 신발 외 | `pullover`: 풀오버, `button`: 단추, `zip`: 전체 지퍼, `half_zip`: 부분 지퍼, `wrap`: 랩·여밈, `open_front`: 앞 트임, `other`: 기타 |
| `details` | 신발 외 | `cargo_pockets`: 카고 포켓, `ruffle`: 러플, `pleats`: 주름, `cable_knit`: 꽈배기 편직, `side_stripe`: 옆선 배색, `cuffed_hem`: 조인 밑단, `drawstring`: 조임끈, `elastic_waist`: 허리 밴딩 |

`details`는 최대 8개 배열, 나머지는 단일값 또는 null이다. 신발에는 이 표의 단일 필드를 null, details를 []로 둔다. subcategory가 other 또는 null인 하의는 leg_shape·skirt_shape를 추측하지 않고 null로 둔다. 착용 시 기장감은 실측과 사용자 체형을 함께 비교해야 한다.

### 3.5. 소재·착용 특성 — 사용자 지정

**이 절의 값은 AI 자동 분석 대상에서 제외한다.** 사용자가 상품 설명·라벨·착용 경험을 참고하여 선택하거나 입력한다. 근거가 없으면 미입력으로 유지한다. 사용자가 선택했다는 사실은 객관적 물성 측정이나 기능성 인증을 의미하지 않는다.

| 필드 | 허용값: 표시명 | 입력 규칙 |
| --- | --- | --- |
| `material_note` | 자유 텍스트 | 선택, 앞뒤 공백 제거 후 1~200자. 예: 면 100%, 소재 미상. 공백만 입력하면 null |
| `touch` | `soft`: 부드러움, `slightly_soft`: 약간 부드러움, `normal`: 보통, `slightly_stiff`: 약간 뻣뻣함, `stiff`: 뻣뻣함 | 5단계 단일 선택 또는 null |
| `stretch` | `none`: 없음, `almost_none`: 거의 없음, `moderate`: 보통, `slight`: 약간 있음, `present`: 있음 | 원문 5단계 구분을 보존. 단일 선택 또는 null |
| `sheerness` | `present`: 있음, `slight`: 약간 있음, `moderate`: 보통, `almost_none`: 거의 없음, `none`: 없음 | 비침 5단계, 단일 선택 또는 null |
| `thickness` | `thin`: 얇음, `slightly_thin`: 약간 얇음, `medium`: 보통, `slightly_thick`: 약간 두꺼움, `thick`: 두꺼움 | 5단계 단일 선택 또는 null |
| `seasons` | `spring`: 봄, `summer`: 여름, `autumn`: 가을, `winter`: 겨울 | 복수 선택, 최대 4개, 중복 금지, 미입력은 [] |

위 단계의 표시명은 사용자가 제공한 무신사 항목을 기준으로 한다. 정량적 간격이나 보편적 물리 단위를 의미하지 않는다. 특히 신축성의 보통·약간 있음 순서를 임의로 수치 점수에 대응시키지 않는다. 추천에서 점수가 필요하면 별도로 의미와 매핑을 정의한다.

fit_type도 사용자 제공 5단계(스키니·슬림·레귤러·루즈·오버사이즈)를 사용한다. 단, 디자인 핏은 사진 기반 제안이 가능하므로 3.4절에서 관리한다. leg_shape는 다리통 형태, fit_type은 전반적인 디자인 여유감으로 의미를 구분한다.

material_note는 검색·표시용 참고 메모이며 문자열에서 혼용률·보온 성능을 자동 확정하지 않는다. 별도의 fabric enum은 제거한다. 안감·방수·방풍·레이어 역할·실제 착용 피드백은 이번 스키마에서 제외한다. 필요성이 확인되면 후속 기능으로 정의한다.

seasons는 사용자 또는 상품이 제시하는 착용 계절이며 기온 보증이 아니다. 선택 사이즈(size_label)와 전체 사이즈표는 저장하지 않는다. 사용자가 소유한 옷의 실측만 등록하며, 치수표에서 어떤 행을 참조했는지는 선택적인 출처 메모에 기록할 수 있다.

### 3.6. 실제 치수: dimensions

각 치수는 측정값·출처·측정 방법을 포함하는 객체로 저장한다. 단위는 `dimensions.unit=cm`로 고정한다. 미측정 필드는 null이다. 상품표의 `-`는 0이 아니라 null로 정규화한다. 사진 속 픽셀 길이, 이미지 비율, 사용자의 체형 실루엣을 cm 실측으로 취급하지 않는다.

치수 객체 형식:

```json
{
  "value": 55.0,
  "source": "user_measured",
  "method": "flat_underarm_to_underarm",
  "reference": null
}
```

- value: 유한한 양수, 소수 둘째 자리까지 반올림한다. 화면은 `55.10 cm`처럼 두 자리를 표시하며 JSON number는 후행 0을 생략할 수 있다. 0·음수·단위 포함 문자열은 거부한다. 신체와 옷 종류가 다양하므로 임의의 보편적 정상 범위를 하드코딩하지 않는다.
- source: `user_measured`(사용자가 실제 측정), `product_chart`(해당 상품·사이즈 치수표). 보정되지 않은 `ai_estimated`는 허용하지 않는다.
- 로컬 AR 실험의 추가 source는 `arcore_manual`(사용자 지정점), `arcore_assisted`(모델 제안점을 사용자 확인)다. 경로의 각 점을 촬영 시점의 AR 수평 평면에 투영하고 구간 거리를 합산한 추정치로, `user_measured`와 구분한다. 자동 제안점을 사진에서 다시 지정하면 `arcore_manual`, 숫자로 수정하면 `user_measured`가 된다. 정확도 검증 전이므로 추천의 확정 실측으로 자동 사용하지 않는다. 정식 서버의 허용값 확장은 추후 계약에 함께 반영해야 한다.
- method: 아래 표의 측정 방법 코드. 치수표 기준이 불명확하면 `unspecified`로 저장하고 자동 치수 비교에서는 제외한다.
- reference: 출처 메모 또는 URL, 선택값. product_chart의 reference는 선택 입력이며 상품·참조 행에 대한 메모를 남길 수 있다. URL을 저장했다고 자동 수집하지 않는다.
- AI가 치수표를 읽는 기능은 이번 범위 밖이다. 향후 추가해도 OCR 값은 사용자 확인과 해당 상품·사이즈 매칭 후 반영한다.

모든 직접 측정은 옷을 평평하게 펴고, 여밈을 닫고, 의도적으로 늘리지 않은 상태를 기준으로 한다. 아래 방법은 **프로젝트용 정의**다. 상품별 측정 기준이 상이한 경우 근거 없는 수치 변환을 수행하지 않는다.

#### 상의·아우터 치수

| 필드 | 표시명 | method 및 측정 기준 | 추천 활용·제약 |
| --- | --- | --- | --- |
| `shoulder_width` | 어깨너비 | `flat_shoulder_seam_to_seam`: 뒤쪽 양 어깨 봉제점 사이 직선 | 드롭숄더·래글런에서는 사용자 어깨와 단순 비교 금지 |
| `chest_width_half` | 가슴 단면 | `flat_underarm_to_underarm`: 양 겨드랑이 아래 사이 직선 | 둘레 여유량의 보조 추정 |
| `total_length` | 총장 | `back_neck_to_hem`: 뒤 목둘레 봉제선 중앙에서 밑단까지; 칼라 제외 | 앞뒤 길이차·다른 시작점의 치수표 주의 |
| `sleeve_length` | 소매길이 | `shoulder_seam_to_cuff`: 어깨 봉제점에서 소매 끝까지; `center_back_via_shoulder_to_cuff`: 뒤 목 중심에서 어깨를 거쳐 소매 끝까지 | method로 구분하며 서로 직접 비교하지 않음 |
| `hem_width_half` | 상의 밑단 단면 | `flat_body_hem`: 몸판 밑단 좌우 직선 | 한쪽 바짓단과 구분 |
| `cuff_width_half` | 소매끝 | `flat_sleeve_opening`: 닫힌 소매 끝을 평평하게 편 좌우 직선 | 소매 끝 둘레와 구분, 민소매는 null |
| `armhole_straight` | 암홀 | `armhole_top_to_underarm_straight`: 일반 소매의 어깨·암홀 교점에서 겨드랑이 봉제점까지 직선 | 암홀 곡선 길이나 팔 둘레가 아님; 가오리·래글런은 null 가능 |

민소매의 sleeve_length는 0이 아니라 null이다. 소매길이는 측정 시작점이 어깨 봉제점인지 등 중심인지에 따라 구분한다. [Proper Cloth의 측정 안내](https://propercloth.com/reference/how-to-measure-a-dress-shirt/)에서도 등 중심을 거치는 방법을 사용하므로 출처의 방법을 함께 확인한다.

상품표가 `암홀`, `총장`, `밑위` 등의 항목명만 제공하는 경우 측정 경로를 확정하지 않는다. 출처의 측정 가이드를 확인하지 못하면 method=unspecified로 입력하고 자동 실측 비교에서 제외한다.

#### 하의 치수

| 필드 | 표시명 | method 및 측정 기준 | 적용 |
| --- | --- | --- | --- |
| `waist_width_half` | 허리 단면 | `flat_waistband_relaxed`: 허리밴드 위쪽 좌우를 늘리지 않고 직선 측정 | 모든 하의 |
| `hip_width_half` | 엉덩이 단면 | `flat_hip_max_width`: 힙 부분의 가장 넓은 수평 단면 | 모든 하의, 주름·패턴에 따른 한계 기록 |
| `thigh_width_half` | 허벅지 단면 | `flat_thigh_at_crotch`: 가랑이 높이에서 한쪽 다리통 단면 | 바지·반바지·active_pants |
| `rise_front` | 앞밑위 | `front_waistband_along_rise_to_crotch`: 앞 허리밴드 위에서 앞 중심 봉제선을 따라 가랑이 교점까지 | 바지·반바지·active_pants |
| `inseam` | 인심 | `crotch_along_inner_seam_to_hem`: 가랑이 봉제 교점부터 안쪽 봉제선을 따라 밑단까지 | 바지·반바지·active_pants |
| `total_length` | 총장 | `waistband_along_side_to_hem`: 허리밴드 위에서 옆선을 따라 밑단까지 | 모든 하의; 스커트도 옆선 기준 |
| `hem_opening` | 한쪽 밑단 단면 | `flat_single_leg_hem`: 한쪽 바짓단 좌우 직선 | 바지·반바지·active_pants; 스커트에는 사용 안 함 |

앞밑위는 곡선 경로, 인심은 안쪽 봉제선, 총장은 옆선 경로이므로 **total_length = rise_front + inseam을 검증식으로 사용하지 않는다.** 각 측정 경로를 별도로 기록한다. 인심과 앞밑위 경로는 [인심 가이드](https://propercloth.com/reference/how-to-measure-pants-inseam-length/)와 [앞밑위 가이드](https://propercloth.com/reference/how-to-measure-pants-front-rise/)를 참고했다. 위 필드명과 정규화 정책은 이 프로젝트의 설계다.

가슴·허리·힙 단면 × 2는 상황에 따라 의류 둘레의 근사치가 될 뿐, 사용자의 신체 정면 너비나 신체 둘레/2와 동일한 물리량이 아니다. 신축·주름·곡선·신체 입체 형태·측정 위치를 함께 고려한다. 앞밑위 하나로 배꼽 기준 위치, 인심 하나로 곱창 주름 발생을 확정하지 않는다.

적용하지 않는 치수 키는 전달하지 않는다. 적용 가능한 미측정 치수는 null로 둘 수 있다. 신발은 dimensions=null이다. 로컬 구현에서는 하의 subcategory가 미입력이어도 치수를 저장할 수 있다. FE는 category·subcategory·소매 구조에 맞는 입력만 보여주고 BE가 같은 규칙을 검증한다.

### 3.7. AI 출력 범위와 입력 출처

AI 분석 응답의 attributes는 다음 필드만 허용한다.

`name`, `category`, `colors`. 성공 시 이름·카테고리는 필수이고 색상은 최대 3개다. 추가 필드는 거부한다. Django는 기존 편집·DB 계약을 유지하기 위해 요청하지 않은 선택 필드를 null 또는 []로 채운다. 이는 AI 추정값이 아니며 사용자만 입력한다.

material_note·touch·stretch·sheerness·thickness·seasons·dimensions는 AI 응답에 포함할 수 없다. BE에서 별도 AI 출력 스키마로 검사하며 금지 필드가 포함된 결과를 최종 저장값으로 사용하지 않는다. 재분석 결과로 기존 사용자 입력을 덮어쓰지 않는다.

서버 관리 필드 attribute_sources는 값이 있는 특징에 대해 `ai_suggested` 또는 `user_entered`를 기록한다. 사용자 전용 필드는 반드시 user_entered다. 시각적 특징은 draft의 제안과 최종 값을 비교하고 사용자의 실제 수정 시 user_entered로 기록한다. 일반 저장 요청에서 출처를 임의 지정하지 못하게 한다. 값이 null/[]로 삭제되면 출처도 제거한다.

항목별 확인 버튼·confirmed_fields·별도 attribute_references는 사용하지 않는다. 편집 없는 저장은 AI 제안의 출처를 유지한다. 실측 출처는 dimensions 안의 기존 source·method·reference로 관리한다. 사용자 입력이 객관적 검증을 의미하지는 않는다.

### 3.8. 추천 담당자가 사용할 규칙

| 추천 목적 | 우선 적용 데이터 | 데이터 부재 시 처리 |
| --- | --- | --- |
| 상하의·신발 구성 | category·실제 소유 옷 ID | 슬롯을 알 수 없는 옷 제외 |
| 색·스타일 조합 | colors·styles·formality | 미확인 속성은 점수 계산에서 제외 |
| 원하는 실루엣 | fit_type·leg_shape·neckline·sleeve_length | 알려진 속성·사용자 선호로 추천 |
| 착용 여유 참고 | 같은 기준의 실측 + 사용자 확인 신체 치수 + stretch | 사이즈 적합 판정 생략, 코디 추천은 계속 |
| 날씨·계절 | 사용자 지정 seasons·thickness·sheerness 및 요청 날씨 | 미입력 속성은 판단에서 제외 |

계산 검토 예시: `가슴 여유량 근사 = 2 × 의류 가슴 단면 - 사용자 가슴둘레`. 이는 동일 위치·적절한 측정 방법이 확인된 경우의 보조 지표다. 임의로 몇 cm 이상이면 오버핏이라는 보편적 임계값을 정하지 않는다. 어깨 구조·소재·신축성·선호에 따른 평가가 필요하다. 사용자 프로필이 체형 실루엣만 제공하면 이 계산을 하지 않는다.

`몸에 맞음`, `배꼽 위 N cm`, `발등을 덮음` 같은 결과는 옷 레코드의 영구 속성이 아니라 사용자·프로필 버전에 의존하는 추천 결과다. 실제 치수가 부족하면 결과 상태를 insufficient_data로 설명한다. AI의 자기평가 confidence 숫자를 보정된 정확도처럼 사용하지 않는다.

## 4. 최종 옷 레코드

### 필드 구성

| 필드 | 형태 | 규칙 |
| --- | --- | --- |
| `schema_version` | 정수 | 이번 제안은 3, 서버가 설정 |
| `id`, `owner_id`, `image_key` | PK·FK·문자열 | 기존과 같이 서버가 결정 |
| `name` | 문자열 | 앞뒤 공백 제거 후 1~50자, 필수 |
| `category` | 문자열 | 네 가지 허용값 중 하나, 필수 |
| `subcategory` | nullable 문자열 | 종류 사전의 category 종속 관계 검증 |
| 색상·핏 등 3절 속성 | nullable 문자열 또는 배열 | 3절의 적용 범위·허용값·개수 검증 |
| `notes` | 문자열 | 사용자 메모, 선택, 최대 2,000자, 줄바꿈 유지, 빈 문자열로 삭제, AI에 전송하지 않음 |
| `dimensions` | nullable JSON 객체 | unit + 적용 가능한 치수 객체, 출처·방법 포함 |
| `attribute_sources` | JSON 객체 | 서버가 관리하는 필드별 AI 제안·사용자 입력 출처 |
| `created_at`, `updated_at` | 날짜·시간 | 서버 설정, UTC ISO 8601 |

category·subcategory·이름·소유자는 일반 컬럼, 선택 속성은 내부 attributes JSONField, dimensions는 별도 JSONField로 관리한다. API에서는 종류·외관 속성을 최상위 필드로 전달하고 BE가 내부 JSON에 매핑한다. **JSON 저장 형식에도 명시적인 스키마 검증을 적용한다.** BE의 공통 검증 규칙으로 분석·생성·수정 모두 검사한다.

생성 요청에서 선택 속성을 생략하면 null 또는 []로 정규화한다. PATCH에서 생략은 기존 값 유지, null/[]는 명시적 삭제다. dimensions를 PATCH하면 객체 전체 교체로 해석하며 unit과 유지할 치수를 모두 보낸다. 객체 내부의 부분 병합을 암묵적으로 수행하지 않는다. 알 수 없는 키는 거부한다. 빈 객체 전달은 기존 치수 유지 요청으로 해석하지 않는다.

치수 소수는 API JSON number로 교환하고 서버에서 소수 자릿수를 검증한다. 계산 시 Decimal 등으로 정규화한다. 분석 응답의 attributes는 AI 제안 필드만 포함하며 사용자 전용 특성·dimensions·서버 메타데이터는 포함하지 않는다.

image_url은 조회 때 만드는 만료 URL이며 DB 영구 식별자는 image_key다. AI 원문을 최종 데이터로 그대로 저장하지 않는다.

### v0.3 변경 사항

- garment_type은 검색·선택 프리셋으로 전환하고 저장 필드에서 제외한다.
- fabric은 사용자 전용 material_note로 대체한다.
- 촉감·비침·계절, 상의 밑단·소매부리 치수를 추가하고 핏·신축성·두께는 5단계로 정의한다.
- 소매길이의 측정 시작점은 method로 구분하여 중복 필드를 제거한다.
- 항목별 확인 API·실제 착용 피드백·레이어 및 기능성 필드를 제외한다. 선택 사이즈는 추가하지 않는다.
- 이전 초안과 필드가 호환되지 않으므로 v0.2를 구현한 경우 API와 데이터 변환을 먼저 합의한다. 현재 저장소에는 해당 모델이 구현되어 있지 않다.

## 5. 임시 등록과 화면 상태

GarmentDraft 제안 필드: `id`(추측하기 어려운 ID), `owner_id`, `image_key`, `status`, `suggested_attributes`, `error_code`, `garment_id`(최종 저장 후 연결), `created_at`, `expires_at`, `upload_request_key`, `image_hash`, `analysis_attempt_id`, `analysis_started_at`. 마지막 네 필드는 업로드 중복 요청 방지와 분석 중단 복구를 위한 서버 내부 필드다.

서버 상태는 `uploaded → analyzing → ready / analysis_failed → saved`로 둔다. 클라이언트의 사진 선택 상태는 서버 상태가 아니다. `analysis_failed`에서도 사용자가 필수값을 입력하면 저장할 수 있다. 업로드가 실패하여 draft가 없으면 저장할 수 없다.

- **제안:** 업로드와 분석 요청을 분리해, 분석 실패 후 사진을 다시 올리지 않고 재시도·수동 입력할 수 있게 한다.
- 초기 분석은 요청 하나 안에서 결과를 반환한다. 측정한 지연 시간이 서버·클라이언트 타임아웃에 맞지 않으면 비동기 작업 API를 별도 설계한다. 현재 초안에 작업 큐를 필수로 도입하지 않는다.
- 분석 완료 상태의 임시 등록 데이터는 옷장 목록에 표시하지 않는다. 최종 저장된 Garment만 표시한다.
- 분석 중인 같은 draft의 추가 분석 요청은 거부한다. 오래된 분석 요청이 상태를 덮어쓰지 않도록 시도 식별자 또는 조건부 갱신을 구현한다.
- `saved` 상태는 재분석할 수 없다. 저장이 완료된 draft를 임시 파일 정리 대상으로 취급하지 않는다.
- 미저장 초안 만료는 24시간을 제안한다. 서버가 만료를 검증하고, 정리 작업은 활성 분석과 충돌하지 않게 임시 사진·레코드를 제거한다. 실제 주기·실행 환경은 인프라 담당자와 합의한다.

## 6. API 초안

모든 요청의 사용자 식별 방식은 팀 인증 계약에 맞춘다. 앱에서 AI나 S3 비밀 키를 직접 사용하지 않는다. 최종 경로·공통 오류 포맷은 기본 BE 담당자와 조정한다. 업로드 중복 요청 키와 분석 시도 상태의 세부 절차는 [데이터 흐름](wardrobe-flow.md)을 따른다.

| Method | 경로 | 요청·성공 응답 |
| --- | --- | --- |
| POST | `/api/wardrobe/drafts/` | multipart `image` + Idempotency-Key → 201, draft ID·상태·만료 시각·미리보기 URL. 동일 업로드 재요청은 기존 초안 200 |
| GET | `/api/wardrobe/drafts/{id}/` | 200, draft_id·status·expires_at·상태별 attributes/error_code/garment_id. 복귀·응답 유실 후 복구 |
| POST | `/api/wardrobe/drafts/{id}/analyze/` | 업로드된 사진 분석 또는 실패 재시도 → 200, 상태·제안 특징 |
| POST | `/api/wardrobe/garments/` | draft ID·최종 특징 → 201, 저장된 옷 |
| GET | `/api/wardrobe/garments/` | 선택 query: category, q, page → 200, 목록 |
| GET | `/api/wardrobe/garments/{id}/` | 200, 본인 옷 상세 |
| PATCH | `/api/wardrobe/garments/{id}/` | 변경할 이름·특징만 → 200, 수정된 옷 |
| DELETE | `/api/wardrobe/garments/{id}/` | 204, 목록·추천 대상에서 제거 |

`PATCH`에서 owner, image_key, ID는 수정할 수 없다. 부분 수정은 기존 값과 합친 최종 상태를 검증한다. category·subcategory 변경으로 무효가 된 특징·치수는 클라이언트가 null/[] 또는 유효한 값으로 함께 보내야 한다. BE는 병합 후 최종 종류·속성의 적용 범위을 검사한다.

### Gemini 응답 예시

```json
{
  "image_status": "single",
  "attributes": {
    "name": "화이트 긴팔 티셔츠",
    "category": "top",
    "colors": ["white"]
  }
}
```

옷 없음·여러 벌·미지원·판별 불가는 별도 image_status와 attributes=null로 반환한다. 로컬 Django 응답에는 초안 ID와 편집용 null 기본값이 추가된다. dimensions·메모는 Gemini에 요청하지 않는다.

### 최종 저장 예시 — 상의와 실제 측정값

아래 숫자는 예시이며 AI가 사진에서 추정한 치수가 아니다. 소재·착용 특성은 사용자 지정값이며 출처 메타데이터는 서버에서 계산한다.

```json
{
  "draft_id": "draft-example",
  "name": "내 화이트 긴팔 티셔츠",
  "category": "top",
  "subcategory": "tshirt",
  "colors": [
    "white"
  ],
  "fit_type": "loose",
  "sleeve_length": "long",
  "neckline": "crew",
  "shoulder_construction": "drop_shoulder",
  "styles": [
    "casual"
  ],
  "dimensions": {
    "unit": "cm",
    "chest_width_half": {
      "value": 55.0,
      "source": "user_measured",
      "method": "flat_underarm_to_underarm",
      "reference": null
    },
    "total_length": {
      "value": 68.0,
      "source": "user_measured",
      "method": "back_neck_to_hem",
      "reference": null
    },
    "hem_width_half": null,
    "cuff_width_half": null,
    "armhole_straight": null
  },
  "material_note": null,
  "touch": null,
  "stretch": "almost_none",
  "sheerness": "none",
  "thickness": "medium",
  "seasons": [
    "spring",
    "autumn"
  ]
}
```

### 최종 저장 예시 — 와이드 청바지

product_chart reference는 출처 형식을 설명하는 가상 예시다. 출처 메모는 선택이며 별도의 선택 사이즈 필드는 없다.

```json
{
  "draft_id": "draft-bottom-example",
  "name": "와이드 청바지",
  "category": "bottom",
  "subcategory": "jeans",
  "colors": [
    "blue"
  ],
  "fit_type": "loose",
  "leg_shape": "wide",
  "rise_type": "high",
  "styles": [
    "casual"
  ],
  "dimensions": {
    "unit": "cm",
    "waist_width_half": {
      "value": 39.0,
      "source": "product_chart",
      "method": "unspecified",
      "reference": "사용자가 제공한 상품 치수표의 해당 행"
    },
    "inseam": {
      "value": 74.0,
      "source": "user_measured",
      "method": "crotch_along_inner_seam_to_hem",
      "reference": null
    }
  },
  "material_note": null,
  "touch": null,
  "stretch": "almost_none",
  "sheerness": "none",
  "thickness": "medium",
  "seasons": [
    "spring",
    "autumn"
  ]
}
```

응답은 ID·최종 특징·image_url·생성/수정 시각을 포함한다. draft 하나에서 옷은 한 벌만 생성한다. 트랜잭션과 고유 제약으로 중복 생성을 막고, 같은 draft의 재저장 요청은 기존 옷을 200으로 반환한다. 재저장으로 특징을 덮어쓰지 않으며 변경은 PATCH로 처리한다.

목록은 `{ "total_count": 32, "count": 8, "next": null, "results": [] }` 형태를 제안한다. 숫자는 예시이며 `total_count`는 요청 사용자의 전체 저장 개수, `count`는 필터 결과 수다. 기본 페이지 크기는 30, 정렬은 생성 시각 내림차순·ID 내림차순이다. `q`는 이름 부분 검색이며 category와 AND 조건으로 적용한다. 추가 페이지를 읽을 때 현재 필터·검색어를 유지한다.

## 7. 입력·오류·이미지 수명 정책

- 업로드 제안 제한: JPEG·PNG·WebP, 최대 10 MiB, 최대 2천만 픽셀. 확장자·MIME 선언 외에 서버에서 실제 디코딩·크기를 검사한다.
- 서버는 EXIF 방향을 반영한 뒤 위치 등 불필요한 메타데이터를 제거한다. 저장·분석용 이미지는 긴 변 최대 1600 px의 JPEG로 정규화하는 방안을 제안한다. 실제 품질과 처리 메모리를 측정한 후 확정한다.
- 빈 파일·손상 이미지·여러 옷·옷이 아닌 사진·미지원 카테고리를 구분한다. 외부 AI 응답의 JSON 형식·허용값·조합을 서버에서 검증한다.
- S3 업로드 완료 후 DB 생성 실패 시 사진 정리를 시도한다. 최종 저장 시 동일한 사진 키를 재사용하여 불필요한 복사를 피한다.
- 삭제는 먼저 DB에서 조회·추천 대상에서 제거한다. S3 삭제 실패는 재시도 가능한 정리 기록으로 남긴다. DB 트랜잭션만으로 S3까지 원자적으로 변경된다고 가정하지 않는다.
- 만료된 이미지 URL은 API에서 새로 받아 표시한다. 저장되지 않은 사진과 삭제된 옷 사진이 무기한 남지 않도록 정리 경로를 구현한다.

오류 응답은 `{ "error": { "code": "...", "message": "...", "fields": {} } }`를 제안한다. UI 분기는 안정적인 code를 사용하고 서버·AI 내부 응답을 사용자에게 그대로 노출하지 않는다.

| HTTP | code 예시 | UI 대응 |
| --- | --- | --- |
| 400 | `INVALID_IMAGE`, `VALIDATION_ERROR` | 사진 변경 또는 해당 입력 수정 |
| 401 | `AUTH_REQUIRED` | 공통 사용자 식별 복구 흐름. 자동으로 새 사용자로 바꾸지 않음 |
| 404 | `NOT_FOUND` | 삭제됐거나 접근할 수 없는 데이터 안내 |
| 409 | `ANALYSIS_IN_PROGRESS`, `DRAFT_ALREADY_SAVED` | 중복 분석 방지, 저장된 옷으로 이동 |
| 410 | `DRAFT_EXPIRED` | 사진을 다시 등록하도록 안내 |
| 413 | `IMAGE_TOO_LARGE` | 크기 제한 안내 |
| 422 | `NO_GARMENT`, `MULTIPLE_GARMENTS`, `UNSUPPORTED_CATEGORY` | 사진 변경·범위 안내 |
| 502 / 504 | `ANALYSIS_FAILED`, `ANALYSIS_TIMEOUT` | 분석 재시도 또는 수동 입력 |
| 503 | `STORAGE_UNAVAILABLE` | 업로드 재시도 |

`DRAFT_ALREADY_SAVED`는 저장된 draft를 재분석할 때 사용한다. 동일 draft의 최종 저장 재요청은 앞 절의 200 응답 규칙을 따른다. 어떤 오류에서도 다른 사용자의 사진·옷 존재 여부를 노출하지 않는다.

## 8. 담당자 간 확정할 항목

| 항목 | 협의 대상 | 현재 제안 |
| --- | --- | --- |
| Android·Django 실제 패키지 구조 | 기본 FE·BE 담당자 | 공통 구조 안에 wardrobe 기능 배치 |
| 사용자·인증·ID 정책 | 기본 BE 담당자 | 익명 사용자 토큰, 서버가 owner 결정 |
| 특징 목록·null 의미 | 추천 담당자 | 3절의 확장 속성과 실측 출처, 확정 옷 ID만 추천에 사용 |
| AI 제공자·모델·비용·처리 시간 | AI 연동 담당 | Gemini gemini-3.1-flash-lite 선정·로컬 연결, 실제 비용·지연·품질 검증 전 |
| S3 설정·사진 정리·DB 연결 | BE·인프라 담당자 | 서버에서 접근, 미저장 초안 24시간 |
| 공통 테마·내비게이션 | FE 담당자 | 기존 시안의 공통 요소 재사용 |
| 삭제된 옷을 참조하는 코디 | 추천·코디북 담당자 | 저장 코디의 표시·삭제 정책 합의 필요 |

## 9. 구현 검증 기준

- [ ] 사진 촬영·선택 → 분석 → 사용자 수정 → 저장 → 재실행 후 조회가 동작한다.
- [ ] 수정한 특징이 그대로 저장되고 AI 최초 제안으로 덮어쓰이지 않는다.
- [ ] 카테고리·종류별 속성 적용 범위, null, 다중값 중복·상한, 필수값을 검증한다.
- [ ] AI 응답에서 소재 메모·촉감·신축성·비침·두께·계절·cm 실측 필드를 거부한다.
- [ ] 치수의 단위·출처·방법·적용 범위와 스커트/민소매/신발의 예외를 검증한다.
- [ ] 속성·치수가 없어도 등록 및 코디 추천이 가능하고 실측 적합 판정만 생략한다.
- [ ] PATCH의 생략·null·객체 전체 교체 규칙과 출처 갱신을 검증한다.
- [ ] 분석 실패 뒤 수동 입력·재시도가 가능하고, 재시도가 옷을 중복 생성하지 않는다.
- [ ] 저장 성공 응답을 잃고 다시 저장해도 옷은 한 벌만 존재한다.
- [ ] 다른 사용자의 옷·draft ID로 조회·분석·수정·삭제·저장을 할 수 없다.
- [ ] 필터 결과 개수와 전체 옷 개수를 구분하고 페이지 이동에도 조건이 유지된다.
- [ ] 삭제·만료·실패로 남은 이미지의 정리와 재시도 경로를 확인한다.
- [ ] 실제 MySQL·S3에서 통합 검증한다. 로컬 모의 구현만으로 완료 처리하지 않는다.

이 기준정보는 현재 Markdown 계약이다. 구현 시 BE 검증 스키마와 FE 매핑을 추가하고 계약 검증으로 값의 불일치를 확인한다. 문서 변경과 구현 간 동기화는 별도로 관리한다.

## 10. 구현 순서와 문서 역할

1. 1차 등록: 종류 사전·색상·스타일·핏·기장·소매·넥라인·하의 형태를 추출·수정·저장한다.
2. 선택 입력: 소재 메모·촉감·신축성·비침·두께·계절과 실측은 접힌 폼에서 입력받는다. 사진 등록의 필수 단계로 만들지 않는다.
3. 추천 연동: 먼저 외관과 선호 기반 추천을 연결하고, 실제 측정된 옷·신체 데이터가 확보된 경우에만 치수 기반 비교를 평가한다.

분류 체계 확장은 AI 분류 정확도의 향상을 보장하지 않는다. 대표 사진으로 세분류·속성 정확도와 null 처리, 사용자 수정 부담을 검증해야 한다. 세분류의 신뢰성이 확보되지 않은 경우 상위 subcategory 수준으로 저장할 수 있도록 한다.

- 이 문서: 필드 의미·측정 방법·저장/API 계약.
- [wardrobe-types.md](wardrobe-types.md): 옷 코드·한국어 이름·종류별 분류 기준.
- [design.md](design.md): 입력 UI·화면 흐름·시각 규칙.

변경 이력: 2026-09-26 v0.3 — 사용자 전용 특성 및 누락 실측 추가, AI 출력 제한, 중복 필드 정리. 모두 팀 합의 전 제안이며 실행 가능한 JSON Schema 파일이나 구현 완료 상태는 아니다.
