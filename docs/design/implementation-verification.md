# [구현 검증] 중앙 대장 — backend

| # | 항목 | 출처 | 상태 |
|---|---|---|---|
| 1 | nori 사용자 사전·decompound 모드 (예: "락과" 미분해 실측) | es-index D4 | 미검증 |
| 2 | RRF k=60·kNN num_candidates=size*5 적정성 · kNN+filter 과필터 여부 | es-index D4 | 미검증 |
| 3 | BGE-M3 sparse(rank_features) 추가 여부 — 1차 제외 | es-index D4 | 미검증 |
| 4 | 전체 색인 refresh 전략(현재 파일당 refresh=true — 세그먼트 부하) | 리뷰 B16 | 미검증 |
| 5 | ES/임베딩 타임아웃 값(query 5s/bulk 60s/embed 3m)·MAX_EMBED_CHARS 9000 | 리뷰 B3·B10 | 1차 실측 대기 |
| 6 | Redis requirepass·maxmemory (LAN 전용 전제 하 보류) | 리뷰 B11 | 보류 |
| 7 | 검색 대역 기본 doc_kind 제외(question 등)의 사용성 | es-index D3 | 미검증 |
