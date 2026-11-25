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
- 이미 있는 저장소가 얕으면(예전 `--depth=50` 사본) 동기화 때 `fetch --unshallow origin main` 으로 한 번 깊게 받는다. 깊게 받기가 실패하거나 시간 제한(명령마다 120초)을 넘어도 평소의 동기화는 끝까지 가고, 실패는 그 동기화의 요청 ID 로 WARN 로그에 남으며 다음 동기화에서 다시 시도한다.
- 동기화 상태(`GET /internal/sync/status`)의 `history`: `{"shallow": true | false | null, "problem": 문구 | null}` — `shallow` 는 마지막 동기화가 알아낸 값(`null` = 동기화 전이거나 얕은지 알아내지 못함), `problem` 은 마지막 동기화에서 깊게 받기 · 얕은지 묻기가 실패한 git 의 문구.
