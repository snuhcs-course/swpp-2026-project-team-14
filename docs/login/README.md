# 로그인 필수 서비스 요구사항과 API 계약

`main-merge-1017`에서 사용자 요청에 따라 선택적 로그인을 로그인 필수 흐름으로 전환했다.

## 앱 진입과 인증

- 앱 시작 시 암호화된 세션을 복원한다. 유효한 세션이 없으면 로그인 화면만 표시하며 회원가입으로 이동할 수 있다.
- 회원가입 성공 시 자동 로그인한다. 로그인 후 계정별 온보딩을 진행하고 홈·옷장·코디북·마이프로필에 진입한다.
- 비밀번호는 화면의 일반 메모리에만 보관하며 저장 가능한 화면 상태나 로컬 파일에 저장하지 않는다.
- 앱 재실행 시 로그인을 유지하고 포그라운드 복귀 시 내 정보 API로 세션을 확인한다.
- 로그아웃·저장된 만료 시각 경과·인증 요청 401 시 토큰과 서비스 화면 상태를 해제하고 로그인 화면으로 돌아간다.
- 네트워크·서버 오류만으로 로그인 상태를 지우지 않는다. 로그아웃 요청 실패 시 상태를 유지하고 재시도를 제공한다.
- 이전 계정의 늦은 응답이 새 세션을 해제하거나 덮어쓸 수 없다.

## 계정 규칙

- 아이디: 영문 소문자·숫자·밑줄 4~20자, 중복 불가.
- 비밀번호: 8~64자, 가입 시 확인 일치. Django 해시로 저장하며 원문을 저장하지 않는다.
- 인증: 30일 유효 DB 기반 Bearer 토큰. 서버에는 SHA-256 해시만 저장하고 로그아웃 시 현재 토큰을 폐기한다.
- Android 세션: Keystore AES-256-GCM으로 암호화한 noBackupFilesDir/auth-session.enc.
- 소셜 로그인·이메일·전화번호·비밀번호 복구·계정 삭제는 추가하지 않는다.

## 공개 API

| API | 역할 |
| --- | --- |
| `POST /api/auth/register/` | username, password, password_confirmation으로 가입. 토큰·만료 시각·사용자 정보 반환 |
| `POST /api/auth/login/` | username, password로 로그인. 토큰·만료 시각·사용자 정보 반환 |
| `GET /healthz/` | 배포 readiness/liveness 확인 |

서비스 API에는 `Authorization: Bearer <token>`이 필요하다. 누락·잘못된·만료·폐기 토큰은 401이며, DB 미설정은 503이다.
다른 계정의 데이터 ID는 404로 처리한다.

## 유지하는 인증 API

| API | 역할 |
| --- | --- |
| `POST /api/auth/logout/` | 현재 토큰 폐기 |
| `GET/PATCH /api/users/me/` | 현재 사용자 및 체형·선호 핏·무드·컬러 조회/수정 |
| `POST /api/body-profile/analyze/` | 정면·측면 사진으로 체형 분석 |
| `POST /api/wardrobe/analyze/` | 사진 분석 후 해당 계정의 미저장 옷 초안 생성 |
| `POST /api/wardrobe/landmarks/` | AR 측정용 의류 특징점 탐지 |
| `GET /api/wardrobe/options/` | 의류 편집 선택지 |
| `POST /api/wardrobe/labels/` | 의류 라벨 계산 |
| `GET /api/wardrobe/items/` | 본인 옷장 목록 |
| `GET/PUT /api/wardrobe/items/<id>/` | 본인 옷 조회·편집·등록 확정 |
| `GET /api/wardrobe/items/<id>/image/` | 본인 의류 사진 |
| `GET /api/weather/` | 추천용 날씨 |
| `POST /api/outfits/recommend/` | 본인 옷장의 추천 코디 생성 |
| `GET/POST /api/outfits/` | 코디북 목록·수동/채팅 코디 저장 |
| `GET/PUT /api/outfits/<id>/` | 코디 조회·저장 여부·이름 수정 |
| `POST /api/outfits/<id>/feedback/` | 좋아요·별로예요와 추천 취향 계산 |

## 기존 데이터 가져오기 유지

`/api/users/me/import/preview/`, `/api/users/me/import/profile/<section>/`, `/api/users/me/import/garments/<source_id>/`, `/api/users/me/import/outfits/<source_id>/`는 원래 인증 API이므로 유지한다. 마이프로필에서 사용자가 수동으로 실행하며, 기존 guest 스냅샷의 체형·스타일·옷·코디를 동의 후 계정으로 가져온다. 충돌 시 교체 여부 선택, 재시도 중복 방지, 실패 시 원본 보존과 계정 분리를 유지한다. 가져오기는 비로그인 서비스 이용을 허용하지 않는다.

## 제거한 경로

- `/api/wardrobe/analyze-preview/`
- `/api/outfits/preview/`
- `/api/outfits/preview/feedback/`
- 사용하지 않는 `/api/hello/`

위 경로는 더 이상 라우팅되지 않는다. 공통 분석·추천 알고리즘과 계정 저장 기능은 유지한다.
기존 DB 마이그레이션을 수정하거나 사용자 데이터를 삭제하지 않는다.

## 계정 저장과 온보딩

- 서버 User가 체형·선호 핏·무드·컬러를 저장하고 Garment/Outfit은 사용자 소유 외래 키로 구분한다.
- 계정별 로컬 캐시는 filesDir/local-users/<user UUID>/data.json에 보관한다. 새 서비스 데이터에 게스트 영역을 사용하지 않는다. 이전 버전의 guest 스냅샷은 로그인한 사용자가 수동 가져오기를 선택할 때만 읽는다.
- 추천 취향 가중치와 온보딩 진행·초안은 계정별 로컬 캐시에 유지한다. 추천 알고리즘과 취향의 서버 저장 범위를 새로 확장하지 않는다.
- 계정 프로필 갱신은 진행 중인 온보딩을 덮어쓰거나 새 계정의 온보딩을 자동 완료하지 않는다.
- 온보딩은 로그인 뒤 환영 → 핏 → 무드 → 선택적 체형 분석 → 결과 확인 → 옷장 등록 → 의류 편집 → 완료 순서다.
- 핏·무드는 다음 단계로 이동하기 전에 프로필 API에 저장한다. 실패하면 현재 단계에서 재시도한다.
- 체형 분석과 옷 등록은 건너뛸 수 있다. 선호 스타일만 저장하고 완료할 수도 있다.
- 사진은 기존 정책을 유지한다. 체형 사진은 보관하지 않고 의류 사진은 계정 API로 보관한다.
- 옷장 목록과 사진·편집·추천·피드백·코디북은 계정 경로를 사용한다. 기존 상의·하의·아우터 추천과 신발 등록을 유지한다.
- 마이프로필에서는 계정 아이디·로그아웃·핏·무드·컬러 편집·체형 분석/삭제를 제공한다. 로그인 카드·게스트 안내는 제거한다. 기존 데이터 가져오기 아이콘과 충돌 확인·재시도는 로그인 후 기능으로 유지한다.

## 기존 보안·목록 계약

- 같은 아이디의 5분 내 연속 로그인 실패 5회부터 5분간 제한한다. 가입·로그인은 같은 IP에서 합산 분당 20회로 제한한다.
- 제한 응답은 429와 Retry-After/retry_after를 제공한다. 프록시에서는 REMOTE_ADDR 전달 구성을 확인해야 한다.
- 프로필 PATCH는 body_profile, preferred_fits, preferred_styles, favorite_colors만 허용하며 기존 값·크기 검증을 유지한다.
- 계정 옷·코디 목록은 기존 after/next 커서 및 응답 크기 제한을 유지한다. 모든 페이지 성공 후에만 목록 캐시를 교체한다.
- 코디에는 본인 의류만 연결하며, 연결된 옷의 분류 변경은 기존 409 정책을 유지한다.

## 검증

- backend: manage.py test apps.users apps.wardrobe apps.outfits --settings=config.test_settings --noinput
- backend: python -m pytest
- frontend: testDebugUnitTest, assembleDebug, lintDebug 및 관련 Android UI 테스트
- 테스트는 격리된 DB·임시 파일·외부 API 대역을 사용한다. 실제 Gemini 호출과 운영 MySQL 마이그레이션은 자동 테스트에 포함하지 않는다.
