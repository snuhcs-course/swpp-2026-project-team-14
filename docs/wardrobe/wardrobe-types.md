# 옷 종류 코드

수정일: 2026-10-05 · 기준: `backend/apps/wardrobe/schema.py`의 현재 SUBCATEGORIES

`attributes.category`는 필수이며 `subcategory`는 해당 분류의 아래 코드 또는 null이다. 한국어는 표시명으로 사용한다. 상세 의류 프리셋 검색·자동 속성 조합은 현재 앱에 없다. 이름에는 사용자가 구체적인 옷 이름을 자유롭게 입력할 수 있다.

## 상의 — `top`

| subcategory | 표시명 |
| --- | --- |
| `tshirt` | 티셔츠 |
| `shirt` | 셔츠 |
| `blouse` | 블라우스 |
| `sweatshirt` | 맨투맨 |
| `hoodie` | 후드티 |
| `knitwear` | 니트웨어 |
| `other` | 기타 |

## 하의 — `bottom`

| subcategory | 표시명 |
| --- | --- |
| `jeans` | 청바지 |
| `slacks` | 슬랙스 |
| `pants` | 캐주얼바지 |
| `active_pants` | 트레이닝바지 |
| `shorts` | 반바지 |
| `skirt` | 스커트 |
| `other` | 기타 |

## 아우터 — `outerwear`

| subcategory | 표시명 |
| --- | --- |
| `cardigan` | 카디건 |
| `hooded_jacket` | 후드집업 |
| `fleece` | 플리스 |
| `jacket` | 재킷 |
| `coat` | 코트 |
| `puffer` | 패딩 |
| `vest` | 조끼 |
| `other` | 기타 |

## 신발 — `shoes`

| subcategory | 표시명 |
| --- | --- |
| `sneakers` | 스니커즈 |
| `loafers` | 로퍼 |
| `boots` | 부츠 |
| `sandals` | 샌들 |
| `dress_shoes` | 구두 |
| `flats` | 플랫 |
| `heels` | 힐 |
| `other` | 기타 |

카디건·후드 집업·셔켓은 outerwear에 속한다. AI는 subcategory를 자동 생성하지 않으며 사용자가 편집한다. 핏·스타일·하의 형태는 종류와 독립된 선택 속성이다. 예를 들어 와이드 청바지는 category=bottom, subcategory=jeans, leg_shape=wide로 표현한다.

현재 원피스·점프슈트·액세서리는 Gemini 분석에서 지원하지 않는다. 신발은 대응하는 한 쌍을 한 항목으로 분석하며 의류 실측 항목은 없다.

필드·허용값의 전체 계약은 [데이터 명세](wardrobe-spec.md), 화면은 [디자인](design.md)을 따른다.
