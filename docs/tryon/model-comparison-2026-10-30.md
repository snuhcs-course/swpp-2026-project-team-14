# 가상 피팅 모델 비교와 품질 우선 전환

최종 정리: 2026년 11월 5일.

**학교 GPU와 무료 API에서 속도·품질의 균형을 찾던 방향에서, 대기시간 제약을 완화하고 품질을 우선하는 방향으로 전환했다.** FASHN을 50스텝으로 높여도 남는 아우터 합성 문제는 유료 이미지 편집 API를 도입한 테스트에서 개선했다. Nano Banana 2.1과 GPT Image 2.6 Sunburst는 검증한 사례에서 기존 코트 제거·새 아우터 교체를 모두 처리했다.

**develop 기반 피팅 작업본은 Nano Banana 2.1(OpenRouter, 2K)로 전환했다.** FASHN GPU 엔진·별도 워커·프록시·마스크 합성을 제거하고 Django가 직접 호출한다. 아래 FASHN 수치는 교체 전 기록이며, Nano Banana의 앱 연동 API 검증은 별도 항목에 정리했다.

## 검토 방향의 변화

1. **학교 GPU·무료 API로 대기시간과 품질을 함께 조절했다.** 제공받은 학교 A100 GPU에서 FASHN·CatVTON 등 자체 추론 모델을 비교하고, 저해상도·저스텝·배치 처리와 Cloudflare 무료 API를 시험했다. 빠른 모델에서는 얼굴·체형·무늬가 바뀌거나 의류 적용이 누락됐고, 학교 GPU는 할당 대기로 즉시 사용이 어려운 경우도 있었다.
2. **짧은 대기시간보다 품질을 우선했다.** 10초 이내 목표를 필수 제약에서 제외하고 FASHN을 BF16, 576 × 864, 의류별 50스텝으로 높였다. 앱은 생성 중 다른 기능을 사용하고 완료 알림으로 결과를 확인하는 비동기 흐름으로 바꿨다. 그러나 스텝 증가만으로 아우터의 팔·어깨·앞섶이 잘리는 마스크 합성 오류는 해결되지 않았다.
3. **아우터 문제를 해결하기 위해 유료 모델을 도입해 검증했다.** OpenRouter를 통해 인물·상의·하의·아우터 사진을 한 요청에 전달했다. Nano Banana와 GPT는 재킷·가디건 착장뿐 아니라 코트를 입고 앉아 있는 인물의 기존 코트 제거·새 가디건 교체도 성공했다. 이 사례들에서는 FASHN의 잘린 아우터 경계가 개선됐다. 다만 얼굴·무늬의 일부 재생성과 제공사 필터 차단은 남아 있어, 모든 입력에서 해결됐다는 의미는 아니다.

## 직접 실행시간·품질 비교

시간은 **앱 버튼·WorkManager·알림 대기시간을 제외하고, 스크립트에서 직접 API를 호출해 최종 이미지를 받기까지**의 관측값이다. 모델 적재가 끝난 상태를 기준으로 하며, FASHN의 GPU 할당·설치·초기 적재는 제외했다. API 시간에는 전송·제공사 처리·결과 수신이 포함된다. 입력 사례·해상도가 달라 같은 조건의 속도 순위를 뜻하지 않는다.

| 모델 | 실행시간 | 피크 VRAM (GiB) | 퀄리티 부족점 | 10개 동시 요청 | 아우터 기능 |
| --- | --- | --- | --- | --- | --- |
| **[FASHN VTON 1.5](https://huggingface.co/fashn-ai/fashn-vton-1.5)** | 상하의 **23.3초**, 아우터 포함 **34.7~35.9초** | 10.39 | 팔·어깨·앞섶의 잘림, 합성 경계 오류 | 서버 처리 **217.9초** | 의류별 순차 추론·마스크 합성, 오류 잔존 |
| [Cloudflare FLUX.2 Klein 4B](https://developers.cloudflare.com/workers-ai/models/flux-2-klein-4b/) | 상하의 **4.3초**, 아우터 포함 **9.6초** | API 미제공 | 그래픽 글자 변형, 인물·체형 일부 변화 | 미측정 | 2회 편집으로 성공, 1회 생성 시도 2건은 필터 차단 |
| **[Nano Banana 2.1](https://openrouter.ai/google/gemini-nano-banana-2.1)** | 상하의 **46.4초**, 아우터 포함 **23.3~37.8초** | API 미제공 | 얼굴·핏·청바지 무늬 등 일부 재해석 | **105.3초** · Django에서 2개씩 호출 | 기존 코트 제거·교체 포함 **4/4건 성공** |
| **[GPT Image 2.6 Sunburst](https://openrouter.ai/openai/gpt-image-2.6-sunburst)** | 상하의 **42.1초**, 아우터 포함 **37.2~41.3초** | API 미제공 | 얼굴·구도·의류 세부가 원본과 완전히 같지는 않음 | 미측정 | 기존 코트 제거·교체 포함 **4/4건 성공** |
| [Seedream 5.0 Pro](https://openrouter.ai/bytedance-seed/seedream-5-0-pro) | 상하의 **39.9초**, 아우터 포함 **43.8~45.1초** | API 미제공 | 세부 무늬 변형, 일부 입력의 생성 차단 | 미측정 | **3/4건 성공**, 코트→가디건 교체 1건은 필터 차단 |

- **FASHN 시간 검증:** Python 직접 호출 기록의 상하의 `23.26초`, 재킷 `35.86초`, 가디건 `34.70초`를 사용했다. GPU 서버의 추론 구간은 각각 `22.42초`, `34.62초`, `33.85초`다. 후자는 전처리·의류별 추론·아우터 합성을 포함하는 서버 처리 구간으로, API 전체 응답시간과 구분한다. 앱에서 측정했던 약 24초는 이 표에 사용하지 않았다.
- **FASHN 자원·10건 처리:** A100 40GB, BF16, 576 × 864, 50스텝이다. VRAM은 10건 시험을 포함한 CUDA 예약 메모리 최대값이며 실제 할당 최대값은 `5.63 GiB`다. 10건은 `2→4→4` 배치로 처리했고 서버 로그의 첫 배치 시작부터 마지막 완료까지 `217.94초`였다. 요청은 Android 클라이언트 10개로 발생시켰지만 표의 시간은 서버 로그만 사용했다. 아우터 포함 10건은 미측정이다.
- **무료 API:** Cloudflare 무료 플랜의 아우터 `9.609초`는 상하의 생성 `4.263초`와 아우터 추가 `5.346초`를 합한 값이다. 연속된 앱 작업을 한 타이머로 측정한 값은 아니다. 입력 사진은 축소 전처리됐고 출력은 576 × 864, 4스텝이다.
- **유료 API:** Nano Banana는 2K 요청·1696 × 2528 출력, GPT는 high·1024 × 1536, Seedream은 2K·1366 × 2048이다. 상하의 시간은 **기존 코트를 제거하는 앉은 인물 조건**의 각 1회 값이며, 아우터 범위는 서 있는 인물 2건과 앉은 인물 1건의 성공값이다. 아우터가 더 빠르게 나온 값을 일반적인 속도 관계로 해석하지 않는다. Seedream 차단 응답 `40.8초`는 생성시간에서 제외했다.
- 외부 API는 학교 GPU를 사용하지 않으며 제공사의 GPU·VRAM은 이번 응답에서 확인하지 못했다. 로컬 GPU를 쓰지 않는다는 이유로 모델 VRAM을 0으로 기재하지 않는다.

### 기존 아우터가 있는 인물 사진의 추가 검증

같은 코트 착용·앉은 자세 사진과 네이비 그래픽 티셔츠·청바지를 사용했다. 아우터 조건에서는 회색 스누피 가디건을 추가했다. 두 조건 모두 원본 사진에서 시작하며 **결과 한 장당 API 1회 호출**이다.

| 모델 | 기존 코트 제거 → 상하의만 | 기존 코트 → 새 가디건으로 교체 |
| --- | --- | --- |
| Nano Banana 2.1 | 46.4초 · 성공 | 29.0초 · 성공 |
| GPT Image 2.6 Sunburst | 42.1초 · 성공 | 39.5초 · 성공 |
| Seedream 5.0 Pro | 39.9초 · 성공 | 40.8초 후 제공사 필터 차단 · 이미지 없음 |

초기 유료 모델 비교 실험 12건 중 11건에서 이미지가 반환됐고, 보고된 요청별 비용 합계는 **$0.8253**이다. 제한된 입력의 탐색 결과이며 일반적인 성공률이나 10명 동시 사용 성능을 입증한 값은 아니다.

## 모델 선택과 앱 연동 상태

- **품질 우선 후속 후보:** Nano Banana 2.1과 GPT Image 2.6 Sunburst. 이번 사례에서는 둘 다 아우터 문제를 개선했고, GPT는 세부 표현, Nano Banana는 일부 조건의 응답시간에서 유리해 보였다. 정량 품질 점수나 보편적인 우열은 확정하지 않았다.
- **현재 구현·빌드:** 오늘의 코디·코디북에서 Nano Banana 2.1을 비동기로 호출한다. GPU·별도 피팅 서버 없이 기존 Django CPU 서버에서 처리하며, 인물과 모든 의류 사진을 한 요청에 넣는다. 계정별 결과 저장·완료 알림·결과 팝업은 유지한다. 폰 재설치는 별도다.
- **유료 API 경로:** 인물·상의·하의·아우터 사진을 함께 넣어 한 번에 생성한다. 상하의 결과에 아우터를 다시 씌우는 2회 호출 방식은 이번 유료 테스트에 사용하지 않았다. Cloudflare의 성공 사례는 2회 호출 방식이다.

## Nano Banana 앱 연동 API 검증

새 DB·새 계정으로 Django API를 실행했으며 이전 DB·결과를 이관하지 않았다. 인물·상의·하의 원본과 선택적 가디건을 사용하고, GPU 서버는 사용하지 않았다.

| 조건 | 직접 API 호출 → 결과 수신 | 서버 생성 구간 |
| --- | --- | --- |
| 상하의 1건 | 24.3초 | 24.0초 |
| 아우터 포함 1건 | 22.3초 | 21.9초 |
| 상하의 10건 동시 접수 | 첫 결과 19.8초 · 전체 105.3초 | 외부 API 2개씩 처리 |

12건 모두 1696 × 2528 JPEG를 반환했다. 10건은 백엔드 큐에 동시에 접수한 시험이며 10대 폰 시험은 아니다. 계정 간 결과 조회·삭제 차단, 처리 후 StyleMate 서버 입력 삭제, 결과 수신 후 서버 작업 삭제를 검증했다. 자동 생성 재시도·GPU 대체 경로·DB 호환 코드는 없다. 이번 통합 검증 비용은 $0.7007이며 초기 비교까지 누적 $1.5260으로 $3 한도 내다.

## 이전 후보 검토 기록

<details>
<summary>속도·메모리 최적화 단계에서 비교했던 모델</summary>

아래는 이전 설정의 관측값이다. 현재 50스텝 FASHN 및 유료 API와 설정·입력·하드웨어가 다르며 재측정한 값으로 해석하지 않는다.

| 모델 | 실행시간 | 피크 VRAM (GiB) | 퀄리티 부족점 | 10개 동시 요청 | 아우터 기능 |
| --- | --- | --- | --- | --- | --- |
| FASHN VTON 1.5 · 과거 30스텝 | 상하의 16.4초, 아우터 포함 21.4초 | 7.32 | 겹침·가림 영역 및 합성 경계 오류 | 미측정 | 추가 합성으로 지원 |
| [CatVTON](https://github.com/Zheng-Chong/CatVTON) | 상하의 5.3초, 아우터 포함 미측정 | 11.67 | 데님 표면 잡음, 피부 질감 손실 | 미측정 | 레이어링 미검증 |
| [Qwen-Image 2.1 Turbo](https://huggingface.co/Qwen/Qwen-Image-2.1-Turbo) | 상하의 3.5초, 아우터 포함 3.8초 | 41.24 | 핏·작은 무늬·로고 변형 | 28.3초 | 지원 |
| [FLUX.2 Klein 4B](https://huggingface.co/black-forest-labs/FLUX.2-klein-4B) | 상하의 1.4초, 아우터 포함 1.4초 | 7.52 | 실제 제품 무늬·로고 재현 한계 | 11.2초 | JSON 기반 표현 |
| [FastFit](https://github.com/Zheng-Chong/FastFit) | 상하의 2.8초, 아우터 포함 5.3초 | 6.53 | 손·신발 변형, 이너·아우터 혼합 및 디테일 유실 | 미측정 | 실험적·불안정 |
| [DreamLite-mobile](https://github.com/ByteVisionLab/DreamLite) | 상하의 0.78초, 아우터 포함 0.78초 | 7.03 | 얼굴·자세·신발 변형 | 7.9초 | 불안정 |
| [Mobile-VTON](https://github.com/tmllab/2026_CVPR_Mobile-VTON) | 상하의 5.4초, 아우터 포함 8.0초 | 6.61 | 하의 적용 오류, 이너 소실 | 54.3초 | 불안정 |
| [VIBE-DistilledCFG](https://github.com/ai-forever/VIBE) | 상하의 0.89초, 아우터 포함 0.88초 | 10.24 | 얼굴·모자·신발·이너 색상 변형 | 8.9초 | 불안정 |
| [Google Vertex AI Virtual Try-On](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/image/generate-virtual-try-on-images) | 미측정 | — | 미평가 | 미측정 | 당시 유료 사용을 제외해 보류 |

DreamLite·Mobile-VTON·VIBE는 A100 40GB 직접 호출, 나머지는 당시 A100 80GB API 기준이다. FLUX·DreamLite·VIBE는 의류 사진 대신 속성을 사용했다. 10건 값은 마지막 완료시간이며 입력 구성이 다르다. 과거 VRAM은 적재·추론·동시 요청 중 최대값으로 필요한 최소 용량은 아니다.

추가 조사 후보는 DeCo-VTON, Leffa, LongCat Edit Turbo, FireRed Image Edit, FLUX 3 Klein, SD-Turbo·SDXS·SDXL-Turbo·DreamShaper LCM이다. 비교표에 대응하는 실행값은 없어 수치를 추가하지 않았다.

</details>

## 3D 방식과 제공 범위

Hunyuan3D·TripoSR 등은 회전 가능한 형상 생성 외에 의류 교체·체형 정합·겹침 처리가 더 필요해 현재 범위에서 제외했다. 앱은 인물 사진과 의류 사진을 활용하는 **2D 착장 미리보기**에 집중한다. 의상 JSON은 추천 코디 구성과 사진 선택에 활용하며 생성 결과는 실제 치수나 착용감의 정확성을 보장하지 않는다.

현재 앱의 사진 선택 안내에 OpenRouter·Google 전송 동의와 외부 제공사 보관 정책 적용을 명시했다. StyleMate 서버 입력은 작업 종료 시 삭제하고 결과는 앱에 수신한 뒤 서버에서 지운다. 이번 테스트가 외부 제공사의 입력 사진 즉시 삭제까지 검증한 것은 아니다. 키·원본 사진·생성 결과·가중치·K-Fashion 원본 데이터는 문서에 포함하거나 커밋하지 않는다.

## 수치 확인 자료

저장소 루트 기준의 로컬 기록이다. 본문 수치는 아래 보고서·실행 스크립트·서버 로그를 대조했으며 원본 자료는 Git에서 제외돼 있다. FASHN 수치는 기존 직접 실행 로그를 검증한 값이며 새 GPU를 할당하지 않았다. Nano Banana 전환 후에는 실제 API로 별도 통합 검증을 수행했다.

- **FASHN 직접 실행:** `backend/private/tryon2d/develop-app-20261104/`의 `smoke.py`, `smoke-report.json`, `generate_outer.py`, `outer-50step-example/report.json`, `generate_cardigan.py`, `outer-cardigan-50step-example/report.json`.
- **FASHN 서버 10건·VRAM:** 같은 폴더의 `latency-1-vs-10/metrics-after.jsonl`, `summary.json`. 앱 완료시간 대신 서버 배치 구간을 사용했다.
- **Cloudflare 무료 API:** `backend/private/tryon2d/cloudflare-free-20261101/summary.json`, `report.json`.
- **유료 API:** `backend/private/openrouter-outer-20261105/test-plan.json`, `results.json`, `run_comparison.py`, `seated-followup-summary.json`.
- **이전 비교:** `backend/private/tryon2d/comparison/summary.json` 및 `backend/private/tryon2d/stability/`의 `fashn-four-20261031T021327Z/results.json`, `fashn-30step-20261031T001850Z/results/results.json`, `photo-attributes-20261030T122326Z/api-resident/report.json`, `final-model-search-20261031T025042Z/`, `candidate-check-20261030T143136Z/summary.json`.

- **Nano Banana 통합 검증:** 기존 작업 폴더의 `backend/private/develop-tryon/source/backend/private/nanobanana-app-20261105/live-plan.json`, `live-results.json`.
