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
