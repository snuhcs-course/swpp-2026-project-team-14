# 채팅 AI 평가 실행

고정 34개 케이스와 통과 기준은 [RUBRIC.md](RUBRIC.md)를 따른다. 제공자 설정과 오류 분류를 수정해도 케이스, 80% 통과율, 치명적 오류 0건, 형식 오류 0건 기준은 유지한다.

## 제공자·모델 확인

서비스의 `chat_intent.parse()`와 평가 스크립트가 같은 `chat_intent.model_config()`를 사용한다.

| 설정 | 선택 |
| --- | --- |
| `CHAT_PROVIDER=auto` 또는 미설정 | `OPENROUTER_API_KEY`가 있으면 OpenRouter, 없으면 Gemini |
| `CHAT_PROVIDER=gemini` | Gemini 직접 호출 |
| `CHAT_PROVIDER=openrouter` | OpenRouter 호출 |
| Gemini 모델 | `GEMINI_TEXT_MODEL`, 기본 `gemini-3.1-flash-lite` |
| OpenRouter 모델 | `OPENROUTER_CHAT_MODEL`, 기본 `google/gemini-3.1-flash-lite` |

평가 실행 시 `backend/.env`를 서비스와 같은 방식으로 읽으며, 이미 지정된 환경 변수를 유지한다. 선택된 제공자의 키가 없거나 설정이 잘못되면 평가 시작 전에 중단한다.

로컬 평가 환경 확인 (모델 호출 없음):

```powershell
cd D:\swppgit\swpp-2026-project-team-14\backend
.\.venv\Scripts\python.exe scripts/eval_chat.py --show-config
```

출력은 `provider`, `model`, `configured` 세 필드이며 API 키를 포함하지 않는다.

배포 서버의 현재 환경은 서버에서 다음 명령으로 확인한다. 기존 배포 이미지에서도 실행할 수 있도록 현재 서비스의 선택 규칙을 직접 조회한다. API 키 값은 출력하지 않는다.

```bash
sudo k3s kubectl -n team14 exec deploy/backend -c backend -- python -c "import os,json; from recommendation import gemini; p=os.environ.get('CHAT_PROVIDER','auto'); p=('openrouter' if os.environ.get('OPENROUTER_API_KEY','').strip() else 'gemini') if p=='auto' else p; print(json.dumps({'provider':p,'model':os.environ.get('OPENROUTER_CHAT_MODEL','google/gemini-3.1-flash-lite') if p=='openrouter' else os.environ.get('GEMINI_TEXT_MODEL',gemini.TEXT_MODEL)}))"
```

저장소의 `infra/apps/config.yaml`에는 채팅 제공자·모델이 명시되어 있지 않다. 실제 선택은 배포 컨테이너 환경에 있는 Secret 등의 설정을 포함해 확인한다. 로컬 출력만으로 서버 제공자를 확정하지 않는다.

## 평가 실행

1. 평가셋·기준·실행 코드 변경을 커밋한다.
2. 서버에서 확인한 제공자·모델명과 확인 시각을 기록한다.
3. 로컬 평가 환경에 같은 제공자·모델을 설정한다. 키는 환경 변수나 `.env`에 둔다.
4. 서버에서 확인한 값을 기대값으로 지정해 전체 평가를 실행한다.

서버가 Gemini 직접 호출이고 모델이 기본값일 때의 예:

```powershell
$env:CHAT_PROVIDER = 'gemini'
$env:GEMINI_TEXT_MODEL = 'gemini-3.1-flash-lite'
.\.venv\Scripts\python.exe scripts/eval_chat.py --expected-provider gemini --expected-model gemini-3.1-flash-lite
```

서버가 OpenRouter이고 모델이 기본값일 때의 예:

```powershell
$env:CHAT_PROVIDER = 'openrouter'
$env:OPENROUTER_CHAT_MODEL = 'google/gemini-3.1-flash-lite'
.\.venv\Scripts\python.exe scripts/eval_chat.py --expected-provider openrouter --expected-model google/gemini-3.1-flash-lite
```

실제 서버가 모델을 따로 지정했다면 해당 모델명으로 바꾼다. 스크립트는 제공자를 Gemini로 덮어쓰지 않으며, 결과 JSON에 실제 선택된 제공자·모델과 temperature 0을 저장한다.

## 결과와 오류 처리

- `ChatModelError.kind`의 `format` / `infra`와 `code`를 직접 사용한다. 로그 분석이나 Gemini 함수 교체로 오류를 추정하지 않는다.
- JSON·응답 구조·스키마 오류는 재시도 없이 해당 케이스 실패와 형식 오류로 집계한다.
- 연결·인증·한도·모델 설정 오류는 인프라 오류다. 실행 중 인프라 오류는 기본 2회 재시도하고, 남으면 `INCOMPLETE`로 처리한다.
- 전체 34개를 완료한 실행은 `results/`에 새 JSON으로 저장한다. 기존 결과를 유지한다.
- `--case` 부분 실행과 인프라 오류가 남은 실행은 공식 결과 파일로 저장하지 않는다.
- 기존 `20261031-011359.json`의 33/34 결과는 Gemini 직접 호출 결과로 보관한다. 서버와 다른 제공자를 사용한다면 해당 서버 경로로 평가한 결과를 추가한다.

## 코드 검증

다음 테스트는 외부 모델을 호출하지 않는다. 서비스의 제공자 선택·실제 요청 모델명·오류 분류, 평가 스크립트의 재시도·판정·결과 메타데이터를 검증한다.

```powershell
.\.venv\Scripts\python.exe -m pytest tests/recommendation/test_chat_intent.py tests/recommendation/test_eval_chat.py -q
.\.venv\Scripts\python.exe manage.py test apps.outfits.test_chat --settings=config.test_settings --noinput
```
