# study-note-deploy-system-backend

study-note 검색 시스템의 backend(Kotlin/Spring Boot). GPU 서버(.158)에서
콘텐츠·검색 API와 색인 파이프라인, 로그 중앙 큐(Redis)를 맡는다.

- 설계: `docs/design/es-index.md`(색인) · 루트 프로젝트 `docs/logging.md`(로그 규약)
- 구동: `.env`에 `LLM_URL`·`SYNC_SECRET` 지정 후 `docker compose up -d --build`
- 접속 기록(nginx — 루트 프로젝트 `docs/cross-cutting/plans/2026-11-25/traffic-map/communication-protocol.md` 3절): 요청마다 표준 출력에 JSON 한 줄(14칸, `service` 는 `backend`). 접속 주소 · 쿼리 문자열 · 리퍼러 · User-Agent · 쿠키 · 인증 머리글은 싣지 않고, 경로는 첫 `?` 앞 1024자까지다. 오류 기록은 표준 오류(주소가 든다 — 수집하지 않는다). 도커 로그는 10MB × 3 으로 묶는다.
  - 요청 ID(`X-Request-Id`): 사설 대역 · 루프백에서 온 꼴(`^[A-Za-z0-9_-]{1,64}$`) 맞는 값만 믿고, 아니면 nginx 가 새로 만들어 앱에 넘기고 응답 머리글로 돌려준다. 호출자(`X-Caller-Service`)도 사설 대역의 아는 이름만 그대로, 나머지는 `outside` 로 덮어써 넘긴다 — 지도 표시용이지 인증이 아니다.
  - 배포: 이 변경을 병합 · 배포한 뒤 서버에서 한 번 `docker compose up -d nginx` 로 nginx 컨테이너를 다시 만든다 — (가) 로그 크기 제한(10MB × 3)이 그때 걸린다(설정 복사 · reload 로는 걸리지 않는다) (나) 이전 형식으로 쌓인 접속 기록(방문자 주소 · 쿼리가 든 줄)이 컨테이너의 로그와 함께 지워진다 (다) reload 뒤 옛 워커가 끝날 때까지(`worker_shutdown_timeout` — 최대 180초) 옛 형식의 줄을 쓰는 구간이 없어진다.

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
