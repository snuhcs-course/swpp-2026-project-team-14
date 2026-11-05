# Virtual Try-On Model Comparison and the Shift Toward Quality

Last updated: 2026-11-05.

**The project moved from balancing speed and quality on the school GPU and free APIs to prioritizing quality with a relaxed latency target.** Increasing FASHN to 50 steps did not resolve its outerwear compositing issues. Paid image-editing APIs improved the tested cases: Nano Banana 2.1 and GPT Image 2.6 Sunburst both removed existing coats and applied replacement outerwear.

**The develop-based fitting implementation now uses Nano Banana 2.1 through OpenRouter at 2K.** Django calls the API directly; the FASHN GPU engine, separate worker, proxy and mask compositing have been removed. FASHN measurements below describe the previous implementation. Nano Banana integration measurements are reported separately below.

## Model Comparison

Times are observations from **direct API calls made by scripts until the final image was received**, excluding app interaction, WorkManager scheduling and notification delays. Models were already loaded; FASHN GPU allocation, installation and initial loading are excluded. API times include transmission, provider processing and result download.

Candidates from the earlier comparison are included in this table. Earlier measurements used different settings, inputs and hardware; they are historical results, not new measurements under a common benchmark. The two FASHN rows retain their separate settings. These values do not establish a speed ranking under identical conditions.

| Model | Generation time | Peak VRAM (GiB) | Quality limitations | 10 simultaneous requests | Outerwear support |
| --- | --- | --- | --- | --- | --- |
| **[Nano Banana 2.1](https://openrouter.ai/google/gemini-nano-banana-2.1)** | Top + bottom **46.4 s**; with outerwear **23.3–37.8 s** | Not disclosed by API | Some reinterpretation of faces, fit and denim patterns | **105.3 s**; Django sends 2 requests at a time | **4/4 cases succeeded**, including existing-coat removal and replacement |
| **[GPT Image 2.6 Sunburst](https://openrouter.ai/openai/gpt-image-2.6-sunburst)** | Top + bottom **42.1 s**; with outerwear **37.2–41.3 s** | Not disclosed by API | Faces, framing and garment details are not perfectly preserved | Not measured | **4/4 cases succeeded**, including existing-coat removal and replacement |
| [Seedream 5.0 Pro](https://openrouter.ai/bytedance-seed/seedream-5-0-pro) | Top + bottom **39.9 s**; with outerwear **43.8–45.1 s** | Not disclosed by API | Fine-pattern changes; some inputs blocked by provider filters | Not measured | **3/4 cases succeeded**; 1 coat-to-cardigan replacement was blocked |
| **[FASHN VTON 1.5](https://huggingface.co/fashn-ai/fashn-vton-1.5)** | Top + bottom **23.3 s**; with outerwear **34.7–35.9 s** | 10.39 | Clipped sleeves, shoulders and front panels; compositing boundary errors | Server processing **217.9 s** | Sequential garment inference and mask compositing; errors remain |
| FASHN VTON 1.5, earlier 30-step run | Top + bottom 16.4 s; with outerwear 21.4 s | 7.32 | Occlusion artifacts and compositing boundary errors | Not measured | Supported through additional compositing |
| [Cloudflare FLUX.2 Klein 4B](https://developers.cloudflare.com/workers-ai/models/flux-2-klein-4b/) | Top + bottom **4.3 s**; with outerwear **9.6 s** | Not disclosed by API | Distorted graphic lettering; some changes to identity and body proportions | Not measured | 2 edits succeeded; 2 attempts using 1 edit were blocked by provider filters |
| [CatVTON](https://github.com/Zheng-Chong/CatVTON) | Top + bottom 5.3 s; with outerwear not measured | 11.67 | Denim noise and loss of skin texture | Not measured | Layering not verified |
| [Qwen-Image 2.1 Turbo](https://huggingface.co/Qwen/Qwen-Image-2.1-Turbo) | Top + bottom 3.5 s; with outerwear 3.8 s | 41.24 | Changes to fit, small patterns and logos | 28.3 s | Supported |
| [FLUX.2 Klein 4B](https://huggingface.co/black-forest-labs/FLUX.2-klein-4B) | Top + bottom 1.4 s; with outerwear 1.4 s | 7.52 | Limited fidelity to actual product patterns and logos | 11.2 s | Attribute JSON provides the clothing description |
| [FastFit](https://github.com/Zheng-Chong/FastFit) | Top + bottom 2.8 s; with outerwear 5.3 s | 6.53 | Distorted hands and shoes; mixed inner and outer layers; lost details | Not measured | Experimental and unstable |
| [DreamLite-mobile](https://github.com/ByteVisionLab/DreamLite) | Top + bottom 0.78 s; with outerwear 0.78 s | 7.03 | Changes to faces, poses and shoes | 7.9 s | Unstable |
| [Mobile-VTON](https://github.com/tmllab/2026_CVPR_Mobile-VTON) | Top + bottom 5.4 s; with outerwear 8.0 s | 6.61 | Incorrect bottom application and missing inner layers | 54.3 s | Unstable |
| [VIBE-DistilledCFG](https://github.com/ai-forever/VIBE) | Top + bottom 0.89 s; with outerwear 0.88 s | 10.24 | Changes to faces, hats, shoes and inner-layer colors | 8.9 s | Unstable |
| [Google Vertex AI Virtual Try-On](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/image/generate-virtual-try-on-images) | Not measured | — | Not evaluated | Not measured | Deferred because paid usage was excluded at that stage |

### Measurement Conditions

- **FASHN timing verification:** Direct Python API calls took `23.26 s` for top + bottom, `35.86 s` with a jacket and `34.70 s` with a cardigan. The corresponding GPU-server generation intervals were `22.42 s`, `34.62 s` and `33.85 s`. These intervals include preprocessing, per-garment inference and outerwear compositing, and are distinct from total API response time. The approximately 24-second app measurement was not used in the table.
- **FASHN resources and concurrent requests:** The later run used an A100 40GB GPU, BF16, 576 × 864 output and 50 steps per garment. Peak VRAM is the maximum CUDA reserved memory, including the 10-request test; peak allocated memory was `5.63 GiB`. The 10 requests ran in batches of `2→4→4`. Server logs show `217.94 s` from the first batch starting to the last batch finishing. Requests originated from 10 Android clients, but the table uses server timings only. Ten concurrent requests with outerwear were not measured.
- **Free API:** Cloudflare's free-plan outerwear time of `9.609 s` is the sum of `4.263 s` for top + bottom and `5.346 s` for adding outerwear. It was not measured as one continuous app operation. Inputs were downscaled; output was 576 × 864 with 4 steps.
- **Paid APIs:** Nano Banana used a 2K request and produced 1696 × 2528 images; GPT used high quality at 1024 × 1536; Seedream used 2K and produced 1366 × 2048 images. Each top + bottom value is a single run with **a seated person whose existing coat had to be removed**. Outerwear ranges cover successful runs with two standing-person cases and one seated-person case. Faster observed outerwear runs do not establish that adding outerwear is generally faster. Seedream's blocked response at `40.8 s` is excluded from generation times.
- **Earlier candidates:** DreamLite, Mobile-VTON and VIBE were called directly on an A100 40GB GPU; the other earlier candidates used the A100 80GB API environment available at the time. FLUX, DreamLite and VIBE used clothing attributes instead of garment photos. Ten-request values indicate the last completion time, with different input configurations. Historical VRAM values are maxima across loading, inference and concurrent requests, not minimum hardware requirements.
- External APIs do not use the school GPU. The responses did not disclose provider GPU or VRAM usage, so these values are not reported as zero.

## How the Evaluation Direction Changed

1. **Balance latency and quality using the school GPU and free APIs.** Self-hosted models including FASHN and CatVTON were compared on the provided school A100 GPU. Lower resolutions, fewer steps, batching and Cloudflare's free API were explored. Faster models sometimes changed faces, body proportions or patterns, or failed to apply garments. School GPU allocation queues also prevented immediate access in some cases.
2. **Prioritize quality over short waits.** The 10-second target was relaxed, and FASHN was increased to BF16, 576 × 864 and 50 steps per garment. The app moved to background generation with completion notifications so users could use other features while waiting. More steps alone did not fix mask-compositing errors that clipped outerwear sleeves, shoulders and front panels.
3. **Evaluate paid models to address outerwear failures.** Person, top, bottom and outerwear photos were sent together through OpenRouter. Nano Banana and GPT handled jacket and cardigan outfits, removed an existing coat from a seated person and applied a replacement cardigan. The clipped outerwear boundaries seen with FASHN improved in these cases. Some face and pattern regeneration and provider-filter blocks remained; the tests do not establish success for every input.

### Additional Tests with Existing Outerwear

The tests used the same seated-person photo with an existing coat, together with reference photos of a navy graphic T-shirt and jeans. A gray Snoopy cardigan was added for the outerwear condition. Both conditions started from the original person photo, with **one API call per output image**.

| Model | Remove existing coat; apply top + bottom | Replace existing coat with new cardigan |
| --- | --- | --- |
| Nano Banana 2.1 | 46.4 s; succeeded | 29.0 s; succeeded |
| GPT Image 2.6 Sunburst | 42.1 s; succeeded | 39.5 s; succeeded |
| Seedream 5.0 Pro | 39.9 s; succeeded | Blocked by provider filter after 40.8 s; no image |

The initial paid-model comparison returned images for 11 of 12 attempts, with a total reported cost of **$0.8253**. These were exploratory tests on limited inputs, not evidence of a general success rate or performance with 10 simultaneous users.

## Model Selection and App Integration

- **Quality-focused candidates:** Nano Banana 2.1 and GPT Image 2.6 Sunburst. Both improved outerwear in the tested cases. GPT appeared stronger in some fine details, while Nano Banana was faster in some conditions. No quantitative quality score or universal ranking was established.
- **Current implementation and build:** Today's Outfit and the Codi Book call Nano Banana 2.1 asynchronously. The existing Django CPU backend sends the person and all garment photos in one request, without a GPU or separate fitting server. Per-account result storage, completion notifications and the result dialog are retained. APK installation is a separate step.
- **Paid API pipeline:** Person, top, bottom and outerwear photos are supplied together for one generation. These paid-model tests did not first generate top + bottom and then add outerwear in a second call. The successful Cloudflare example used two calls.

## Nano Banana App API Validation

The Django API was run with a new database and new accounts; previous databases and results were not migrated. Original person, top and bottom photos were supplied, with an optional cardigan. No GPU server was used.

| Condition | Direct API call to result receipt | Server generation interval |
| --- | --- | --- |
| One top + bottom request | 24.3 s | 24.0 s |
| One request with outerwear | 22.3 s | 21.9 s |
| 10 simultaneous top + bottom submissions | First result 19.8 s; all results 105.3 s | 2 external API calls at a time |

All 12 requests returned 1696 × 2528 JPEG images. The concurrent test submitted 10 jobs to the backend queue, not from 10 phones. Checks covered cross-account access and deletion denial, input deletion from the StyleMate server after processing, and server-job deletion after result receipt. There are no automatic generation retries, GPU fallback or database compatibility layers. This integration test cost $0.7007; the cumulative cost including the initial comparison was $1.5260, within the $3 budget.

Other researched candidates were DeCo-VTON, Leffa, LongCat Edit Turbo, FireRed Image Edit, FLUX 3 Klein, SD-Turbo, SDXS, SDXL-Turbo and DreamShaper LCM. No corresponding execution measurements were available, so no values were added to the comparison table.

## 3D Approaches and Feature Scope

Hunyuan3D and TripoSR were excluded from the current scope because generating a rotatable shape still required additional garment replacement, body alignment and overlap handling. The app focuses on **2D outfit previews** using person and garment photos. Clothing JSON supports outfit recommendations and photo selection; generated images do not guarantee accurate measurements or physical fit.

The app's photo-selection notice states that photos are sent to OpenRouter and Google and that external providers' retention policies apply. StyleMate server inputs are deleted when a job ends, and server results are deleted after the app receives them. These tests did not verify immediate deletion of input photos by external providers. Keys, source photos, generated results, model weights and raw K-Fashion data are not included in the document or committed to Git.

## Measurement Records

The following local records are referenced relative to the repository root. Figures were checked against these reports, scripts and server logs; the raw records are excluded from Git. FASHN figures were verified from existing direct-execution logs without allocating another GPU. Separate live API integration tests were run after the Nano Banana migration.

- **FASHN direct execution:** `smoke.py`, `smoke-report.json`, `generate_outer.py`, `outer-50step-example/report.json`, `generate_cardigan.py` and `outer-cardigan-50step-example/report.json` under `backend/private/tryon2d/develop-app-20261104/`.
- **FASHN 10-request server timings and VRAM:** `latency-1-vs-10/metrics-after.jsonl` and `summary.json` in the same directory. Server batch intervals were used instead of app completion times.
- **Cloudflare free API:** `backend/private/tryon2d/cloudflare-free-20261101/summary.json` and `report.json`.
- **Paid APIs:** `backend/private/openrouter-outer-20261105/test-plan.json`, `results.json`, `run_comparison.py` and `seated-followup-summary.json`.
- **Earlier comparisons:** `backend/private/tryon2d/comparison/summary.json` and the following records under `backend/private/tryon2d/stability/`: `fashn-four-20261031T021327Z/results.json`, `fashn-30step-20261031T001850Z/results/results.json`, `photo-attributes-20261030T122326Z/api-resident/report.json`, `final-model-search-20261031T025042Z/` and `candidate-check-20261030T143136Z/summary.json`.
- **Nano Banana integration validation:** `backend/private/develop-tryon/source/backend/private/nanobanana-app-20261105/live-plan.json` and `live-results.json` in the original working directory.
