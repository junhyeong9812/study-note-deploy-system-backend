#!/usr/bin/env bash
# 실제 PostgreSQL 에 대고 엔진 테스트(./gradlew engineTest)를 돈다.
# Docker 빌드의 test 단계 안에서는 DB 를 띄울 수 없어 이 스크립트를 로컬에서 따로 돈다.
# 띄운 컨테이너(익명 볼륨 포함)·네트워크는 성공·실패와 무관하게 지우고, 종료 코드는 테스트 결과를 그대로 전한다.
set -euo pipefail

repository="$(cd "$(dirname "$0")/.." && pwd)"
suffix="$$-$(date +%s)"
network="backend-quiz-engine-network-$suffix"
database="backend-quiz-engine-postgres-$suffix"
runner="backend-quiz-engine-runner-$suffix"
database_password="backend-test-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"   # 이 실행에서만 쓰고 버리는 값

cleanup() {
	# 이 실행이 띄운 것만 지운다 — 이름에 접미사가 붙어 다른 컨테이너를 건드리지 않는다
	# -v: 익명 볼륨(DB 데이터)까지 지운다 — 안 지우면 실행마다 볼륨이 남아 디스크가 찬다
	docker rm -f -v "$runner" "$database" >/dev/null 2>&1 || true
	docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "$network" >/dev/null
docker run -d --name "$database" --network "$network" \
	-e POSTGRES_DB=study -e POSTGRES_USER=study -e POSTGRES_PASSWORD="$database_password" \
	postgres:17-alpine >/dev/null

# 받을 준비가 될 때까지 기다린다(최대 60초)
for _ in $(seq 1 60); do
	if docker exec "$database" pg_isready -U study -d study -h 127.0.0.1 >/dev/null 2>&1; then
		ready=yes
		break
	fi
	sleep 1
done
if [ "${ready:-no}" != "yes" ]; then
	echo "engine-test: PostgreSQL 이 60초 안에 준비되지 않았다" >&2
	exit 1
fi

# 테스트 결과를 그대로 종료 코드로 — set -e 가 중간에 끊지 않게 받아 둔다
status=0
docker run --name "$runner" --network "$network" \
	-v "$repository":/app -v backend-gradle-home:/root/.gradle -w /app \
	-e BACKEND_TEST_DB_URL="jdbc:postgresql://$database:5432/study" \
	-e BACKEND_TEST_DB_USER=study -e BACKEND_TEST_DB_PASSWORD="$database_password" \
	eclipse-temurin:21-jdk ./gradlew engineTest --console=plain "$@" || status=$?

echo "engine-test: exit $status"
exit "$status"
