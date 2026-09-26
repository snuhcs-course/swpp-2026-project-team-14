# 옷 종류 사전

상태: 팀 합의 전 제안 v0.3 · 관련 문서: [스키마](wardrobe-spec.md), [화면 설계](design.md)

## 분류 체계 및 적용 기준

이 사전은 **화면 검색·선택용 프리셋 목록**이다. 프리셋 코드 자체는 옷 레코드에 저장하지 않는다. 저장 필드는 category·subcategory와 개별 속성이며, 상세 표시명은 해당 값으로 구성한다.

| 선택 예시 | 저장할 값 |
| --- | --- |
| `wide_leg_jeans` 와이드 청바지 | category=bottom, subcategory=jeans, leg_shape=wide |
| `long_sleeve_t_shirt` 긴팔 티셔츠 | category=top, subcategory=tshirt, sleeve_length=long |
| `crewneck_t_shirt` 라운드넥 반팔 | category=top, subcategory=tshirt, neckline=crew, sleeve_length=short |
| `flannel_shirt` 플라넬 셔츠 | category=top, subcategory=shirt; 사용자 선택에 근거한 material_note=플라넬 |

각 표의 category·subcategory와 명시된 시각적 특징만 프리셋으로 적용한다. 그 외 속성은 기존의 유효한 값을 유지하며 추정 기본값을 추가하지 않는다. 구현할 프리셋의 변환 규칙은 FE에서 명시하고 계약 검사로 검증한다. 프리셋 원본 코드는 API에 전송하지 않는다.

소재를 포함하는 종류는 사용자의 직접 선택에 한해 소재 메모를 제안하며, 기존 메모가 있으면 덮어쓰기 전 확인한다. AI는 material_note를 생성하거나 사진만으로 플라넬·시폰·피케 등의 소재를 확정할 수 없다. 시각적으로 불명확한 경우 상위 종류만 반환한다. 데님처럼 통상적인 종류 표현도 원단 성분·혼용률을 의미하지 않는다.

한국어 표시명으로 검색하며, 상세 항목을 생략하고 상위 종류만 선택할 수 있다. 속성을 수정하면 표시명을 현재 값에 맞춰 갱신하므로 프리셋 이름과 속성을 중복 저장하지 않는다.

## 1. 상의 — `top`

### 티셔츠·기본 탑 — `tshirt`

| 코드 | 표시명 | 분류 기준 |
| --- | --- | --- |
| `crewneck_t_shirt` | 라운드넥 반팔 티셔츠 | 둥근 넥라인, 반팔 |
| `v_neck_t_shirt` | 브이넥 반팔 티셔츠 | V자 넥라인, 반팔 |
| `long_sleeve_t_shirt` | 긴팔 티셔츠 | 긴팔 기본 티셔츠, 넥라인 별도 |
| `sleeveless_top` | 민소매 상의 | 소매가 없는 상의의 넓은 분류 |
| `tank_top` | 탱크탑 | 어깨 끈이 있는 민소매 탑; 해당 형태가 확인된 경우 sleeveless_top보다 우선 적용 |
| `cropped_top` | 크롭탑 | 짧은 몸판의 기본 탑; 구체적 종류가 있으면 그 종류와 length로 표현 |
| `henley_neck_shirt` | 헨리넥 티셔츠 | 칼라 없이 목 아래 짧은 단추 여밈 |
| `pique_polo_shirt` | 피케 폴로 셔츠 | 칼라·짧은 단추 여밈의 피케 조직 폴로 |
| `polo_shirt` | 폴로 셔츠 | 폴로 형태는 알지만 피케 조직은 확인하지 못함 |

### 셔츠 — `shirt`

| 코드 | 표시명 | 분류 기준 |
| --- | --- | --- |
| `casual_button_down_shirt` | 캐주얼 셔츠·남방 | 본 프로젝트에서 앞 단추 여밈의 캐주얼 셔츠로 정의함. 칼라 끝 단추 여부를 강제하지 않음 |
| `formal_dress_shirt` | 드레스 셔츠 | 정장에 사용하는 셔츠 |
| `open_collar_shirt` | 오픈칼라 셔츠 | 목 부분이 열린 칼라 |
| `flannel_shirt` | 플라넬 셔츠 | 플라넬 원단 확인 필요. 체크무늬만 보고 선택하지 않음 |
| `denim_shirt` | 데님 셔츠 | 데님 소재의 셔츠 |
| `collarless_shirt` | 노카라 셔츠 | 접히는 칼라가 없는 셔츠 |

### 블라우스 — `blouse`

| 코드 | 표시명 | 분류 기준 |
| --- | --- | --- |
| `chiffon_blouse` | 시폰 블라우스 | 얇고 비치는 시폰 조직의 블라우스 |
| `ruffle_blouse` | 러플·프릴 블라우스 | 러플 장식이 있는 블라우스 |

시폰과 러플이 동시에 있으면 사용자가 고른 대표 종류를 저장하고 다른 특징은 사용자 지정 material_note 또는 details에 기록한다.

### 스웨트셔츠 — `sweatshirt`

| 코드 | 표시명 | 분류 기준 |
| --- | --- | --- |
| `crewneck_sweatshirt` | 맨투맨 | 둥근 넥라인의 스웨트셔츠 |
| `half_zip_up_sweatshirt` | 하프집업 맨투맨 | 목에서 가슴까지 부분 지퍼 |
| `collar_sweatshirt` | 카라 맨투맨 | 칼라가 달린 스웨트셔츠 |

### 후디 — `hoodie`

| 코드 | 표시명 | 분류 기준 |
| --- | --- | --- |
| `hoodie_pullover` | 풀오버 후드티 | 앞 전체가 열리지 않는 후드 상의 |

후드 집업은 분류 중복 방지를 위해 outerwear에 배정한다. 실제 착용 시 레이어 역할은 별도 속성으로 관리한다.

### 니트웨어 — `knitwear`

| 코드 | 표시명 | 분류 기준 |
| --- | --- | --- |
| `crewneck_knit_sweater` | 라운드넥 니트 | 둥근 넥라인 |
| `v_neck_knit_sweater` | 브이넥 니트 | V자 넥라인 |
| `turtleneck_knit` | 터틀넥·목폴라 니트 | 높게 올라와 접을 수 있는 목 부분 |
| `mock_neck_knit` | 반목·모크넥 니트 | 접지 않는 짧은 높은 목 부분 |
| `knit_vest` | 니트 조끼 | 소매 없는 니트 |
| `cable_knit_sweater` | 케이블·꽈배기 니트 | 꼬인 줄 모양의 편직 무늬; 넥라인 별도 |

## 2. 하의 — `bottom`

### 데님 팬츠 — `jeans`

| 코드 | 표시명 | 분류 기준 |
| --- | --- | --- |
| `skinny_jeans` | 스키니진 | 다리 전체에 밀착되는 실루엣 |
| `slim_fit_jeans` | 슬림핏 청바지 | 비교적 좁게 떨어지는 실루엣 |
| `straight_fit_jeans` | 일자 청바지 | 다리통이 비교적 일정함 |
| `wide_leg_jeans` | 와이드 청바지 | 넓은 다리통 |
| `bootcut_jeans` | 부츠컷 청바지 | 무릎 아래에서 밑단이 넓어짐 |
| `tapered_jeans` | 테이퍼드 청바지 | 밑단으로 갈수록 좁아짐 |
| `cargo_jeans` | 카고 청바지 | 옆면의 카고 포켓; 다리 실루엣 별도 |

### 슬랙스 — `slacks`

| 코드 | 표시명 | 분류 기준 |
| --- | --- | --- |
| `straight_slacks` | 일자 슬랙스 | 일자 실루엣 |
| `wide_slacks` | 와이드 슬랙스 | 넓은 다리통 |
| `tapered_slacks` | 테이퍼드 슬랙스 | 밑단으로 갈수록 좁아짐 |
| `cropped_slacks` | 크롭·9부 슬랙스 | 짧게 설계된 기장, 사용자 발목 위치는 별도 판단 |
| `semi_wide_slacks` | 세미 와이드 슬랙스 | 일자보다 넓고 와이드보다 좁은 디자인 분류 |

### 캐주얼 팬츠 — `pants`

| 코드 | 표시명 | 분류 기준 |
| --- | --- | --- |
| `chino_pants` | 치노 팬츠 | 치노 계열 바지. 면 소재라는 이유만으로 모두 포함하지 않음 |
| `cargo_pants` | 카고 팬츠 | 카고 포켓이 특징인 바지 |
| `parachute_pants` | 파라슈트 팬츠 | 부피감 있는 형태·조임 디테일 등이 특징인 바지 |
| `corduroy_pants` | 코듀로이·골덴 바지 | 세로 골이 있는 코듀로이 원단 |
| `leather_pants` | 가죽 느낌 바지 | 천연·합성 가죽의 구분은 라벨 확인 전 확정하지 않음 |

### 트레이닝·홈웨어 — `active_pants`

| 코드 | 표시명 | 분류 기준 |
| --- | --- | --- |
| `sweatpants_jogger` | 조거 스웨트팬츠 | 밑단이 모이는 스웨트팬츠 |
| `sweatpants_wide` | 와이드 스웨트팬츠 | 넓은 통의 스웨트팬츠 |
| `track_pants` | 트랙팬츠 | 운동복 계열 트랙 바지. 사이드라인 유무는 별도 |
| `leggings` | 레깅스 | 신축성 있는 밀착형 하의 |
| `pajama_pants` | 파자마 바지 | 잠옷 용도의 바지 |

### 반바지 — `shorts`

| 코드 | 표시명 | 분류 기준 |
| --- | --- | --- |
| `denim_shorts` | 데님 반바지 | 데님 원단의 반바지 |
| `chino_shorts` | 치노 반바지 | 치노 계열 반바지 |
| `sweat_shorts` | 스웨트 반바지 | 스웨트 원단 반바지 |
| `bermuda_shorts` | 버뮤다 팬츠 | 무릎 부근까지 오는 디자인의 반바지 |
| `short_shorts` | 숏팬츠·핫팬츠 | 짧은 디자인의 반바지 |

소재·기장에 따라 복수 종류에 해당하는 경우 대표 종류 하나를 저장하고 추가 특징은 사용자 지정 material_note·length로 표현한다.

### 스커트 — `skirt`

| 코드 | 표시명 | 분류 기준 |
| --- | --- | --- |
| `straight_skirt` | 일자 스커트 | 비교적 곧게 떨어지는 형태 |
| `a_line_skirt` | A라인 스커트 | 밑단으로 갈수록 넓어짐 |
| `pleated_skirt` | 플리츠 스커트 | 반복 주름이 있는 스커트 |
| `flared_skirt` | 플레어 스커트 | 풍성하게 퍼지는 형태 |
| `denim_skirt` | 데님 스커트 | 데님 원단의 스커트 |

## 3. 아우터 — `outerwear`

상의와 분류가 중복되는 의류는 다음 표에 따라 단일 카테고리에 배정한다. 레이어 역할은 이번 등록 스키마에서 별도로 저장하지 않는다.

| subcategory | 프리셋 코드 | 표시명 | 분류 기준 |
| --- | --- | --- | --- |
| `hooded_jacket` | `hoodie_zip_up` | 후드 집업 | 앞 전체 지퍼, 후드 |
| `cardigan` | `knit_cardigan` | 니트 카디건 | 앞 여밈이 있는 니트 겉옷 |
| `jacket` | `anorak_jacket` | 아노락 | 풀오버·부분 여밈 형태의 아우터 |
| `jacket` | `track_top_jacket` | 트랙탑·저지 | 트랙 운동복 상의 |
| `fleece` | `fleece_zip_up` | 플리스 집업 | 플리스 조직, 지퍼 여밈 |
| `jacket` | `shirt_jacket` | 셔켓·셔츠형 재킷 | 셔츠 형태의 겉옷 |
| `jacket` | `denim_jacket` | 데님 재킷 | 데님 겉옷 |
| `jacket` | `bomber_jacket` | 봄버 재킷 | 짧은 몸판·시보리 등이 특징 |
| `jacket` | `leather_jacket` | 가죽 느낌 재킷 | 천연·합성 구분은 별도 확인 |
| `jacket` | `blazer` | 블레이저 | 테일러드 재킷 형태 |
| `jacket` | `windbreaker` | 바람막이 | 얇은 외피형 겉옷, 실제 방풍 성능은 별도 |
| `coat` | `trench_coat` | 트렌치코트 | 트렌치 형태의 코트 |
| `coat` | `single_breasted_coat` | 싱글 코트 | 한 줄 단추 배열의 코트 |
| `coat` | `double_breasted_coat` | 더블 코트 | 두 줄 단추 배열의 코트 |
| `puffer` | `puffer_jacket` | 패딩 재킷 | 충전재 있는 겉옷, 충전재 성분은 별도 |
| `vest` | `puffer_vest` | 패딩 조끼 | 소매 없는 패딩 |

## 4. 신발 — `shoes`

| subcategory | 프리셋 코드 | 표시명 | 분류 기준 |
| --- | --- | --- | --- |
| `sneakers` | `low_top_sneakers` | 로우탑 스니커즈 | 발목 아래 높이 |
| `sneakers` | `high_top_sneakers` | 하이탑 스니커즈 | 발목을 덮는 형태 |
| `sneakers` | `running_shoes` | 러닝화 | 러닝화 종류, 실제 운동 적합성 보증 아님 |
| `loafers` | `penny_loafers` | 페니 로퍼 | 페니 장식 형태 |
| `loafers` | `tassel_loafers` | 태슬 로퍼 | 술 장식 형태 |
| `boots` | `chelsea_boots` | 첼시 부츠 | 측면 밴드 형태 |
| `boots` | `lace_up_boots` | 레이스업 부츠 | 끈 여밈 부츠 |
| `sandals` | `strap_sandals` | 스트랩 샌들 | 끈으로 발을 고정 |
| `sandals` | `slides` | 슬라이드·슬리퍼 | 뒤꿈치가 열린 슬라이드 형태 |
| `dress_shoes` | `oxford_shoes` | 옥스퍼드 구두 | 옥스퍼드 여밈 형태 |
| `dress_shoes` | `derby_shoes` | 더비 구두 | 더비 여밈 형태 |
| `flats` | `ballet_flats` | 발레 플랫 | 낮은 굽의 발레 슈즈 형태 |
| `heels` | `pumps` | 펌프스 | 펌프스 형태의 구두 |

## 5. 미분류 및 확장 규칙

- subcategory는 해당 category의 표에 명시된 그룹 또는 other, null을 허용한다. 목록 밖 종류임이 확인된 경우 other, 판별하지 못한 경우 null로 저장한다.
- 프리셋 목록이 모든 속성 조합을 열거하지는 않는다. 소매·넥라인·실루엣 등 독립 속성으로 다양한 조합을 표현한다.
- 원피스·점프슈트·액세서리는 현행 네 카테고리에 포함하지 않는다. 추천 슬롯과 UI 필터를 확장할 때 추가한다.
- 프리셋 변경은 FE 검색·선택·표시 및 정규화 규칙에 반영한다. 저장 category·subcategory·속성의 변경은 BE 검증과 추천 계약도 함께 변경한다.
