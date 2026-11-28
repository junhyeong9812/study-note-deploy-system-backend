# study-note-deploy-system-backend

study-note 검색 시스템의 backend(Kotlin/Spring Boot). GPU 서버(.158)에서
콘텐츠·검색 API와 색인 파이프라인, 로그 중앙 큐(Redis)를 맡는다.

- 설계: `docs/design/es-index.md`(색인) · 루트 프로젝트 `docs/logging.md`(로그 규약)
- 구동: `.env`에 `LLM_URL`·`SYNC_SECRET` 지정 후 `docker compose up -d --build`
- 접속 기록(nginx — 루트 프로젝트 `docs/cross-cutting/plans/2026-11-25/traffic-map/communication-protocol.md` 3절): 요청마다 표준 출력에 JSON 한 줄(14칸, `service` 는 `backend`). 접속 주소 · 쿼리 문자열 · 리퍼러 · User-Agent · 쿠키 · 인증 머리글은 싣지 않고, 경로는 첫 `?` 앞 1024자까지다. 오류 기록은 표준 오류(주소가 든다 — 수집하지 않는다). 도커 로그는 10MB × 3 으로 묶는다.
  - 요청 ID(`X-Request-Id`): 사설 대역 · 루프백에서 온 꼴(`^[A-Za-z0-9_-]{1,64}$`) 맞는 값만 믿고, 아니면 nginx 가 새로 만들어 앱에 넘기고 응답 머리글로 돌려준다. 호출자(`X-Caller-Service`)도 사설 대역의 아는 이름만 그대로, 나머지는 `outside` 로 덮어써 넘긴다 — 지도 표시용이지 인증이 아니다.
  - 배포: 이 변경을 병합 · 배포한 뒤 서버에서 한 번 `docker compose up -d --force-recreate nginx` 로 nginx 컨테이너를 다시 만든다(설정이 이미 반영된 서버에서는 그냥 `up -d` 가 다시 만들지 않는다). 다시 만드는 수 초 동안 연결이 끊기고, 열려 있던 긴 연결(채팅 스트림 · SSE)도 끊긴다 — 쓰는 사람이 없는 때에 한다. 다시 만들면 (가) 로그 크기 제한(10MB × 3)이 그때 걸린다(설정 복사 · reload 로는 걸리지 않는다) (나) 이전 형식으로 쌓인 접속 기록(방문자 주소 · 쿼리가 든 줄)이 컨테이너의 로그와 함께 지워진다 (다) reload 뒤 옛 워커가 끝날 때까지(`worker_shutdown_timeout`) 쓰던 옛 형식의 줄도 로그와 함께 지워진다.

## 패키지 구조 (도메인-우선 — 도메인 안에 레이어, 2026-08-28 재편)

```
shared/                    전역 인프라·표현 (도메인 무소속)
  api/    Envelope · GlobalErrorHandler
  infra/  RequestLog · EsClient · GitRepository · EmbeddingClient · LlmClient
sync/      {api, usecase}            SyncController · SyncService · SourceControlPort
indexing/  {usecase, domain}         IndexingService · ports(DocumentReader·TextEncoder·IndexStore) · Chunker · DocClassifier
search/    {api, usecase, domain}    SearchController · SearchService · ports(QueryRewrite·QueryEncoder·SearchIndex) · SearchHit
content/   {api, usecase, domain}    ContentController · TreeService · NoteSourcePort · TreeBuilder

의존 역전(DIP): 각 usecase가 포트(인터페이스)를 소유하고 shared/infra 구현체가 implements
  (GitRepository : SourceControlPort, NoteSourcePort, DocumentReader — 다중 구현).
테스트는 포트를 mock — 구체 인프라 클래스에 결합하지 않는다.
도메인 간 호출은 usecase 레벨만 (sync → indexing.IndexingService · content.TreeService).
```

## 내용 저장소 (study-note)

- 볼륨(`REPO_DIR`)에 내용 저장소를 **전체 이력**으로 받아 둔다 — 새로 받을 때도 평소의 동기화(`fetch origin main` → `reset --hard origin/main`)도 `--depth` 를 쓰지 않는다.
- 이미 있는 저장소가 얕으면(예전 `--depth=50` 사본) 동기화 때 `fetch --unshallow origin main` 으로 한 번 깊게 받는다. 깊게 받기가 실패하거나 시간 제한을 넘어도 평소의 동기화는 끝까지 가고, 실패는 그 동기화의 요청 ID 로 WARN 로그에 남으며 다음 동기화에서 다시 시도한다.
- 시간 제한: 깊게 받기는 `STUDY_NOTE_DEEPEN_TIMEOUT_SECONDS`(초 — 기본 300, 양의 정수가 아니면 기본값을 쓰고 WARN 한 줄), 그 밖의 동기화 명령은 명령마다 120초, 요청 중의 읽기(`git show` · `rev-parse HEAD`)는 5초.
- 동기화 명령이 시간 제한을 넘으면 git 과 그 아래 프로세스에 먼저 종료 신호(SIGTERM)를 보내고(git 이 잠금 파일을 스스로 지운다) 5초 안에 끝나지 않은 것만 강제로 끊는다. 요청 중의 읽기는 곧바로 강제로 끊는다.
- 깊게 받기가 실패했을 때 `.git/shallow.lock` 이 남아 있으면 `history.problem` 과 WARN 이 "잠금 파일이 남아 있다"와 그 경로로 시작한다. backend 는 이 잠금을 지우지 않는다(같은 볼륨을 쓰는 다른 자리의 git 이 쥔 것일 수 있다) — 사람이 그 볼륨에서 도는 git 이 없는지 확인한 뒤 지우면 다음 동기화가 다시 깊게 받는다.
- 동기화 상태(`GET /internal/sync/status`)의 `history`: `{"shallow": true | false | null, "problem": 문구 | null}` — `shallow` 는 마지막 동기화가 알아낸 값(`null` = 동기화 전이거나 얕은지 알아내지 못함), `problem` 은 마지막 동기화에서 깊게 받기 · 얕은지 묻기가 실패한 git 의 문구이거나, 받기는 끝났는데 저장소가 아직 얕은 경우(원격이 얕은 사본 — `.git/shallow` 가 남음)의 "아직 얕다" 글.

## 변경 이력 API

옛 `GET /api/history`(커밋 제목 30개)는 없앴다. 공개되는 글(`.md` 이고 `docs` · `templates` · 실습 폴더 밖 — 아래 "실습 폴더")만 싣고, 커밋은 sha · 커밋 시각(`at`) · 제목 한 줄만 낸다(본문 · 글쓴이 · 메일은 내지 않는다). 병합 커밋은 싣지 않는다.

- `GET /api/history/recent` → `{commit_sha, complete, history, commits: [{sha, at, title, docs: [{path, kind, from?}], more_docs}]}` — `kind` 는 added · modified · deleted · renamed(`from` 은 renamed 에만). 최근 커밋 200개를 훑어 공개되는 글을 바꾼 커밋을 30개까지, 커밋당 글 50개까지(넘친 수는 `more_docs`). 숨김 폴더 · 실습 폴더만 고친 커밋은 통째로 빠진다. 동기화가 성공할 때 한 번 계산해 쥔다(재시작 뒤에는 첫 요청이 만든다 — 계산은 한 번에 하나이고 그동안의 요청은 기다리지 않고 503, 첫 계산이 실패하면 그 실패를 5초 쥐어 그동안도 503. 동기화 뒤의 계산이 실패해도 동기화는 성공이고 앞의 것을 쥔다).
- `GET /api/history/doc?path=` → `{path, complete, history, revisions: [{sha, at, title, path}], more_revisions}` — 이름 바뀜을 따라가며 판마다 그때의 경로를 싣는다(숨김 폴더 · 실습 폴더에 있던 판은 뺀다). 100개까지(넘친 수는 `more_revisions`). 경로가 형식에 맞지 않거나(저장소 상대의 `.md` 가 아님 · `..` · `.` · 빈 마디(`//` · 끝의 `/`) · `.git` 이 든 경로) 제어 문자가 있거나 1024자를 넘으면 422, 숨김 폴더 · 실습 폴더이거나 지금 판에 없는 글이면 404. 요청 때 계산하고 함께 4개까지 — 넘치면 기다리지 않고 503. 요청 하나가 쓰는 git(커밋이 있는지 · 지금 판에 있는지 · 이력)은 모두 합해 5초 안이다.
- `complete` 는 저장소가 얕지 않다고 알려졌을 때만 참이다(얕음 · 재시작 뒤 첫 동기화 전이면 거짓). `history` 는 동기화 상태의 그것과 같다.
- git 의 실패 · 시간 초과(요청 중의 읽기 5초) · 동시 읽기 상한 초과 · 최근 변경의 첫 계산이 도는 중이거나 방금 실패함은 빈 목록이 아니라 503 `content_unavailable`.
- 경로 검증(`PathGuard`)은 `/api/doc` · 옛 판 읽기 · 채팅과 같다 — `./docs/x.md` 처럼 `.` · 빈 마디로 숨김 폴더를 가리키는 경로도 422 로 거절한다.

## 실습 폴더

글 저장소의 주제 폴더 안에 실습 문제가 `practice/<번호-이름>/`(문제 글 · 설정 · 시작 코드 · 테스트 · 모범 답안)로 들어온다. backend 는 이것을 글 읽기 길에서 숨긴다.

- 정의: 경로의 폴더 마디 가운데 **둘째 이후**의 것이 정확히 `practice` 이면 실습 경로다(`PathGuard.isPracticePath`). 파일 이름 `practice.md` · 대소문자가 다른 `Practice` · `practices` 는 아니다.
- 숨김 판정(`PathGuard.isHidden`)에 들어 있어, `docs` · `templates` 를 거르는 모든 곳(트리 · 글 목록 · 문서 · 옛 판 · 글 하나의 이력 · 최근 변경 · 동기화의 변경 목록 · 색인 · 채팅)이 함께 거른다 — 문서 · 이력은 숨김 글과 같은 404, 채팅은 422. 퀴즈는 주제 경로 판정이 실습 폴더를 주제로 받지 않는다(422).
- 까닭: 트리는 하위 폴더가 없는 폴더를 주제로 본다 — 숨기지 않으면 실습 폴더가 생긴 주제가 주제로 뜨지 않아 글 · 퀴즈가 사라진다. 모범 답안 · 테스트가 글로 읽히지 않게 하는 것이기도 하다.
- 최상위 `practice/`(프로그래머스 풀이 글)는 기존 글이라 숨기지 않는다 — 첫 마디는 보지 않는다.
- 실습 문제를 사이트에 보이는 경로(문제 읽기 · 제출)는 뒤 조각에서 따로 만든다.

## 주인 판정

Claude 로 가는 경로(`POST /api/chat/escalate` · `POST /api/chat` 의 자동 넘김)는 주인만 지난다. 주인은 서버가 쿠키 하나로 판정한다 — 머리 · 질의 · 몸의 칸 · 다른 쿠키는 보지 않는다.

- 쿠키 `study_owner` = `ow1.<만든 시각 ms>.<서명>`. 서명은 HMAC-SHA256(열쇠 = `OWNER_COOKIE_SECRET` 의 UTF-8 바이트, 줄 = `study-owner` LF `ow1` LF `<만든 시각 ms>`)의 base64url(채움 없음 43글자)이고 글자 그대로 상수 시간에 견준다. 만든 시각에서 30일까지 받고, 지금보다 60초 넘게 앞선 시각은 받지 않는다. 쿠키를 심고 지우는 것은 어드민이다 — backend 는 읽기만 한다.
- 열쇠 `OWNER_COOKIE_SECRET`: 어드민과 같은 값 · 32바이트 이상. 없거나 짧으면 **누구도 주인이 아니다**(닫힌 쪽 — 뜰 때 그 이름을 말하는 WARN 한 줄).
- `GET /api/owner` → `{owner: true|false}`(언제나 200 · `Cache-Control: no-store` · `Vary: Cookie`). front 가 단추를 보일지 정하는 데만 쓴다 — 이것만 믿고 여는 길은 없다.
- 주인이 아닌 `POST /api/chat/escalate` 는 몸의 검증보다 먼저 403 `owner_only` — Claude 쪽 · 대화 이력 · 문서 읽기에 닿지 않고 세션 쿠키도 심지 않는다.
- 되돌리는 법: 열쇠를 바꾸면(어드민과 함께) 지금까지의 표시가 한꺼번에 무효가 된다. 열쇠를 비우면 주인이 없어진다.
- 판정은 요청 로그에 `owner=true` 또는 `owner=false reason=<낱말>` 로 남는다(쿠키의 값 · 서명은 남기지 않는다).

| 낱말 | 뜻 |
|---|---|
| `no_secret` | 열쇠가 없거나 비었거나 공백뿐이거나 32바이트보다 짧다 |
| `no_cookie` | `study_owner` 쿠키가 없다 |
| `malformed` | 같은 이름의 쿠키가 둘 이상(톰캣이 읽어 낸 쿠키 기준 — 값의 꼴이 틀려 톰캣이 버린 쿠키는 세지 않는다) · 값의 꼴이 틀림 · 만든 시각이 지금보다 60초 넘게 앞섬 |
| `bad_signature` | 서명이 맞지 않는다(다른 열쇠 · 고친 값) |
| `expired` | 만든 시각에서 30일이 지났다 |

## Claude 로 넘김

로컬 모델에 **연결되지 못하면** 주인의 질문을 Claude 실행 자리(`study-note-deploy-system-claude`)가 답한다. 단추(`POST /api/chat/escalate`)도 같은 실행 자리를 부른다.

- 설정: `CLAUDE_RUNNER_URL` · `CLAUDE_RUNNER_SECRET`(실행 자리의 호출용 비밀값과 같은 값). 둘 다 있으면 실행 자리를, 아니면 옛 통로(`CLAUDE_BRIDGE_URL` · `BRIDGE_SECRET`)를 쓴다 — 옛 통로는 단추에만 쓰고 자동 넘김은 하지 않는다. 고르는 곳은 `EscalateConfiguration` 한 곳.
- 자동 넘김의 조건(모두): ① 주인(SSE 를 시작하기 전에 쿠키로 판정 — 옛 계약의 글자 흐름은 넘기지 않는다) ② 그 질문의 **첫 시도가 연결 단계에서** 실패(연결 거부 · 연결 시간 초과 3초 · 주소를 풀지 못함 — 헤더 시간 초과 · 연결 직후 끊김 · 줄 밀림 · 도중 끊김 · 교체 중 다시 보낸 요청의 실패는 넘기지 않는다) ③ 실행 자리가 설정돼 있음 ④ 요청을 받은 때부터 170초의 마감까지 20초 이상 남음. 아니면 지금 그대로 `error{code:"llm_unreachable"}`.
- 이벤트(지금의 꼴에 더하기만): `escalated{}` → `token{text}`(답 한 번) → `truncated{}`(잘렸을 때만) → `done{}`. 거절이면 `escalated{}` → `error{code}`. 한 질문에 실행 자리 호출은 많아야 한 번(재시도 없음).
- 실행 자리를 부르는 법: `POST <URL>/runs` · 머리글 `X-Claude-Runner-Secret` · `Content-Length` 가 있는 JSON 몸 · 리다이렉트를 따라가지 않는다 · 연결 3초 · 기한은 호출 전체의 절대 마감(자동 넘김은 남은 시간, 단추는 135초). 호출마다 클라이언트를 만들어 끝나면 닫고, 기한이 지나거나 기다리던 쪽이 떠나면 그 클라이언트를 곧바로 닫아(`shutdownNow`) 실행 자리 쪽 연결도 닫힌다. 프롬프트는 60,000자를 넘지 않는다(문서는 40,000자까지 — 넘으면 자르고 한 줄). 문서를 읽지 못하면 부르지 않는다.

| 실행 자리의 답 | 결과 | 자동 넘김의 코드 | 단추의 응답 |
|---|---|---|---|
| 200 · `data.text` | 답 | `token` | 200 `{answer, truncated}` |
| 429 `busy` | `busy` | `claude_busy` | 429 `claude_busy` |
| 429 `limit_reached` | `limit_reached` | `claude_limit` | 429 `claude_limit` |
| 429 `disabled` | `disabled` | `claude_disabled` | 429 `claude_disabled` |
| 504 · 기한 초과 | `timeout` | `claude_timeout` | 504 `claude_timeout` |
| 503 · 502 · 500 · 연결 실패 · 꼴이 틀린 답 · 모르는 코드 | `unavailable` | `claude_unavailable` | 503 `escalate_failed` |
| 400 · 401 · 404 · 411 · 413 · 422 (backend 의 설정 · 코드 잘못) | `rejected` | `claude_unavailable` | 503 `escalate_failed` |
| (부를 곳이 없음) | | (넘기지 않음 — `llm_unreachable`) | 503 `escalate_unavailable` |

- 이력: 답을 받았을 때만 질문 · 답 두 줄을 한 명령(`RPUSH` 두 값)으로 쓴다 — 로컬의 답도 같다. Claude 의 답 줄에만 `"source":"claude"` 가 있고 `GET /api/chat/history` 가 그 칸을 그대로 낸다. Claude 의 답은 이력을 먼저 쓰고 보낸다(보내다 끊겨도 남는다). 기다리다 끊기면 쓰지 않는다.
- 로그(프롬프트 · 질문 · 답 · 비밀값 없음): `claude run: result=<answered|busy|limit_reached|disabled|timeout|unavailable|rejected|abandoned> auto=<true|false> duration_ms=<수>`(+ `truncated=true` · `rejected` 면 `code=<실행 자리의 코드>`). 답은 info · `rejected` 는 error · 그 밖의 거절은 warning. 로컬의 완료 로그도 질문 · 답의 길이만 남긴다.
- 한계: 브라우저가 떠나면 기다리던 호출을 끊지만 실행 자리의 실행은 끝까지 돌 수 있다 — 한도 하나를 쓴다. 떠난 것을 아는 데는 **약 30초**가 걸린다 — keep-alive 는 15초마다지만 떠난 직후의 첫 keep-alive 는 버퍼에 들어가 성공하고, 그다음 것이 실패한다(실측 30.06초). 끊긴 호출은 `claude run: result=abandoned auto=… duration_ms=…`(warning) 한 줄을 남기고 이력은 쓰지 않는다.
- `unavailable` 의 줄은 실행 자리가 답했으면 그 뒤에 `status=<HTTP 상태 코드>` 를, 실행 자리의 낱말이 알려진 것(`claude_failed` · `bad_output` · `invalid_answer` · `token_missing` · `claude_unavailable` · `limits_unavailable` · `ledger_unavailable` · `internal` · `timeout`)이면 `code=<낱말>` 을 덧붙인다(예: `… result=unavailable auto=true duration_ms=812 status=502 code=claude_failed`). 닿지 못했으면 덧붙이지 않는다. 앞의 글자 `claude run: result=unavailable` 은 바뀌지 않는다(알림 규칙이 찾는다).
- `rejected` 의 `code=` 는 실행 자리의 알려진 코드(`unauthorized` · `length_required` · `bad_length` · `invalid_body` · `unknown_field` · `unknown_purpose` · `empty_prompt` · `prompt_too_long` · `unencodable_prompt` · `invalid_request_id` · `not_found`)일 때만 쓴다 — 그 밖의 코드는 칸 자체를 쓰지 않는다.
- 짝 글자: 실행 자리는 UTF-8 로 바꿀 수 없는 프롬프트를 거절한다(422 `unencodable_prompt`). 그래서 문서(4만 자) · 이력(500자)을 자르는 끝이 짝 글자(이모지 등)의 가운데면 한 글자 덜 자르고, 원래 글(질문 · 이력 · 문서)에 짝이 없는 반쪽(홀로 선 서로게이트)이 있으면 U+FFFD 로 바꿔 싣는다.

## 로그 · 요청 ID

로그 한 줄은 `<요청 ID>:backend:<내용>` 이다. 요청 ID(추적의 ID)의 꼴은 `^[A-Za-z0-9_-]{1,64}$`(정규식은 `RequestLog` 한 곳) — 들어온 `X-Request-Id` 가 이 꼴이면 그 값을, 아니면 backend 가 새로 만든다(`RequestLog.acceptOrIssue`).

- llm 을 부를 때(`/rewrite` · `/chat` — 교체 중 다시 보내는 요청도) 머리글 `X-Request-Id` 에 그 요청의 ID 를 싣는다. 본문의 `request_id` 는 호환으로 그대로 두고 같은 값이다. 꼴이 맞지 않는 값은 머리글에 싣지 않는다(새로 만들지도 않는다).
- 처리하지 못한 예외의 로그(`unhandled: …`)도 그 요청의 ID 를 쓴다 — 그 요청에서 정한 값, 없으면 꼴이 맞는 들어온 머리글, 아니면 새로 만든 값. 오류 응답의 몸은 그대로다.
- 싣지 않는 곳: 응답 머리글(nginx 가 낸다) · 임베딩 · 검색 엔진 · Redis(nginx 를 거치지 않는다) · Claude 실행 자리(본문의 `request_id` 가 그 계약이다).

## 의존 서비스 상태

`GET /api/health/dependencies` — 의존 넷(elasticsearch · embedding · redis · postgres)을 병렬로 확인한다(전체 예산 3초 · 항상 200). 기본 헬스(`/actuator/health`)에는 넣지 않는다 — 의존이 죽어도 배포 헬스가 바뀌지 않게.

- 본문: `{"status": "UP"|"DOWN", "components": {<의존>: {"status": "UP"|"DOWN", "took_ms": <정수>|null, "detail"?: <글>}}}` — `detail` 은 DOWN 일 때만(120자까지).
- `took_ms`: **응답하는 쪽(backend)이 잰 그 확인 하나의 걸린 시간**(단조 시계 · 정수 ms). 그 의존에서 응답을 받았으면 쓸 수 없는 답이어도(200 이 아닌 상태 · ES red · PONG 이 아닌 답 · SQL 오류) 정수다. 응답을 받지 못했거나(연결 거절 · 시간 초과 · 끊김 · 연결을 얻지 못함) 예산을 넘겨 끝을 보지 못했으면 `null`. Redis 는 답을 기다리다 명령 시간 초과(1초)로 끝난 경우도(Redis 가 멈추거나 죽었을 때 이렇게 끝난다), PostgreSQL 은 질의 시간 초과도 `null`. 칸은 늘 있다.

## 한계 — Redis 의 디스크 저장

`redis` 는 디스크 저장(AOF — `--appendonly yes` · `--appendfsync everysec`)을 켜 두었다. 데이터는 이름 있는 볼륨 `backend-redis-data`(`/data/appendonlydir`)에 있고, 컨테이너를 다시 만들어도 남는다. RDB 스냅숏은 켜지 않는다(`--save ""`).

- **디스크에 남는 것**: 방문의 원문(관측 서버가 가져가기 전의 스트림 `visits`) · 그날의 소금 · 대화 이력(7일) · 로그 · 지표 · 통신 스트림. 매초 디스크에 맞춰 쓴다 — 갑자기 꺼지면 마지막 1초까지 잃을 수 있다.
- **지운 방문이 디스크에서 사라지는 때**: AOF 는 덧붙이는 기록이라 지운 항목의 글자가 **다시 쓰기(rewrite) 전까지 파일에 남는다**. 다시 쓰기는 두 길로 돈다 — 파일이 10% 넘게 커지면(최소 1mb) Redis 가 스스로, 그리고 곁 서비스 `redis-aof-rewrite` 가 30분마다(`REDIS_AOF_REWRITE_INTERVAL_SECONDS`, 기본 1800초 · 60 이상의 정수만 — 그 밖의 값은 로그에 `BAD-INTERVAL` 한 줄을 남기고 1800초. `.env` 에서 값을 비워 두면 compose 가 먼저 기본 1800 으로 바꾼다 — 이때는 `BAD-INTERVAL` 줄이 없다) `BGREWRITEAOF` 를 시킨다. 그래서 지운 방문의 원문은 **보통 약 30분 안에**(주기 + 다시 쓰기의 시간) 파일에서 사라진다. 메모리에서는 곧바로 사라진다(`--stream-node-max-entries 1`). 이것은 파일의 글자 기준이다 — 지워진 옛 AOF 파일이 파일시스템의 빈 블록에 남는 것은 범위 밖이다.
  - **한 주기 더 걸리는 때**: 지우기 전에 시작된 다시 쓰기가 아직 도는 중에 곁 서비스의 차례가 오면 그 차례는 거절된다(`already in progress`). 그 다시 쓰기는 지우기 전의 데이터를 쓰므로 원문은 다음 차례까지 남는다 — 최대 약 두 주기(약 60분).
  - **남아 있는 때**: 다시 쓰기가 실패하는 동안(디스크 가득 · 쓰기 오류)에는 사라지지 않는다. 곁 서비스는 두 경우에 한 줄을 남긴다 — Redis 가 다시 쓰기를 곧바로 거절할 때(디스크가 완전히 차 시작하지도 못하는 때 등 — Redis 의 `ERR …` 응답을 그 줄에 싣는다), 시킨 다시 쓰기의 결과가 실패 · 한 주기 안에 끝나지 않음 · 결과를 못 읽음일 때: `docker logs backend-redis-aof-rewrite 2>&1 | grep 'redis-aof-rewrite: FAILED'`(`rewrite-error` · `rewrite-timeout` · `rewrite-unknown`). 원인은 Redis 의 로그와 `redis-cli INFO persistence` 의 `aof_last_bgrewrite_status` 에 있다.
  - **다시 쓰기 도중 강제 종료(SIGKILL · 정전 · OOM)**: 자식이 쓰던 `/data/temp-rewriteaof-*.aof`(그때의 전체 데이터)가 남고 Redis 는 이것을 지우지 않는다. 그래서 `redis` 의 명령이 **뜰 때** `/data/temp-rewriteaof-*.aof` · `/data/temp-*.rdb` 를 지운 뒤 서버를 띄운다(`redis-start: removed leftover temp file …` 한 줄). 다시 뜨기 전까지는 남는다. 지우지 못하면 `redis-start: FAILED to remove leftover temp file …` 한 줄을 남기고 **Redis 는 그대로 뜬다**(서비스를 세우지 않는다) — 그 파일은 남으므로 이 줄을 찾아 손으로 지운다: `docker logs backend-redis 2>&1 | grep 'redis-start: FAILED'`.
- **비용**: 디스크에 쓰는 양은 하루 약 **48 × B + 11 × G** 다 — B 는 다시 쓴 바탕의 크기, G 는 그날 AOF 에 덧붙인 양(방문 · 로그 · 지표 · 통신 · 대화의 쓰기). 48 × B 는 곁 서비스의 몫이다(바탕 47MB — 로그 1만 · 지표 2만 · 통신 5천 항목 기준 — 이면 약 2.3GB). 11 × G 는 덧붙임 그 자체(1 × G)와 자동 다시 쓰기의 몫(약 10 × G)이다: 문턱이 10% 라 AOF 가 B 의 10% 만큼 커질 때마다 B 전체를 다시 쓰므로, 덧붙인 양의 약 10배를 더 쓴다(리뷰의 실측 9.6배 — 바탕 7.3MB 에 15.2MB 를 더 넣자 다시 쓰기 20번 · 약 146MB). G 는 LAN 의 다른 호스트가 넣는 로그 · 지표 · 통신의 양을 따라 늘고 상한이 없다 — 예: 하루 1GB 가 들어오면 다시 쓰기만으로 약 10GB 를 더 쓴다. 노드당 항목 1 로 스트림의 메모리가 약 1.45배가 된다(로그 5만 항목에서 +8.3 → +12.0MiB). 다시 쓰기 한 번은 0.5~0.9초이고 그 순간 `XADD` 가 수십 ms 늦을 수 있다.
- **디스크가 가득 차면**: Redis 가 AOF 에 쓰지 못해 모든 쓰기와 `PING` 을 거부한다(`MISCONF Errors writing to the AOF file: No space left on device`). healthcheck(`redis-cli ping`)가 unhealthy 가 된다. backend 는 이 오류를 견디고 계속 뜨지만, 그동안 방문 · 대화 이력이 저장되지 않는다.
- **수동 `BGSAVE`**: Redis 에 인증이 없어 LAN 의 누구든 `BGSAVE` 를 부를 수 있다. 그러면 그때의 원문이 든 `/data/dump.rdb` 가 볼륨에 남고, AOF 다시 쓰기는 이것을 지우지 않는다(따로 다룬다).
- **볼륨이 지워지는 때**: `docker compose down -v` 한 번에 `backend-redis-data` 가 지워진다(방문 · 소금 · 대화 이력이 함께 사라진다). 운영에서는 `-v` 를 쓰지 않는다.
- **AOF 가 깨졌을 때**: 끝이 잘린 파일(갑자기 꺼짐)은 Redis 가 잘린 꼬리를 버리고 뜬다. 가운데가 깨지면 뜨지 않는다 — 컨테이너를 멈춘 뒤 `redis-check-aof --fix` 로 고친다(깨진 곳 뒤의 기록은 잃는다).
- **배포**: 배포 에이전트는 앱의 자리(`app-blue` · `app-green`)만 다시 만든다(`up -d --no-build --no-deps <자리>`) — `redis` 와 `redis-aof-rewrite` 는 postgres 처럼 이름 없이 `docker compose up -d` 할 때 뜬다.
- **서버에 반영할 때 한 번 사라지는 것**: 디스크 저장을 켜려고 `redis` 를 한 번 다시 만들 때, 그때까지 메모리에만 있던 대화 이력(최근 7일) · 그날의 소금(그날의 순방문자가 반영 전후로 두 번 세어진다) · 아직 넘어가지 않은 방문 · 로그 · 지표 · 통신 스트림의 사본과 관측 서버의 소비자 그룹이 사라진다.
- **서버에서 손으로 할 일(한 번 — 배포 에이전트는 `redis` 를 다시 만들지 않는다)**: 배포 디렉터리(`docker-compose.yml` 이 있는 곳)에서
  1. 반영 전에 옛 컨테이너의 `/data` 에 붙은 볼륨을 적어 둔다 — 이미지에 `VOLUME /data` 가 있어 옛 `backend-redis` 는 이름 없는 볼륨(긴 16진수 이름)을 가진다:
     `docker inspect backend-redis --format '{{range .Mounts}}{{.Type}} {{.Name}} {{.Destination}}{{println}}{{end}}'`
  2. `redis` 를 다시 만들고 곁 서비스를 띄운다(위의 것이 이때 한 번 사라진다 — 대화 이력 한 번): `docker compose up -d redis redis-aof-rewrite`
  3. 확인: `docker compose ps redis redis-aof-rewrite`(둘 다 Up · redis 는 healthy) · `docker inspect backend-redis --format '{{range .Mounts}}{{.Name}} {{.Destination}}{{println}}{{end}}'` 가 `backend-redis-data /data` · `docker exec backend-redis redis-cli CONFIG GET appendonly` 가 `yes` · 30분 뒤 `docker logs backend-redis-aof-rewrite 2>&1 | tail -3` 에 `Background append only file rewriting started` 가 있고, `docker logs backend-redis-aof-rewrite 2>&1 | grep -c 'redis-aof-rewrite: \(FAILED\|BAD-INTERVAL\)'` 가 `0` 이고, `docker logs backend-redis 2>&1 | grep -c 'redis-start: FAILED'` 가 `0` 이다(아니면 그 줄의 파일을 손으로 지운다).
  4. 1 에서 적은 옛 볼륨은 새 컨테이너가 쓰지 않아 남는다. 아무 컨테이너도 쓰지 않는지 · 안이 비었는지(옛 설정은 `appendonly no` · `save ""` 라 비어 있을 것이다) 본 뒤 **그 이름으로** 지운다 — `docker volume prune` 은 쓰지 않는다(다른 서비스의 이름 없는 볼륨까지 지운다):
     `docker ps -a --filter volume=<적어 둔 이름>`(빈 목록) · `docker run --rm -v <적어 둔 이름>:/data redis:7-alpine ls -la /data` · `docker volume rm <적어 둔 이름>`
