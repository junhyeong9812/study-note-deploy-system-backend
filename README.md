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

## 한계 — Redis 의 디스크 저장

`redis` 는 디스크 저장(AOF — `--appendonly yes` · `--appendfsync everysec`)을 켜 두었다. 데이터는 이름 있는 볼륨 `backend-redis-data`(`/data/appendonlydir`)에 있고, 컨테이너를 다시 만들어도 남는다. RDB 스냅숏은 켜지 않는다(`--save ""`).

- **디스크에 남는 것**: 방문의 원문(관측 서버가 가져가기 전의 스트림 `visits`) · 그날의 소금 · 대화 이력(7일) · 로그 · 지표 · 통신 스트림. 매초 디스크에 맞춰 쓴다 — 갑자기 꺼지면 마지막 1초까지 잃을 수 있다.
- **지운 방문이 디스크에서 사라지는 때**: AOF 는 덧붙이는 기록이라 지운 항목의 글자가 **다시 쓰기(rewrite) 전까지 파일에 남는다**. 다시 쓰기는 두 길로 돈다 — 파일이 10% 넘게 커지면(최소 1mb) Redis 가 스스로, 그리고 곁 서비스 `redis-aof-rewrite` 가 30분마다(`REDIS_AOF_REWRITE_INTERVAL_SECONDS`, 기본 1800초) `BGREWRITEAOF` 를 시킨다. 그래서 지운 방문의 원문은 **늦어도 약 30분 뒤**(주기 + 다시 쓰기의 시간)에 파일에서 사라진다. 메모리에서는 곧바로 사라진다(`--stream-node-max-entries 1`). 이것은 파일의 글자 기준이다 — 지워진 옛 AOF 파일이 파일시스템의 빈 블록에 남는 것은 범위 밖이다.
- **비용**: 다시 쓰기는 디스크에 하루 최대 약 2.3GB 를 쓴다(바탕 47MB × 하루 48번 — 로그 1만 · 지표 2만 · 통신 5천 항목 기준). 노드당 항목 1 로 스트림의 메모리가 약 1.45배가 된다(로그 5만 항목에서 +8.3 → +12.0MiB). 다시 쓰기 한 번은 0.5~0.9초이고 그 순간 `XADD` 가 수십 ms 늦을 수 있다.
- **볼륨이 지워지는 때**: `docker compose down -v` 한 번에 `backend-redis-data` 가 지워진다(방문 · 소금 · 대화 이력이 함께 사라진다). 운영에서는 `-v` 를 쓰지 않는다.
- **AOF 가 깨졌을 때**: 끝이 잘린 파일(갑자기 꺼짐)은 Redis 가 잘린 꼬리를 버리고 뜬다. 가운데가 깨지면 뜨지 않는다 — 컨테이너를 멈춘 뒤 `redis-check-aof --fix` 로 고친다(깨진 곳 뒤의 기록은 잃는다).
- **배포**: 배포 에이전트는 앱의 자리(`app-blue` · `app-green`)만 다시 만든다(`up -d --no-build --no-deps <자리>`) — `redis` 와 `redis-aof-rewrite` 는 postgres 처럼 이름 없이 `docker compose up -d` 할 때 뜬다.
- **서버에 반영할 때 한 번 사라지는 것**: 디스크 저장을 켜려고 `redis` 를 한 번 다시 만들 때, 그때까지 메모리에만 있던 대화 이력(최근 7일) · 그날의 소금(그날의 순방문자가 반영 전후로 두 번 세어진다) · 아직 넘어가지 않은 방문 · 로그 · 지표 · 통신 스트림의 사본과 관측 서버의 소비자 그룹이 사라진다.
