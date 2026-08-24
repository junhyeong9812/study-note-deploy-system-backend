FROM docker.elastic.co/elasticsearch/elasticsearch:8.17.3
# 한국어 형태소 분석기 — es-index.md D4의 nori analyzer 전제
RUN bin/elasticsearch-plugin install --batch analysis-nori
