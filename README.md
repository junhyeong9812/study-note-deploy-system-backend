# study-note-deploy-system-backend

study-note 검색 시스템의 backend(Kotlin/Spring Boot). GPU 서버(.158)에서
콘텐츠·검색 API와 색인 파이프라인, 로그 중앙 큐(Redis)를 맡는다.

- 설계: `docs/design/es-index.md`(색인) · 루트 프로젝트 `docs/logging.md`(로그 규약)
- 구동: `.env`에 `LLM_URL`·`SYNC_SECRET` 지정 후 `docker compose up -d --build`

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

옛 `GET /api/history`(커밋 제목 30개)는 없앴다. 공개되는 글(`.md` 이고 `docs` · `templates` 밖)만 싣고, 커밋은 sha · 커밋 시각(`at`) · 제목 한 줄만 낸다(본문 · 글쓴이 · 메일은 내지 않는다). 병합 커밋은 싣지 않는다.

- `GET /api/history/recent` → `{commit_sha, complete, history, commits: [{sha, at, title, docs: [{path, kind, from?}], more_docs}]}` — `kind` 는 added · modified · deleted · renamed(`from` 은 renamed 에만). 최근 커밋 200개를 훑어 공개되는 글을 바꾼 커밋을 30개까지, 커밋당 글 50개까지(넘친 수는 `more_docs`). 숨김 폴더만 고친 커밋은 통째로 빠진다. 동기화가 성공할 때 한 번 계산해 쥔다(재시작 뒤에는 첫 요청이 만든다 — 계산은 한 번에 하나이고 그동안의 요청은 기다리지 않고 503, 첫 계산이 실패하면 그 실패를 5초 쥐어 그동안도 503. 동기화 뒤의 계산이 실패해도 동기화는 성공이고 앞의 것을 쥔다).
- `GET /api/history/doc?path=` → `{path, complete, history, revisions: [{sha, at, title, path}], more_revisions}` — 이름 바뀜을 따라가며 판마다 그때의 경로를 싣는다(숨김 폴더에 있던 판은 뺀다). 100개까지(넘친 수는 `more_revisions`). 경로가 형식에 맞지 않거나(저장소 상대의 `.md` 가 아님 · `..` · `.` · 빈 마디(`//` · 끝의 `/`) · `.git` 이 든 경로) 제어 문자가 있거나 1024자를 넘으면 422, 숨김 폴더이거나 지금 판에 없는 글이면 404. 요청 때 계산하고 함께 4개까지 — 넘치면 기다리지 않고 503. 요청 하나가 쓰는 git(커밋이 있는지 · 지금 판에 있는지 · 이력)은 모두 합해 5초 안이다.
- `complete` 는 저장소가 얕지 않다고 알려졌을 때만 참이다(얕음 · 재시작 뒤 첫 동기화 전이면 거짓). `history` 는 동기화 상태의 그것과 같다.
- git 의 실패 · 시간 초과(요청 중의 읽기 5초) · 동시 읽기 상한 초과 · 최근 변경의 첫 계산이 도는 중이거나 방금 실패함은 빈 목록이 아니라 503 `content_unavailable`.
- 경로 검증(`PathGuard`)은 `/api/doc` · 옛 판 읽기 · 채팅과 같다 — `./docs/x.md` 처럼 `.` · 빈 마디로 숨김 폴더를 가리키는 경로도 422 로 거절한다.

## 주인 판정

Claude 로 가는 경로(지금은 `POST /api/chat/escalate`)는 주인만 지난다. 주인은 서버가 쿠키 하나로 판정한다 — 머리 · 질의 · 몸의 칸 · 다른 쿠키는 보지 않는다.

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
| `malformed` | 같은 이름의 쿠키가 둘 이상 · 값의 꼴이 틀림 · 만든 시각이 지금보다 60초 넘게 앞섬 |
| `bad_signature` | 서명이 맞지 않는다(다른 열쇠 · 고친 값) |
| `expired` | 만든 시각에서 30일이 지났다 |
