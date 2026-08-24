# 의사결정 — ES 인덱스 설계 (study-note 색인)

> 근거: study-note 실측(2026-08-24) — md 623개(avg 3.2KB), depth 2~4 혼재, 파일 유형: `1-question/2-summary/3-answer` 각 165 · `problem/analyze` 각 47(programmers) · `README` 12 · `index` 9 · 자유형 12(reference/writing evidence 최대 58KB, 세미나 등).

## D1. 논리 단위 — "파일"이 아니라 "청크(헤딩 섹션)"

- 검색 대상 문서 = **md 파일의 `##`(h2) 단위 청크**. 파일 전체를 한 벡터로 임베딩하면 58KB짜리 evidence류에서 검색 정밀도가 무너진다.
- 분할 규칙: h2 기준 분할 → 청크가 8KB 초과 시 h3 → 그래도 크면 문단 경계로 재분할. 1KB 미만 파일은 통째 1청크. h2 이전 전문(前文)은 chunk 0.
- `_id = <path>#<chunk_no>` (결정적) → 재색인이 곧 upsert. 파일 삭제/이동 시 `delete_by_query(term: path)` 후 재색인.

## D2. 경로 → 필드 매핑 (depth 편차 흡수)

`<topic>/<...중간 폴더...>/<subject>/<file>.md` — depth 2~4 전부 아래 규칙 하나로 처리:

| 필드 | 타입 | 규칙 | 예 |
|---|---|---|---|
| `path` | keyword | repo 상대 경로 (정본 키) | `cs/development-standards/operational-standards/2-summary.md` |
| `topic` | keyword | 첫 폴더 | `cs` |
| `topic_path` | keyword[] | 전체 폴더 배열(트리 재구성·필터용) | `["cs","development-standards","operational-standards"]` |
| `subject` | keyword | **마지막 폴더명 = 주제**. 파일이 topic 직속이면(`cs/index.md`) subject=topic | `operational-standards` |
| `depth` | integer | 폴더 깊이 | 3 |

## D3. 문서 유형 — 형태(form) 이원화 + 파일명 enum

**`form`(keyword) — 문서 형태 2분법**:
- `chapter` — 1/2/3 규약 챕터 (question↔answer 매핑·복습 UI 대상)
- `post` — 단일 기록(블로그형): 세미나·reference/writing evidence·guide 등 자유형. 위키에서 별도 섹션("기록")으로 노출

`doc_kind`(keyword): `question | summary | answer | index | readme | post`
(코테 problem/analyze는 study-note 규약 통일로 question/answer에 흡수 — 인덱서는 과거 파일명도 question/answer로 매핑)

| 파일명 | kind | 비고 |
|---|---|---|
| 1-question.md / 2-summary.md / 3-answer.md | question/summary/answer (form=chapter) | 학습 3종. 2-summary 부재 허용(코테) |
| problem.md / analyze.md | question/answer (과거 호환 매핑) | 통일 rename 후 소멸 예정 |
| index.md / README.md | index/readme | 목차·규칙 |
| **그 외 전부** | `post` (form=post) | 세미나·evidence·guide — 블로그형 단일 기록 |

- 검색 UI 기본값: `summary·answer·post`만 (question은 답이 없는 문서, problem은 문제 지문이라 검색 노이즈 — 필터로 켤 수 있게).
- 새 파일명 패턴이 나타나면 `post`로 흡수되므로 파이프라인이 깨지지 않는다 (fail-open은 유형 판정만; 색인 실패는 로그+재시도).

## D4. 매핑 (인덱스 `study-v1`, alias `study`)

```jsonc
{
  "settings": {
    "analysis": {
      "analyzer": {
        "ko": { "type": "custom", "tokenizer": "nori_tokenizer",
                "filter": ["nori_part_of_speech", "lowercase"] }
      }
    }
  },
  "mappings": { "properties": {
    "path":       { "type": "keyword" },
    "topic":      { "type": "keyword" },
    "topic_path": { "type": "keyword" },
    "subject":    { "type": "keyword" },
    "depth":      { "type": "integer" },
    "form":       { "type": "keyword" },
    "doc_kind":   { "type": "keyword" },
    "title":      { "type": "text", "analyzer": "ko" },     // h1 또는 파일명
    "heading":    { "type": "text", "analyzer": "ko" },     // 청크 헤딩 트레일 "h2 > h3"
    "content":    { "type": "text", "analyzer": "ko" },
    "chunk_no":   { "type": "integer" },
    "dense":      { "type": "dense_vector", "dims": 1024,
                    "index": true, "similarity": "cosine" }, // BGE-M3
    "commit_sha": { "type": "keyword" },
    "updated_at": { "type": "date" }
  } }
}
```

- **버전 인덱스 + alias**: 매핑 변경 시 `study-v2` 만들어 재색인 → alias 스왑(다운타임 0).
- 검색: `multi_match(title^3, heading^2, content)` BM25 + `knn(dense)` → **수동 RRF(k=60)** 병합. `topic`·`doc_kind` 필터는 양쪽 모두에 적용.
- `[구현 검증]` nori 세부(사용자 사전·decompound 모드), BGE-M3 sparse를 `rank_features`로 추가할지(1차 제외), RRF k값 — 구현 시 실데이터로 판정. → 중앙 대장 `docs/design/implementation-verification.md`.

## D5. 색인 파이프라인 불변식

> **sync 트리거 의미론(리뷰 B13 확정)**: 요청의 `commit_sha`는 **중복 트리거 판별용 힌트**이고, 색인 대상은 항상 **원격 HEAD**다. 요청 SHA보다 HEAD가 앞서면 최신을 색인한다(수렴) — 뒤처진 SHA의 재요청은 no-op으로 수렴하므로 멱등이 유지된다. 특정 SHA 고정 색인은 지원하지 않는다.


1. 색인 입력 = **git diff `<last_sha>..HEAD` `--name-status`** 의 md만 (전체 스캔은 수동 `/sync` 전용).
2. A/M → 해당 path 청크 전량 교체(delete_by_query → bulk upsert — 청크 수 감소 시 고아 방지). R → 구 path delete + 신 path 색인. D → delete.
3. 완료 후에만 `last_sha` 전진. 부분 실패 시 전진 금지 → 다음 트리거가 같은 diff 재처리(멱등).
4. record-level 검증: 색인 후 `count(path)` == 생성 청크 수 확인. 불일치 = 실패로 취급(silent failure 금지).
