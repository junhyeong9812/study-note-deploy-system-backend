# study-note-deploy-system-backend

study-note 검색 시스템의 backend(Kotlin/Spring Boot). GPU 서버(.158)에서
콘텐츠·검색 API와 색인 파이프라인, 로그 중앙 큐(Redis)를 맡는다.

- 설계: `docs/design/es-index.md`(색인) · 루트 프로젝트 `docs/logging.md`(로그 규약)
- 구동: `.env`에 `LLM_URL`·`SYNC_SECRET` 지정 후 `docker compose up -d --build`

## 패키지 구조 (레이어 — 의존 방향 고정)

```
api/      Controller·GlobalErrorHandler·Envelope   (HTTP 관심사)
usecase/  SyncService·IndexingService·SearchService (오케스트레이션)
domain/   Chunker·DocClassifier·SearchHit·RewriteOutcome·TreeBuilder (순수 로직 — 내부 무의존)
infra/    GitRepository·EsClient·EmbeddingClient·LlmClient·RequestLog (외부 시스템)

api → usecase → (domain, infra) · infra → domain 허용 · domain은 아무것도 import하지 않음
의존 역전(인터페이스)은 도입하지 않음 — 인프라 교체·테스트 격리 요구가 생기는 시점에 (결정 2026-08-27)
```
