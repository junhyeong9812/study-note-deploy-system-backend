#!/usr/bin/env bash
# Redis 의 디스크 저장(AOF)을 실제 redis 컨테이너로 확인한다 — docker-compose.yml 의 `redis` 서비스가 가진 실행 인자 · 볼륨을 그대로 읽어 띄운다
# (설정을 이 스크립트에 옮겨 적지 않는다: compose 를 고치면 이 검사가 그 값을 본다).
#
#   scripts/redis-persistence-check.sh text   # 설정 파일의 글자만(컨테이너를 띄우지 않는다 · 몇 초)
#   scripts/redis-persistence-check.sh run    # 실제 컨테이너로(몇 분)
#   scripts/redis-persistence-check.sh        # 둘 다
#
# 주기적 다시 쓰기: compose 에는 `redis` 에 BGREWRITEAOF 를 주기로 시키는 곁 서비스가 하나 있다(이름은 보지 않는다 — 명령에 BGREWRITEAOF 가 든 서비스).
# 그 주기는 compose 의 `${REDIS_AOF_REWRITE_INTERVAL_SECONDS:-1800}` 로 정한다(계약) — 이 검사는 그 변수를 몇 초로 주어 compose 를 다시 해석하고,
# 해석된 곁 서비스의 명령을 그대로 띄운다(30분을 기다리지 않는다). 곁 서비스는 주기의 바닥(REDIS_AOF_REWRITE_MIN_INTERVAL_SECONDS — compose 의 글자 60)
# 보다 짧은 주기를 받지 않으므로, 이 검사는 짧은 주기로 띄울 때만 그 바닥을 1 로 준다(S8 은 compose 의 바닥 그대로 본다).
# redis 의 명령은 뜰 때 /data 의 남은 임시 파일을 지우는 sh 를 앞에 두고 `redis-server …` 를 넘긴다 — 띄울 때는 명령 전체를, 인자의 검사는 `redis-server` 부터 본다.
#
# 값은 가짜만 쓴다(주소는 문서용 대역 192.0.2.0/24 · 198.51.100.0/24 · 203.0.113.0/24). 띄운 컨테이너 · 볼륨은 이름에 이 실행의 접미사가 붙고,
# 성공 · 실패와 무관하게 정확한 이름으로 지운다(prune 하지 않는다 · 운영과 같은 이름 `backend-redis` 는 쓰지 않는다).
# compose 를 읽을 때 `.env` 를 읽지 않는다(--env-file /dev/null — 필요한 값은 가짜로 넣는다). 필요한 것: docker(compose v2) · python3.
# 종료 코드: 실패한 검사가 하나라도 있으면 1.
set -uo pipefail

repository="$(cd "$(dirname "$0")/.." && pwd)"
compose_file="$repository/docker-compose.yml"
readme_file="$repository/README.md"
mode="${1:-all}"
suffix="$$-$(date +%s)"
prefix="backend-redis-check-$suffix"
data_volume="$prefix-data"
failures=0
measurements=()

pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }
warn() { echo "WARN  $1"; }
measure() { measurements+=("$1|$2"); }

interval_variable="REDIS_AOF_REWRITE_INTERVAL_SECONDS"   # 곁 서비스의 주기(초) — compose 의 변수 이름(계약)
floor_variable="REDIS_AOF_REWRITE_MIN_INTERVAL_SECONDS"  # 곁 서비스가 받는 주기의 바닥(초) — compose 의 글자(검사만 낮춘다)
short_interval=3                                       # 실행 검사에서 줄 주기(초)
# S1 의 합격 기준 = 한 주기 + 이 여유(ms). 여유 = 곁 서비스가 다시 쓰기의 결과를 기다리는 1초 간격 + 다시 쓰기(작은 데이터 · 수 ms) + 이 검사가
# 디스크를 보는 간격(0.2초 + docker exec). 다시 쓰기가 끝난 바로 뒤에 지우는(가장 늦는) 꼴로 열 번 잰 한 주기 위의 넘침: 837~1150ms(2026-11-28 ·
# 7.4.12) — 그 두 배 남짓을 여유로 둔다. 두 주기가 걸리는 구현(주기 3초면 약 8초)은 넘는다.
s1_slack_ms=2500

# --- compose(해석된 꼴 · 모든 서비스) -------------------------------------------------------------------------
resolve_compose() {   # resolve_compose [덧붙일 환경 변수 …]
	(cd /tmp && env -i PATH="$PATH" HOME="$HOME" \
		BIND_ADDR=192.0.2.10 LLM_URL=http://192.0.2.11:8000 SYNC_SECRET=fake-sync STUDY_DB_NAME=fake STUDY_DB_USER=fake STUDY_DB_PASSWORD=fake-password "$@" \
		docker compose --env-file /dev/null -p backend-redis-check -f "$compose_file" config --format json)
}
compose_json="$(resolve_compose)" || { echo "FAIL  docker compose config 가 compose 파일을 읽지 못했다" >&2; exit 1; }
short_compose_json="$(resolve_compose "$interval_variable=$short_interval")" || { echo "FAIL  docker compose config(짧은 주기)가 실패했다" >&2; exit 1; }

# 곁 서비스: redis 가 아닌 서비스 가운데 명령 · entrypoint · 환경에 BGREWRITEAOF 가 든 것
rewriter_names() {   # rewriter_names <compose json>
	python3 -c '
import json, sys
document = json.load(sys.stdin)
for name, service in document["services"].items():
    if name == "redis":
        continue
    text = json.dumps([service.get("command"), service.get("entrypoint"), service.get("environment")])
    if "BGREWRITEAOF" in text.upper():
        print(name)' <<<"$1"
}
mapfile -t rewriters < <(rewriter_names "$compose_json")
rewriter_field() {   # rewriter_field <compose json> <python 식 — service 는 곁 서비스>
	python3 -c 'import json,sys; document=json.load(sys.stdin); service=document["services"][sys.argv[2]]; print(eval(sys.argv[1]))' "$2" "${rewriters[0]}" <<<"$1"
}
# 목록(command · entrypoint)의 항목을 NUL 로 나눠 낸다 — 항목 안의 줄바꿈(sh -c 의 글)과 빈 항목("")이 그대로 남게.
# `config` 의 출력은 compose 의 이스케이프 `$$` 를 그대로 둔다 — `compose up` 이 컨테이너에 넘기는 꼴(`$`)로 되돌린다
list_field() {   # list_field <compose json> <서비스> <키>
	python3 -c 'import json,sys; d=json.load(sys.stdin); v=d["services"][sys.argv[1]].get(sys.argv[2]) or []; sys.stdout.write("".join(p.replace("$$","$")+"\0" for p in v))' "$2" "$3" <<<"$1"
}
rewriter_text() {    # 명령 · entrypoint · 환경을 한 덩어리 글자로
	rewriter_field "$1" 'json.dumps([service.get("command"), service.get("entrypoint"), service.get("environment")], ensure_ascii=False)'
}

service_field() {   # service_field <python 식 — service 는 redis 서비스, document 는 전체>
	python3 -c 'import json,sys; document=json.load(sys.stdin); service=document["services"]["redis"]; print(eval(sys.argv[1]))' "$1" <<<"$compose_json"
}
# 띄우는 명령 전체(launch_command) · 그 가운데 `redis-server` 부터의 실행 인자(redis_command — 빈 값 "" 도 빈 항목으로 남는다)
mapfile -d '' -t launch_command < <(list_field "$compose_json" redis command)
redis_command=()
for index in "${!launch_command[@]}"; do
	if [ "${launch_command[$index]}" = "redis-server" ]; then redis_command=("${launch_command[@]:$index}"); break; fi
done
image="$(service_field 'service["image"]')"
# /data 에 붙은 볼륨: "volume <이름>" · "bind <경로>" · 없으면 "none"
data_mount="$(service_field 'next((m["type"]+" "+m["source"] for m in service.get("volumes") or [] if m["target"]=="/data"), "none")')"

argument_value() {   # argument_value --이름 → 값(없으면 <absent>)
	local index
	for index in "${!redis_command[@]}"; do
		if [ "${redis_command[$index]}" = "$1" ]; then printf '%s' "${redis_command[$((index + 1))]-<absent>}"; return; fi
	done
	printf '<absent>'
}

# ============================================================================================================
text_checks() {
	echo "== 설정 파일의 글자"
	[ "$(argument_value --appendonly)" = "yes" ] && pass "T1 --appendonly yes" || fail "T1 --appendonly 가 yes 가 아니다: $(argument_value --appendonly)"
	[ "$(argument_value --appendfsync)" = "everysec" ] && pass "T2 --appendfsync everysec" || fail "T2 --appendfsync 가 everysec 가 아니다: $(argument_value --appendfsync)"
	[ "$(argument_value --save)" = "" ] && pass "T3 --save \"\" (RDB 스냅숏 꺼짐)" || fail "T3 --save 가 \"\" 가 아니다(RDB 가 켜진다): $(argument_value --save)"
	[ "$(argument_value --stream-node-max-entries)" = "1" ] && pass "T4 --stream-node-max-entries 1" \
		|| fail "T4 --stream-node-max-entries 가 1 이 아니다(지운 방문이 노드에 남는다): $(argument_value --stream-node-max-entries)"
	local percentage minimum
	percentage="$(argument_value --auto-aof-rewrite-percentage)"; minimum="$(argument_value --auto-aof-rewrite-min-size)"
	if [[ "$percentage" =~ ^[0-9]+$ ]] && [ "$percentage" -ge 1 ] && [ "$percentage" -lt 100 ]; then pass "T5 --auto-aof-rewrite-percentage $percentage (기본 100 보다 작다)"
	else fail "T5 --auto-aof-rewrite-percentage 가 1~99 가 아니다(기본 100 이면 지운 방문이 디스크에 오래 남는다): $percentage"; fi
	if [ "$minimum" != "<absent>" ] && [ "$(echo "$minimum" | tr 'A-Z' 'a-z')" != "64mb" ]; then pass "T6 --auto-aof-rewrite-min-size $minimum (기본 64mb 가 아니다)"
	else fail "T6 --auto-aof-rewrite-min-size 가 없거나 기본 64mb 다: $minimum"; fi
	local volume_name="${data_mount#volume }"
	if [[ "$data_mount" == volume\ * ]] && [ "$volume_name" = "backend-redis-data" ]; then
		pass "T7 /data 에 이름 있는 볼륨 backend-redis-data"
		python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if "backend-redis-data" in (d.get("volumes") or {}) else 1)' <<<"$compose_json" \
			&& pass "T8 최상위 volumes 에 backend-redis-data 가 있다" || fail "T8 최상위 volumes 에 backend-redis-data 가 없다"
	else
		fail "T7 /data 에 이름 있는 볼륨 backend-redis-data 가 없다: $data_mount"
	fi
	# 주석 — redis 서비스 블록(다음 서비스 앞까지)의 글자
	local block
	block="$(awk '/^  redis:/{inside=1; print; next} inside && /^  [a-z#]/{exit} inside{print}' "$compose_file")"
	if grep -q "영속 불필요" <<<"$block"; then fail "T9 redis 의 주석이 아직 '영속 불필요'라고 말한다"
	elif grep -qi "AOF\|디스크" <<<"$block"; then pass "T9 redis 의 주석이 디스크 저장(AOF)을 말한다"
	else fail "T9 redis 블록에 디스크 저장(AOF · 디스크)을 말하는 주석이 없다"; fi
	# README 의 한계 절 — `## …한계…` 제목 아래(다음 `## ` 앞까지)에 AOF 와 다시 쓰기
	local limits
	limits="$(awk '/^## .*한계/{inside=1; print; next} inside && /^## /{exit} inside{print}' "$readme_file")"
	if [ -z "$limits" ]; then fail "T10 README 에 '한계' 가 든 ## 절이 없다"
	elif grep -q "AOF" <<<"$limits" && grep -q "다시 쓰기" <<<"$limits"; then pass "T10 README 의 한계 절이 AOF 와 다시 쓰기를 말한다"
	else fail "T10 README 의 한계 절에 'AOF' · '다시 쓰기' 가 없다"; fi

	# --- 곁 서비스(주기적 다시 쓰기) ---
	if [ "${#rewriters[@]}" != "1" ]; then
		fail "T11 redis 에 BGREWRITEAOF 를 시키는 서비스가 정확히 하나가 아니다: ${#rewriters[@]} 개(${rewriters[*]:-})"
		return
	fi
	pass "T11 redis 에 BGREWRITEAOF 를 시키는 서비스가 하나다: ${rewriters[0]}"
	local default_text default_interval short_interval_value
	default_text="$(rewriter_text "$compose_json")"
	# 곁 서비스에 넘어가는 주기의 값 — 기본은 글자 1800, 변수를 주면 그 값(명령 안의 1800 은 잘못된 값일 때의 대체라 보지 않는다)
	default_interval="$(rewriter_field "$compose_json" '(service.get("environment") or {}).get("'"$interval_variable"'")')"
	short_interval_value="$(rewriter_field "$short_compose_json" '(service.get("environment") or {}).get("'"$interval_variable"'")')"
	if [ "$default_interval" = "1800" ] && [ "$short_interval_value" = "$short_interval" ]; then
		pass "T12 주기가 1800초이고 \$$interval_variable 로 덮어쓸 수 있다"
	else fail "T12 곁 서비스의 환경 $interval_variable 가 기본 1800 이 아니거나 변수로 바뀌지 않는다(기본 '$default_interval' · 덮어쓴 값 '$short_interval_value')"; fi
	[ "$(rewriter_field "$compose_json" 'service["image"]')" = "$image" ] && pass "T13 곁 서비스의 이미지가 redis 와 같다($image)" \
		|| fail "T13 곁 서비스의 이미지가 redis($image)와 다르다: $(rewriter_field "$compose_json" 'service["image"]')"
	local restart_policy; restart_policy="$(rewriter_field "$compose_json" 'service.get("restart") or ""')"
	[ -n "$restart_policy" ] && [ "$restart_policy" != "no" ] && pass "T14 곁 서비스에 restart: $restart_policy" || fail "T14 곁 서비스에 restart: 가 없다"
	if grep -qE -- '-h[ \\"]+redis([^-a-zA-Z0-9]|$)|redis://redis([:/"]|$)' <<<"$default_text"; then pass "T15 곁 서비스가 redis 서비스(-h redis)를 가리킨다"
	else fail "T15 곁 서비스가 redis 서비스를 가리키지 않는다: $default_text"; fi
	# Redis 가 늦게 뜨거나 다시 쓰기가 이미 도는 중이어도 죽지 않는 꼴 — 되풀이(while)가 있고, 실패에 끝내는 길(set -e · || exit)이 없다
	if grep -q "while" <<<"$default_text" && ! grep -qE 'set -e|\|\|[ ]*exit|&&[ ]*exit' <<<"$default_text"; then
		pass "T16 곁 서비스는 되풀이하고, 실패에 끝나는 길(set -e · || exit)이 없다"
	else fail "T16 곁 서비스가 되풀이하지 않거나 실패에 끝난다: $default_text"; fi
}

# ============================================================================================================
cleanup() {
	# 이 실행이 띄운 것만 — 정확한 이름으로
	local name
	for name in "$prefix-redis" "$prefix-fresh" "$prefix-cost" "$prefix-memory-default" "$prefix-memory-one" "$prefix-quiet" "$prefix-corrupt" \
		"$prefix-killed" "$prefix-rewriter" "$prefix-rewriter-redis" "$prefix-interval-1" "$prefix-interval-2" "$prefix-interval-3" \
		"$prefix-interval-4" "$prefix-interval-5" "$prefix-interval-6" "$prefix-stop"; do
		docker rm -f -v "$name" >/dev/null 2>&1 || true
	done
	for name in "$data_volume" "$prefix-quiet-data" "$prefix-corrupt-data" "$prefix-permission-data" "$prefix-killed-data" "$prefix-rewriter-data"; do
		docker volume rm "$name" >/dev/null 2>&1 || true
	done
	docker network rm "$prefix-network" >/dev/null 2>&1 || true
}

# 지운 항목의 글자를 바이트로 찾으려고 `--rdbcompression no` 만 덧붙인다 — 압축(LZF)은 바탕 파일 · DUMP 의 바이트 꼴만 바꾸고 어떤 항목이 남는지는
# 바꾸지 않는다(압축하면 같은 꼴이 반복되는 주소가 뒤 참조로 바뀌어 grep 이 놓친다 — 실측).
wait_ping() {   # wait_ping <컨테이너> — 10초 안에 PONG
	local attempt
	for attempt in $(seq 1 50); do
		[ "$(docker exec "$1" redis-cli ping 2>/dev/null)" = "PONG" ] && return 0
		sleep 0.2
	done
	return 1
}
start_redis() {   # start_redis <컨테이너> <볼륨|none> [덧붙일 인자…] — compose 의 명령 전체(뜰 때의 임시 파일 지우기 포함)로 띄운다
	local name="$1" volume="$2"; shift 2
	local mount=()
	[ "$volume" != "none" ] && mount=(-v "$volume:/data")
	docker run -d --name "$name" "${mount[@]}" "$image" "${launch_command[@]}" --rdbcompression no "$@" >/dev/null || return 1
	wait_ping "$name"
}
redis_in() { local name="$1"; shift; docker exec "$name" redis-cli "$@"; }
# 명령 묶음을 넣는다 — 오류가 하나라도 있으면 실패로 센다(조용히 넘기지 않는다). 만드는 명령을 인자로 받아 이 셸에서 판정한다:
# `만드는 명령 | pipe_into …` 꼴이면 pipe_into 가 하위 셸에서 돌아 그 안의 fail 이 실패 수에 더해지지 않는다(리뷰의 실측).
pipe_into() {   # pipe_into <컨테이너> <만드는 명령…>
	local name="$1"; shift
	local summary; summary="$("$@" | docker exec -i "$name" redis-cli --pipe 2>&1 | tail -1)"
	[[ "$summary" == "errors: 0,"* ]] || fail "명령 묶음에 오류가 있었다($name): $summary"
}
# on_disk <컨테이너> <글자> → /data 아래 모든 파일(appendonlydir 의 바탕 · 덧붙임 + 다시 쓰기의 임시 파일 · dump.rdb 등)에서 그 글자의 수.
# 컨테이너에 닿지 못하거나 /data 가 없으면 수 대신 "unreadable" 과 실패 코드 — 0(사라졌다)으로 보이지 않게.
# 다시 쓰기 중에 이름이 바뀌어 사라지는 파일의 읽기 오류는 넘긴다(다음에 볼 때 새 이름으로 보인다).
on_disk() {
	local count
	count="$(docker exec "$1" sh -c 'find /data -type f -exec cat {} + 2>/dev/null; test -d /data' | { grep -a -o -F "$2" || true; } | wc -l)" \
		|| { echo "unreadable"; return 1; }
	echo "$count"
}
rewrites_of() { redis_in "$1" INFO persistence | tr -d '\r' | awk -F: '/^aof_rewrites:/{print $2}'; }
info_field() { redis_in "$1" INFO "$2" | tr -d '\r' | awk -F: -v key="$3" '$1==key{print $2}'; }
wait_rewrite_done() {   # 다시 쓰기가 끝나기를 기다린다 — 끝나지 않으면 실패로 센다(뒤의 판정이 끝나지 않은 다시 쓰기 위에서 돌지 않게)
	local attempt
	for attempt in $(seq 1 600); do
		[ "$(info_field "$1" persistence aof_rewrite_in_progress)" = "0" ] && [ "$(info_field "$1" persistence aof_rewrite_scheduled)" = "0" ] && return 0
		sleep 0.05
	done
	fail "다시 쓰기가 끝나기를 기다리다 시간이 다 됐다($1)"
	return 1
}

# RESP 생성 — 로그 줄(절반은 무작위: 압축이 지나치게 잘 되지 않게) · 방문 한 건(observe 의 차례: 넣기 → 읽기 → 확인 → 지우기)
resp_logs() {   # resp_logs <스트림> <수> <줄의 바이트> <MAXLEN>
	python3 - "$@" <<'PYTHON'
import os, sys
stream, count, size, maxlen = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
out = sys.stdout.buffer
base = "req-3f9a0c1b2d4e:backend:GET /api/doc ok cs/algorithm/06-binary-search/2-summary.md " * 40
def command(*parts):
    out.write(b"*%d\r\n" % len(parts))
    for part in parts:
        encoded = str(part).encode(); out.write(b"$%d\r\n%s\r\n" % (len(encoded), encoded))
for index in range(count):
    half = size // 2
    line = base[:half] + os.urandom(size).hex()[: size - half]
    command("XADD", stream, "MAXLEN", "~", maxlen, "*", "level", "info", "line", line)
PYTHON
}
resp_visits() {   # resp_visits <ID 의 밀리초> <첫 번호> <수> <주소 접두> — ID 는 <밀리초>-<번호>(앞선 항목보다 커야 한다)
	python3 - "$@" <<'PYTHON'
import sys
milliseconds, start, count, address_prefix = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
out = sys.stdout.buffer
def command(*parts):
    out.write(b"*%d\r\n" % len(parts))
    for part in parts:
        encoded = str(part).encode(); out.write(b"$%d\r\n%s\r\n" % (len(encoded), encoded))
for number in range(start, start + count):
    entry_id = "%s-%d" % (milliseconds, number)
    command("XADD", "visits", "MAXLEN", "~", "50000", entry_id, "v", "1", "path", "/cs/algorithm/06-binary-search", "screen", "summary",
            "device", "desktop", "visitor", "%064x" % number, "ip", "%s%d" % (address_prefix, number % 250))
    command("XREADGROUP", "GROUP", "observe", "check", "COUNT", "1", "STREAMS", "visits", ">")
    command("XACK", "visits", "observe", entry_id)
    command("XDEL", "visits", entry_id)
PYTHON
}

# ============================================================================================================
run_checks() {
	echo "== 실제 컨테이너($image) — compose 의 인자: ${redis_command[*]} · /data: $data_mount"
	local volume_for_run="none"
	[[ "$data_mount" == volume\ * ]] && volume_for_run="$data_volume"
	[[ "$data_mount" == bind\ * ]] && { warn "/data 가 호스트 디렉터리다 — 이 검사는 그 대신 임시 볼륨을 쓴다"; volume_for_run="$data_volume"; }
	local main="$prefix-redis"

	# --- R1 빈 볼륨에서 처음 뜨고 · 다시 만들어도 남는다 ------------------------------------------------------
	start_redis "$main" "$volume_for_run" || { fail "R1 빈 볼륨에서 뜨지 않았다"; docker logs "$main" 2>&1 | tail -5; return; }
	pass "R1a 빈 볼륨에서 처음 뜬다"
	[ "$(redis_in "$main" CONFIG GET appendonly | tail -1)" = "yes" ] && pass "R1b 뜬 서버의 appendonly = yes" || fail "R1b 뜬 서버의 appendonly 가 yes 가 아니다"
	[ "$(redis_in "$main" CONFIG GET save | tail -1)" = "" ] && pass "R1c 뜬 서버의 save = \"\" (RDB 꺼짐)" || fail "R1c 뜬 서버의 save 가 비어 있지 않다: $(redis_in "$main" CONFIG GET save | tail -1)"
	redis_in "$main" XADD visits '*' v 1 path /cs/a ip 192.0.2.41 >/dev/null
	redis_in "$main" SET visits:salt:2026-11-27 0123456789abcdef0123456789abcdef PX 3600000 NX >/dev/null
	redis_in "$main" RPUSH 'chat:00000000-0000-4000-8000-000000000001:cs/a.md' '{"role":"user","content":"fake"}' >/dev/null
	redis_in "$main" EXPIRE 'chat:00000000-0000-4000-8000-000000000001:cs/a.md' 604800 >/dev/null
	sleep 1.5   # everysec — 1초 안의 쓰기는 정전에 잃을 수 있다(계약)
	local ttl_before; ttl_before="$(redis_in "$main" PTTL visits:salt:2026-11-27)"
	docker rm -f "$main" >/dev/null   # SIGKILL — 정상 종료의 저장을 기대하지 않는다
	sleep 2
	start_redis "$main" "$volume_for_run" || { fail "R1 다시 만든 컨테이너가 뜨지 않았다"; return; }
	local stream_left salt_left chat_left ttl_after chat_ttl
	stream_left="$(redis_in "$main" XLEN visits)"; salt_left="$(redis_in "$main" GET visits:salt:2026-11-27)"
	chat_left="$(redis_in "$main" LLEN 'chat:00000000-0000-4000-8000-000000000001:cs/a.md')"
	ttl_after="$(redis_in "$main" PTTL visits:salt:2026-11-27)"; chat_ttl="$(redis_in "$main" TTL 'chat:00000000-0000-4000-8000-000000000001:cs/a.md')"
	measure "다시 만든 뒤 남은 것" "방문 스트림 $stream_left 건 · 소금 '$( [ -n "$salt_left" ] && echo 있음 || echo 없음)' · 대화 $chat_left 줄 · 소금 PTTL ${ttl_before}→${ttl_after} · 대화 TTL $chat_ttl"
	[ "$stream_left" = "1" ] && pass "R1d 다시 만든 뒤 방문 스트림 항목이 남는다" || fail "R1d 다시 만든 뒤 방문 스트림 항목이 없다(XLEN $stream_left)"
	[ "$salt_left" = "0123456789abcdef0123456789abcdef" ] && pass "R1e 다시 만든 뒤 그날의 소금이 남는다" || fail "R1e 다시 만든 뒤 소금이 없다"
	[ "$chat_left" = "1" ] && pass "R1f 다시 만든 뒤 대화 이력이 남는다" || fail "R1f 다시 만든 뒤 대화 이력이 없다"
	if [[ "$ttl_after" =~ ^[0-9]+$ ]] && [ "$ttl_after" -gt 0 ] && [ "$ttl_after" -le "$ttl_before" ]; then pass "R1g 만료가 이어진다(PTTL $ttl_before → $ttl_after)"
	else fail "R1g 만료가 이어지지 않는다(PTTL $ttl_before → $ttl_after)"; fi
	redis_in "$main" FLUSHALL >/dev/null

	# --- R2 지운 방문의 원문이 메모리에 없다 ---------------------------------------------------------------
	local marker="203.0.113.77" kept="198.51.100.55" ids=() index
	redis_in "$main" XGROUP CREATE visits observe '$' MKSTREAM >/dev/null
	for index in 1 2 3 4; do
		if [ "$index" = 2 ]; then ids+=("$(redis_in "$main" XADD visits '*' v 1 path /cs/a ip "$marker")")
		else ids+=("$(redis_in "$main" XADD visits '*' v 1 path /cs/a ip "198.51.100.$((10 + index))")"); fi
	done
	ids+=("$(redis_in "$main" XADD visits '*' v 1 path /cs/b ip "$kept")")   # 아직 넘어가지 않은 방문 — 스트림에 남는다
	redis_in "$main" XREADGROUP GROUP observe check COUNT 10 STREAMS visits '>' >/dev/null
	redis_in "$main" XACK visits observe "${ids[@]:0:4}" >/dev/null
	redis_in "$main" XDEL visits "${ids[@]:0:4}" >/dev/null
	local dump_marker dump_kept
	dump_marker="$(docker exec "$main" redis-cli --raw DUMP visits | grep -a -o -F "$marker" | wc -l)"
	dump_kept="$(docker exec "$main" redis-cli --raw DUMP visits | grep -a -o -F "$kept" | wc -l)"
	measure "지운 방문이 메모리(DUMP)에" "지운 주소 $dump_marker 번 · 남긴 주소 $dump_kept 번 · MEMORY USAGE visits $(redis_in "$main" MEMORY USAGE visits) 바이트"
	if [ "$dump_kept" -lt 1 ]; then fail "R2 대조 실패 — 남긴 항목의 주소조차 DUMP 에 보이지 않는다(검사가 아무것도 보지 못한다)"
	elif [ "$dump_marker" = "0" ]; then pass "R2 지운 방문의 주소가 메모리(DUMP)에 없다 · 남긴 항목은 보인다"
	else fail "R2 지운 방문의 주소가 메모리(DUMP)에 남아 있다 — 같은 노드에 남은 항목이 있어서($dump_marker 번)"; fi

	# --- R3 지운 방문이 디스크에서 사라진다 ----------------------------------------------------------------
	sleep 1.2
	local before_rewrite; before_rewrite="$(on_disk "$main" "$marker")"
	measure "지운 직후 AOF 에" "지운 주소 $before_rewrite 번(덧붙임 파일의 XADD 원문)"
	[ "$before_rewrite" -ge 1 ] && pass "R3a 대조 — 지운 직후에는 AOF(덧붙임)에 원문이 있다(grep 이 볼 수 있다)" \
		|| fail "R3a 대조 실패 — 지운 직후 AOF 에서 원문을 찾지 못했다(appendonly 가 꺼졌거나 grep 이 보지 못한다)"
	# 바탕: 로그 스트림을 서버의 MAXLEN(~10000)까지 · 다시 쓰기로 바탕을 만든다(그 뒤 자동 다시 쓰기의 문턱은 이 바탕의 퍼센트)
	pipe_into "$main" resp_logs logs 10000 200 10000
	redis_in "$main" BGREWRITEAOF >/dev/null; wait_rewrite_done "$main"
	# 방문 한 건이 AOF 를 키우는 양(100건으로 잰다 · 다시 쓰기의 문턱보다 훨씬 작다)
	local size_before size_after visit_bytes
	sleep 1.2; size_before="$(info_field "$main" persistence aof_current_size)"
	pipe_into "$main" resp_visits 7000000000000 1 100 192.0.2.
	sleep 1.2; size_after="$(info_field "$main" persistence aof_current_size)"; visit_bytes=$(( (size_after - size_before) / 100 ))
	[ "$visit_bytes" -gt 0 ] || { fail "R3 방문 100건을 넣어도 AOF 가 커지지 않았다(appendonly 꺼짐?)"; visit_bytes=1; }
	# 앞의 BGREWRITEAOF 가 marker 를 이미 거둔다 — 잦은 날은 새 표지로 다시 잰다
	local busy_marker="203.0.113.88" busy_id="8000000000000-1" minutes=0 rewrites_before
	redis_in "$main" XADD visits "$busy_id" v 1 path /cs/a ip "$busy_marker" >/dev/null
	redis_in "$main" XREADGROUP GROUP observe check COUNT 10 STREAMS visits '>' >/dev/null
	redis_in "$main" XACK visits observe "$busy_id" >/dev/null; redis_in "$main" XDEL visits "$busy_id" >/dev/null
	rewrites_before="$(rewrites_of "$main")"
	sleep 1.2
	local busy_seen; busy_seen="$(on_disk "$main" "$busy_marker")"
	local base_size threshold_percentage minimum_size
	base_size="$(info_field "$main" persistence aof_base_size)"
	threshold_percentage="$(redis_in "$main" CONFIG GET auto-aof-rewrite-percentage | tail -1)"
	minimum_size="$(redis_in "$main" CONFIG GET auto-aof-rewrite-min-size | tail -1)"
	# 잦은 날: 분당 300 건을 1분 몫씩 넣고, 매 몫 뒤에 디스크를 본다(최대 60분 몫 = 상한 1시간)
	while [ "$minutes" -lt 60 ]; do
		minutes=$((minutes + 1))
		pipe_into "$main" resp_visits 8000000000001 $((minutes * 1000)) 300 203.0.113.
		sleep 1.2; wait_rewrite_done "$main"
		[ "$(on_disk "$main" "$busy_marker")" = "0" ] && break
	done
	local busy_left; busy_left="$(on_disk "$main" "$busy_marker")"
	measure "AOF 가 커지는 양" "방문 한 건(넣기·읽기·확인·지우기) $visit_bytes 바이트 · 바탕 $base_size 바이트 · 문턱 percentage=$threshold_percentage min-size=$minimum_size"
	measure "잦은 날(분당 300)" "$( [ "$busy_left" = 0 ] && echo "${minutes}분 몫 뒤 디스크에서 사라짐" || echo "60분 몫 뒤에도 디스크에 남음") · 자동 다시 쓰기 $(( $(rewrites_of "$main") - rewrites_before )) 번"
	if ! [ "$busy_seen" -ge 1 ] 2>/dev/null; then fail "R3b 대조 실패 — 지운 직후 AOF 에 원문이 없어 사라지는 시각을 잴 수 없다"
	else [ "$busy_left" = "0" ] && pass "R3b 잦은 날(분당 300): 지운 방문이 ${minutes}분 몫 안에 디스크에서 사라진다(자동 다시 쓰기)" \
		|| fail "R3b 잦은 날(분당 300): 60분 몫이 지나도 지운 방문이 디스크에 남는다(자동 다시 쓰기의 문턱이 크다)"; fi

	# 드문 날: 하루 30건 — 자동 다시 쓰기가 오는가(같은 바탕에서)
	redis_in "$main" BGREWRITEAOF >/dev/null; wait_rewrite_done "$main"
	local quiet_marker="203.0.113.99" quiet_id="8000000000002-1"
	redis_in "$main" XADD visits 8000000000001-99999999 v 1 path /cs/b ip 198.51.100.65 >/dev/null   # 아직 넘어가지 않은 방문(앞의 이웃)
	redis_in "$main" XADD visits "$quiet_id" v 1 path /cs/a ip "$quiet_marker" >/dev/null
	redis_in "$main" XREADGROUP GROUP observe check COUNT 10 STREAMS visits '>' >/dev/null
	redis_in "$main" XACK visits observe "$quiet_id" >/dev/null; redis_in "$main" XDEL visits "$quiet_id" >/dev/null
	redis_in "$main" XADD visits 8000000000002-2 v 1 path /cs/b ip 198.51.100.66 >/dev/null   # 아직 넘어가지 않은 방문(뒤의 이웃) — 지운 것과 같은 노드에 남는다
	sleep 1.2
	local quiet_seen; quiet_seen="$(on_disk "$main" "$quiet_marker")"
	pipe_into "$main" resp_visits 8000000000003 1 30 198.51.100.
	sleep 1.5; wait_rewrite_done "$main"
	local quiet_left needed_bytes
	quiet_left="$(on_disk "$main" "$quiet_marker")"
	base_size="$(info_field "$main" persistence aof_base_size)"
	needed_bytes=$(( base_size * threshold_percentage / 100 ))
	[ "$needed_bytes" -lt $((minimum_size - base_size)) ] && needed_bytes=$((minimum_size - base_size))
	measure "드문 날(하루 30건)" "$( [ "$quiet_left" = 0 ] && echo "사라짐" || echo "하루 몫 뒤에도 디스크에 남음") · 다음 자동 다시 쓰기까지 더 쌓여야 할 양 ≈ $needed_bytes 바이트(방문만이면 약 $(( needed_bytes / visit_bytes )) 건 — 로그 · 지표 쓰기가 함께 채운다)"
	if ! [ "$quiet_seen" -ge 1 ] 2>/dev/null; then fail "R3c 대조 실패 — 지운 직후 AOF 에 원문이 없어 드문 날 · 다시 쓰기를 잴 수 없다"
	elif [ "$quiet_left" = "0" ]; then pass "R3c 드문 날: 지운 방문이 디스크에서 사라졌다"
	elif [ "${#rewriters[@]}" = "1" ]; then pass "R3c 드문 날: 설정만으로는 남는다 — 주기적 다시 쓰기 서비스(${rewriters[0]})가 맡는다(S1 에서 실제로 본다)"
	else fail "R3c 드문 날: 지운 방문이 디스크에 남는데 주기적 다시 쓰기 서비스가 없다(자동 다시 쓰기만으로는 상한을 지키지 못한다)"; fi
	local started=$(date +%s%N)
	redis_in "$main" BGREWRITEAOF >/dev/null; wait_rewrite_done "$main"
	measure "BGREWRITEAOF 한 번으로" "$( [ "$(on_disk "$main" "$quiet_marker")" = 0 ] && echo "사라짐" || echo "남음") · $(( ($(date +%s%N) - started) / 1000000 ))ms"
	[ "$quiet_seen" -ge 1 ] && [ "$(on_disk "$main" "$quiet_marker")" = "0" ] && pass "R3d BGREWRITEAOF 한 번이면 지운 방문이 디스크에서 사라진다(주기적 다시 쓰기가 기대는 동작)" \
		|| fail "R3d BGREWRITEAOF 뒤에도 지운 방문이 디스크에 남는다(지운 항목이 노드에 남는다 — stream-node-max-entries)"

	# --- R5 지금 쓰는 명령 · 헬스체크 ----------------------------------------------------------------------
	local healthcheck
	mapfile -t healthcheck < <(service_field '"\n".join(service["healthcheck"]["test"][1:])')
	docker exec "$main" "${healthcheck[@]}" >/dev/null && pass "R5a compose 의 헬스체크(${healthcheck[*]})가 통과한다" || fail "R5a 헬스체크가 실패한다"
	local command_failures
	command_failures="$( {
		redis_in "$main" XADD logs MAXLEN '~' 10000 '*' level info line 'req-0:backend:GET /api/doc ok'
		redis_in "$main" XGROUP CREATE logs observe-logs 0
		redis_in "$main" XREADGROUP GROUP observe-logs check COUNT 1 STREAMS logs '>'
		redis_in "$main" XPENDING logs observe-logs
		redis_in "$main" XTRIM visits MINID = 1764000000000
		redis_in "$main" SET visits:salt:2026-11-28 fedcba9876543210fedcba9876543210 PX 1000 NX
		redis_in "$main" RPUSH 'chat:00000000-0000-4000-8000-000000000002:cs/b.md' '{"role":"assistant","content":"fake"}'
		redis_in "$main" LRANGE 'chat:00000000-0000-4000-8000-000000000002:cs/b.md' 0 -1
		redis_in "$main" SCAN 0 MATCH 'chat:00000000-0000-4000-8000-000000000002:*' COUNT 100
		redis_in "$main" DEL 'chat:00000000-0000-4000-8000-000000000002:cs/b.md'
	} 2>&1 | grep -c '^ERR\|^WRONGTYPE\|^NOGROUP\|Error' )"
	[ "$command_failures" = "0" ] && pass "R5b 로그 스트림 · 소비자 그룹 · 소금 · 대화 이력의 명령이 그대로 동작한다" || fail "R5b 명령 오류 $command_failures 개"
	local process_user; process_user="$(docker exec "$main" sh -c 'stat -c %U /data/appendonlydir')"
	[ "$process_user" = "redis" ] && pass "R5c AOF 디렉터리의 주인이 redis(서버 사용자)다" || fail "R5c AOF 디렉터리의 주인이 $process_user 다"
	docker rm -f -v "$main" >/dev/null

	# --- R5d 볼륨의 권한 — root 가 가진 빈 볼륨(0700)에서도 뜬다(이미지의 entrypoint 가 주인을 바꾼다) ------------
	docker volume create "$prefix-permission-data" >/dev/null
	docker run --rm -v "$prefix-permission-data:/data" "$image" sh -c 'chown root:root /data && chmod 700 /data' >/dev/null
	if start_redis "$prefix-fresh" "$prefix-permission-data" && redis_in "$prefix-fresh" SET probe 1 >/dev/null && sleep 1.2 \
		&& [ "$(on_disk "$prefix-fresh" probe)" -ge 1 ]; then pass "R5d root 소유 0700 볼륨에서도 뜨고 AOF 를 쓴다"
	else fail "R5d root 소유 볼륨에서 뜨지 못하거나 AOF 를 쓰지 못한다"; fi
	docker rm -f -v "$prefix-fresh" >/dev/null 2>&1

	# --- R5e 덧붙임 파일의 끝이 잘린 AOF(정전)로 뜬다 -----------------------------------------------------
	docker volume create "$prefix-corrupt-data" >/dev/null
	start_redis "$prefix-corrupt" "$prefix-corrupt-data" || { fail "R5e 준비 실패"; return; }
	redis_in "$prefix-corrupt" SET kept-before-cut value-1 >/dev/null
	redis_in "$prefix-corrupt" SET cut-in-half value-2 >/dev/null
	sleep 1.5; docker rm -f "$prefix-corrupt" >/dev/null
	# 마지막 명령의 가운데에서 자른다(정전에 쓰다 만 꼴)
	docker run --rm -v "$prefix-corrupt-data:/data" "$image" sh -c 'f=$(ls /data/appendonlydir/*.incr.aof | tail -1); size=$(stat -c %s "$f"); truncate -s $((size - 12)) "$f"' >/dev/null
	if start_redis "$prefix-corrupt" "$prefix-corrupt-data"; then
		local kept_value; kept_value="$(redis_in "$prefix-corrupt" GET kept-before-cut)"
		local truncated_log; truncated_log="$(docker logs "$prefix-corrupt" 2>&1 | grep -ci 'truncat')"
		measure "끝이 잘린 AOF" "뜸 · 잘리기 전 값 '$kept_value' · 잘린 명령의 값 '$(redis_in "$prefix-corrupt" GET cut-in-half)' · 로그의 truncated 줄 $truncated_log"
		[ "$kept_value" = "value-1" ] && pass "R5e 끝이 잘린 AOF 로도 뜨고(aof-load-truncated) 잘리기 전 값이 남는다" || fail "R5e 끝이 잘린 AOF 로 떴지만 앞의 값이 없다"
	else
		fail "R5e 끝이 잘린 AOF 로 뜨지 못한다"; docker logs "$prefix-corrupt" 2>&1 | tail -3
	fi
	docker rm -f -v "$prefix-corrupt" >/dev/null 2>&1

	# --- R6 다시 쓰기 도중 강제 종료(SIGKILL · 정전 · OOM) — 자식이 쓰던 /data/temp-rewriteaof-<pid>.aof(포크 때의 전체 데이터)가 남는다.
	#     Redis 는 다시 떠도 이것을 지우지 않는다(7.4 실측) — compose 의 명령이 뜰 때 지운다. 지운 방문의 표지가 /data 어디에도 없어야 한다.
	local killed="$prefix-killed" killed_marker="203.0.113.150" temp_seen=0 attempt
	docker volume create "$prefix-killed-data" >/dev/null
	start_redis "$killed" "$prefix-killed-data" || { fail "R6 준비 실패"; return; }
	redis_in "$killed" XGROUP CREATE visits observe '$' MKSTREAM >/dev/null
	redis_in "$killed" XADD visits 1-1 v 1 path /cs/a ip "$killed_marker" >/dev/null
	redis_in "$killed" XADD visits 1-2 v 1 path /cs/b ip 198.51.100.150 >/dev/null   # 아직 넘어가지 않은 방문 — 남아야 한다
	redis_in "$killed" XREADGROUP GROUP observe check COUNT 10 STREAMS visits '>' >/dev/null
	# 다시 쓰기를 느리게(키마다 0.3초 · 30키 = 9초 넘게) — 그 가운데(늦어도 약 7초)에서 죽인다. 자식이 키를 쓰는 차례는 매번 달라(해시 시드)
	# 방문 스트림이 이미 임시 파일에 쓰였는지는 잰 값으로만 남긴다(6초까지 기다린다 — 약 3번에 2번). 대조(판정)는 "죽이기 전에 임시 파일이 있었다".
	docker exec "$killed" sh -c 'p=$(head -c 100000 /dev/zero | tr "\0" y); for i in $(seq 1 30); do redis-cli SET "slow:$i" "$p" >/dev/null; done'
	redis_in "$killed" CONFIG SET rdb-key-save-delay 300000 >/dev/null
	redis_in "$killed" BGREWRITEAOF >/dev/null
	local poll_started; poll_started=$(date +%s%N)
	while [ $(( ($(date +%s%N) - poll_started) / 1000000 )) -lt 6000 ]; do
		temp_seen="$(docker exec "$killed" sh -c 'cat /data/temp-* 2>/dev/null' | grep -a -o -F "$killed_marker" | wc -l)"
		[ "$temp_seen" -ge 1 ] && break
		sleep 0.1
	done
	redis_in "$killed" XACK visits observe 1-1 >/dev/null; redis_in "$killed" XDEL visits 1-1 >/dev/null   # 관측 서버가 DB 에 넣고 지운 꼴(포크 뒤)
	sleep 1.2
	local temp_before; temp_before="$(docker exec "$killed" sh -c 'find /data -name "temp-*" | wc -l')"
	docker kill "$killed" >/dev/null; docker rm "$killed" >/dev/null
	local temp_left=unknown killed_left=unknown kept_left=unknown removed_lines=0
	if start_redis "$killed" "$prefix-killed-data"; then
		temp_left="$(docker exec "$killed" sh -c 'find /data -name "temp-*" | wc -l')"
		removed_lines="$(docker logs "$killed" 2>&1 | grep -c 'redis-start: removed leftover temp file')"
		kept_left="$(redis_in "$killed" XRANGE visits 1-2 1-2 | grep -c '198.51.100.150')"
		redis_in "$killed" BGREWRITEAOF >/dev/null; wait_rewrite_done "$killed"
		killed_left="$(on_disk "$killed" "$killed_marker")"
	fi
	measure "다시 쓰기 도중 SIGKILL" "죽이기 전 /data 의 temp-* $temp_before 개(그 안의 지운 방문의 표지 $temp_seen 번) → 다시 뜬 뒤 temp-* $temp_left 개 · 지웠다는 로그 $removed_lines 줄 · 다시 쓰기 뒤 /data 전체의 표지 $killed_left 번 · 남긴 방문 $kept_left 건"
	if ! [ "$temp_before" -ge 1 ] 2>/dev/null; then fail "R6 대조 실패 — 죽이기 전에 다시 쓰기의 임시 파일이 없어 남는 꼴을 만들지 못했다"
	elif [ "$temp_left" = "0" ] && [ "$killed_left" = "0" ] && [ "$kept_left" = "1" ]; then
		pass "R6 다시 쓰기 도중 강제 종료돼도 다시 뜰 때 임시 파일을 지운다 — 지운 방문이 /data 어디에도 없고 남긴 방문은 그대로다"
	else fail "R6 다시 쓰기 도중 강제 종료 뒤 /data 에 임시 파일 $temp_left 개 · 지운 방문의 표지 $killed_left 번 · 남긴 방문 $kept_left 건"; fi
	docker rm -f -v "$killed" >/dev/null 2>&1; docker volume rm "$prefix-killed-data" >/dev/null 2>&1

	# --- R4 비용(값을 재어 적는다 — 판정하지 않는다) -------------------------------------------------------
	start_redis "$prefix-cost" none || { fail "R4 준비 실패"; return; }
	pipe_into "$prefix-cost" resp_logs logs 10000 200 10000
	pipe_into "$prefix-cost" resp_logs metrics 20000 300 20000
	pipe_into "$prefix-cost" resp_logs traffic 5000 8000 5000
	local cpu_before cpu_after
	cpu_before="$(info_field "$prefix-cost" cpu used_cpu_user_children) $(info_field "$prefix-cost" cpu used_cpu_sys_children)"
	started=$(date +%s%N)
	docker exec -d "$prefix-cost" sh -c 'sleep 0.2; redis-cli BGREWRITEAOF'
	local latency_during latency_idle
	latency_during="$(docker exec "$prefix-cost" redis-benchmark -c 1 -n 5000 --precision 3 XADD probe MAXLEN '~' 100 '*' a b 2>/dev/null | grep -A1 'avg' | tail -1 | tr -s ' ')"
	wait_rewrite_done "$prefix-cost"
	local rewrite_ms=$(( ($(date +%s%N) - started) / 1000000 - 200 ))
	cpu_after="$(info_field "$prefix-cost" cpu used_cpu_user_children) $(info_field "$prefix-cost" cpu used_cpu_sys_children)"
	latency_idle="$(docker exec "$prefix-cost" redis-benchmark -c 1 -n 5000 --precision 3 XADD probe MAXLEN '~' 100 '*' a b 2>/dev/null | grep -A1 'avg' | tail -1 | tr -s ' ')"
	measure "다시 쓰기 한 번(logs 1만 · metrics 2만 · traffic 5천)" "used_memory $(info_field "$prefix-cost" memory used_memory_human) · 약 ${rewrite_ms}ms · 자식 CPU $(python3 -c 'import sys; a=list(map(float,sys.argv[1].split())); b=list(map(float,sys.argv[2].split())); print(round(sum(b)-sum(a),2))' "$cpu_before" "$cpu_after")초 · 바탕 파일 $(info_field "$prefix-cost" persistence aof_base_size) 바이트 · CoW $(info_field "$prefix-cost" persistence aof_last_cow_size) 바이트"
	measure "XADD 지연 ms(avg min p50 p95 p99 max)" "평소 [$latency_idle] · 다시 쓰는 중 [$latency_during]"
	docker rm -f -v "$prefix-cost" >/dev/null

	# --- R4 메모리 — 로그 스트림 5만 항목: 노드당 1 과 기본(100) ---------------------------------------------
	local node name before after
	for node in 100 1; do
		name="$prefix-memory-$( [ "$node" = 1 ] && echo one || echo default)"
		docker run -d --name "$name" "$image" redis-server --save "" --appendonly no --stream-node-max-entries "$node" >/dev/null; sleep 1
		before="$(info_field "$name" memory used_memory)"
		pipe_into "$name" resp_logs logs 50000 150 60000
		after="$(info_field "$name" memory used_memory)"
		measure "로그 5만 항목(줄 150바이트) · stream-node-max-entries $node" "used_memory +$(( (after - before) / 1024 ))KiB · MEMORY USAGE $(( $(redis_in "$name" MEMORY USAGE logs SAMPLES 0) / 1024 ))KiB"
		docker rm -f -v "$name" >/dev/null
	done
}

# ============================================================================================================
# 곁 서비스(주기적 다시 쓰기)를 compose 의 해석된 명령 그대로(주기만 $short_interval 초로) 띄워 본다
container_state() { docker inspect -f '{{.State.Status}} restarts={{.RestartCount}}' "$1" 2>/dev/null || echo "missing"; }
start_rewriter_redis() {   # start_rewriter_redis <docker run 의 /data 마운트 인자…> [-- 덧붙일 redis 인자…] — 같은 네트워크에서 이름 `redis` 로 닿는 Redis(compose 의 명령 그대로)
	local mount=() extra=()
	while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do mount+=("$1"); shift; done
	[ "$#" -gt 0 ] && { shift; extra=("$@"); }
	docker run -d --name "$prefix-rewriter-redis" --network "$prefix-network" --network-alias redis "${mount[@]}" \
		"$image" "${launch_command[@]}" --rdbcompression no "${extra[@]}" >/dev/null || return 1
	wait_ping "$prefix-rewriter-redis"
}
# 곁 서비스를 compose 의 해석된 꼴(주기 $short_interval) 그대로 띄우되, 준 환경 변수만 바꾼다
rewriter_run() {   # rewriter_run <컨테이너> <네트워크> [이름=값 …]
	local name="$1" network="$2"; shift 2
	local arguments=(-d --name "$name" --network "$network" --restart "$restart_policy") variable given skip
	for variable in "${rewriter_environment[@]}"; do
		[ -n "$variable" ] || continue
		skip=no; for given in "$@"; do [ "${given%%=*}" = "${variable%%=*}" ] && skip=yes; done
		[ "$skip" = no ] && arguments+=(-e "$variable")
	done
	for given in "$@"; do arguments+=(-e "$given"); done
	if [ "${#rewriter_entrypoint[@]}" -gt 0 ] && [ -n "${rewriter_entrypoint[0]}" ]; then
		arguments+=(--entrypoint "${rewriter_entrypoint[0]}" "$rewriter_image" "${rewriter_entrypoint[@]:1}")
	else
		arguments+=("$rewriter_image")
	fi
	docker run "${arguments[@]}" "${rewriter_command[@]}" >/dev/null
}
failed_lines() { docker logs "$1" 2>&1 | grep -c "redis-aof-rewrite: FAILED $2"; }   # failed_lines <곁 컨테이너> <종류>
wait_failed_line() {   # wait_failed_line <곁 컨테이너> <종류> <이 수보다 커질 때까지> <최대 초>
	local started; started=$(date +%s)
	while [ $(( $(date +%s) - started )) -lt "$4" ]; do
		[ "$(failed_lines "$1" "$2")" -gt "$3" ] && return 0
		sleep 0.5
	done
	return 1
}
wait_rewrites_above() {   # wait_rewrites_above <이 수보다 커질 때까지> <최대 초> → 걸린 초(못 넘으면 실패)
	local started=$(date +%s%N) current
	while [ $(( ($(date +%s%N) - started) / 1000000000 )) -lt "$2" ]; do
		current="$(rewrites_of "$prefix-rewriter-redis" 2>/dev/null)"
		if [[ "$current" =~ ^[0-9]+$ ]] && [ "$current" -gt "$1" ]; then echo $(( ($(date +%s%N) - started) / 1000000 )); return 0; fi
		sleep 0.2
	done
	return 1
}
rewriter_checks() {
	echo "== 곁 서비스(주기적 다시 쓰기) — 주기 \$$interval_variable=$short_interval"
	if [ "${#rewriters[@]}" != "1" ]; then fail "S0 곁 서비스가 정확히 하나가 아니라 실행 검사를 할 수 없다(${#rewriters[@]} 개)"; return; fi
	local rewriter_command rewriter_entrypoint rewriter_environment restart_policy rewriter_image
	mapfile -d '' -t rewriter_command < <(list_field "$short_compose_json" "${rewriters[0]}" command)
	mapfile -d '' -t rewriter_entrypoint < <(list_field "$short_compose_json" "${rewriters[0]}" entrypoint)
	mapfile -t rewriter_environment < <(rewriter_field "$short_compose_json" '"\n".join(k+"="+(v or "").replace("$$", "$") for k,v in (service.get("environment") or {}).items())')
	restart_policy="$(rewriter_field "$short_compose_json" 'service.get("restart") or "no"')"
	rewriter_image="$(rewriter_field "$short_compose_json" 'service["image"]')"
	docker network create "$prefix-network" >/dev/null
	docker volume create "$prefix-rewriter-data" >/dev/null

	# S2 Redis 가 아직 없을 때 띄운다 — 세 주기 동안 죽지 않는다(짧은 주기를 받게 바닥만 1 로)
	rewriter_run "$prefix-rewriter" "$prefix-network" "$floor_variable=1" || { fail "S2 곁 서비스가 뜨지 않았다"; return; }
	sleep $((short_interval * 3 + 1))
	local state; state="$(container_state "$prefix-rewriter")"
	measure "Redis 없이 $((short_interval * 3 + 1))초 뒤의 곁 컨테이너" "$state"
	[ "$state" = "running restarts=0" ] && pass "S2a Redis 가 없어도 곁 컨테이너가 죽지 않는다($state)" || fail "S2a Redis 가 없을 때 곁 컨테이너가 죽거나 다시 떴다($state)"

	# Redis 를 띄운 뒤의 주기에 다시 쓴다 + S1 드문 날 — 다른 쓰기 없이 한 주기(+ 여유) 안에 사라진다
	start_rewriter_redis -v "$prefix-rewriter-data:/data" || { fail "S1 Redis 가 뜨지 않았다"; return; }
	local quiet_redis="$prefix-rewriter-redis" marker="203.0.113.123"
	redis_in "$quiet_redis" XGROUP CREATE visits observe '$' MKSTREAM >/dev/null
	local before_id after_id marker_id
	before_id="$(redis_in "$quiet_redis" XADD visits '*' v 1 path /cs/b ip 198.51.100.121)"
	marker_id="$(redis_in "$quiet_redis" XADD visits '*' v 1 path /cs/a ip "$marker")"
	local seen; seen="$(on_disk "$quiet_redis" "$marker")"   # 대조 — 넣은 직후 AOF(덧붙임)에 원문이 있다(곁 서비스의 다음 회차 전에 본다)
	after_id="$(redis_in "$quiet_redis" XADD visits '*' v 1 path /cs/b ip 198.51.100.122)"
	redis_in "$quiet_redis" XREADGROUP GROUP observe check COUNT 10 STREAMS visits '>' >/dev/null
	local rewrites_before; rewrites_before="$(rewrites_of "$quiet_redis")"
	# 가장 늦는 꼴 — 곁 서비스의 다시 쓰기가 끝난 바로 뒤에 지운다(그 다시 쓰기는 지우기 전의 데이터를 썼다: 다음 회차까지 남는다)
	local phase_ms; phase_ms="$(wait_rewrites_above "$rewrites_before" $((short_interval * 2 + 3)))" || phase_ms="missed"
	redis_in "$quiet_redis" XACK visits observe "$marker_id" >/dev/null; redis_in "$quiet_redis" XDEL visits "$marker_id" >/dev/null
	local deleted_at=$(date +%s%N) limit_ms=$((short_interval * 1000 + s1_slack_ms))
	while [ "$(on_disk "$quiet_redis" "$marker")" != "0" ] && [ $(( ($(date +%s%N) - deleted_at) / 1000000 )) -lt $((short_interval * 3000 + 5000)) ]; do sleep 0.2; done
	local vanished_ms=$(( ($(date +%s%N) - deleted_at) / 1000000 )) left; left="$(on_disk "$quiet_redis" "$marker")"
	local rewrites_after; rewrites_after="$(rewrites_of "$quiet_redis")"
	measure "드문 날(다른 쓰기 없음) · 주기 ${short_interval}초" "넣은 직후 AOF 에 $seen 번 → 다시 쓰기가 끝난 바로 뒤(${phase_ms}ms 기다림)에 지움 → $( [ "$left" = 0 ] && echo "${vanished_ms}ms 뒤 사라짐(한 주기 위로 $((vanished_ms - short_interval * 1000))ms)" || echo "$((vanished_ms / 1000))초 뒤에도 남음") · 그동안의 다시 쓰기 $((rewrites_after - rewrites_before)) 번"
	if ! [ "$seen" -ge 1 ] 2>/dev/null; then fail "S1 대조 실패 — 넣은 직후 AOF 에 원문이 없다"
	elif [ "$phase_ms" = "missed" ]; then fail "S1 대조 실패 — 지우기 전에 곁 서비스의 다시 쓰기가 오지 않아 가장 늦는 꼴을 만들지 못했다"
	elif [ "$left" = "0" ] && [ "$vanished_ms" -le "$limit_ms" ]; then pass "S1 드문 날: 다른 쓰기 없이 지운 방문이 한 주기 + 여유(${limit_ms}ms) 안에(${vanished_ms}ms) /data 에서 사라진다"
	else fail "S1 드문 날: 지운 방문이 한 주기 + 여유(${limit_ms}ms) 안에 /data 에서 사라지지 않는다(${vanished_ms}ms · 남은 수 $left)"; fi
	[ $((rewrites_after - rewrites_before)) -ge 1 ] && pass "S2b Redis 가 뜬 뒤의 주기에 다시 쓴다(다시 쓰기 $((rewrites_after - rewrites_before)) 번)" \
		|| fail "S2b Redis 가 뜬 뒤에도 곁 서비스가 다시 쓰기를 시키지 않는다"
	redis_in "$quiet_redis" XDEL visits "$before_id" "$after_id" >/dev/null
	# S2c 다시 쓰기가 성공하는 동안에는 실패의 줄이 없다(실패의 줄이 늘 찍히면 운영자가 그 줄을 믿지 않는다)
	local false_alarms; false_alarms="$(failed_lines "$prefix-rewriter" "")"
	[ "$false_alarms" = "0" ] && pass "S2c 다시 쓰기가 성공하는 동안 곁 서비스는 'redis-aof-rewrite: FAILED' 줄을 남기지 않는다" \
		|| fail "S2c 다시 쓰기가 성공하는데 곁 서비스가 'redis-aof-rewrite: FAILED' 줄을 $false_alarms 번 남겼다"

	# S3 다시 쓰기가 이미 도는 중에 시켜도 죽지 않는다 — 다시 쓰기를 일부러 느리게(키마다 1초) 해 세 주기 넘게 걸치게 한다
	local key; for key in $(seq 1 $((short_interval * 4))); do redis_in "$quiet_redis" SET "slow:$key" value >/dev/null; done
	redis_in "$quiet_redis" CONFIG SET rdb-key-save-delay 1000000 >/dev/null
	wait_rewrite_done "$quiet_redis"
	redis_in "$quiet_redis" BGREWRITEAOF >/dev/null
	local in_progress_started=$(date +%s)
	sleep $((short_interval * 3))
	local still_running; still_running="$(info_field "$quiet_redis" persistence aof_rewrite_in_progress)"
	redis_in "$quiet_redis" CONFIG SET rdb-key-save-delay 0 >/dev/null
	wait_rewrite_done "$quiet_redis"
	local collision_lines; collision_lines="$(docker logs "$prefix-rewriter" 2>&1 | grep -ci 'already in progress')"
	measure "다시 쓰기가 도는 중의 시킴" "느린 다시 쓰기가 $((short_interval * 3))초 넘게 걸침(그 시점 진행 중=$still_running) · 곁 컨테이너 로그의 'already in progress' 줄 $collision_lines"
	if [ "$still_running" != "1" ]; then fail "S3 대조 실패 — 다시 쓰기가 세 주기 동안 이어지지 않아 겹침을 만들지 못했다"
	else
		state="$(container_state "$prefix-rewriter")"
		local rewrites_now; rewrites_now="$(rewrites_of "$quiet_redis")"
		if [ "$state" = "running restarts=0" ] && wait_rewrites_above "$rewrites_now" $((short_interval * 3)) >/dev/null; then
			pass "S3 다시 쓰기가 도는 중에 시켜도(already in progress) 죽지 않고 다음 주기에 다시 쓴다($state)"
		else fail "S3 다시 쓰기가 도는 중에 시킨 뒤 곁 컨테이너가 죽었거나 더 다시 쓰지 않는다($state)"; fi
	fi

	# S4 Redis 를 지웠다 다시 만들어도 이어서 돈다
	docker rm -f "$prefix-rewriter-redis" >/dev/null
	sleep $((short_interval * 2))
	start_rewriter_redis -v "$prefix-rewriter-data:/data" || { fail "S4 Redis 를 다시 만들지 못했다"; return; }
	local recreated_ms
	if recreated_ms="$(wait_rewrites_above 0 $((short_interval * 3)))"; then
		state="$(container_state "$prefix-rewriter")"
		[ "$state" = "running restarts=0" ] && pass "S4 Redis 를 다시 만든 뒤에도 이어서 다시 쓰기를 시킨다(${recreated_ms}ms 뒤 · $state)" \
			|| fail "S4 Redis 를 다시 만든 사이에 곁 컨테이너가 죽었다가 다시 떴다($state)"
	else fail "S4 Redis 를 다시 만든 뒤 곁 서비스가 다시 쓰기를 시키지 않는다($(container_state "$prefix-rewriter"))"; fi

	# S6 곁 서비스가 시킨 다시 쓰기가 한 주기 안에 끝나지 않으면 드러나는 한 줄(rewrite-timeout)을 남긴다 — 키마다 0.5초로 느리게
	local timeout_before; timeout_before="$(failed_lines "$prefix-rewriter" rewrite-timeout)"
	wait_rewrite_done "$prefix-rewriter-redis"
	redis_in "$prefix-rewriter-redis" CONFIG SET rdb-key-save-delay 500000 >/dev/null
	local slow_started=no attempt
	for attempt in $(seq 1 $(( (short_interval * 2 + 3) * 5 ))); do
		[ "$(info_field "$prefix-rewriter-redis" persistence aof_rewrite_in_progress)" = "1" ] && { slow_started=yes; break; }
		sleep 0.2
	done
	local timeout_seen=no; [ "$slow_started" = yes ] && wait_failed_line "$prefix-rewriter" rewrite-timeout "$timeout_before" $((short_interval + 4)) && timeout_seen=yes
	redis_in "$prefix-rewriter-redis" CONFIG SET rdb-key-save-delay 0 >/dev/null
	wait_rewrite_done "$prefix-rewriter-redis"
	measure "곁 서비스가 시킨 느린 다시 쓰기" "시작됨=$slow_started · 'FAILED rewrite-timeout' 줄 $timeout_before → $(failed_lines "$prefix-rewriter" rewrite-timeout)"
	if [ "$slow_started" != yes ]; then fail "S6 대조 실패 — 곁 서비스가 느린 다시 쓰기를 시작하지 않았다"
	elif [ "$timeout_seen" = yes ]; then pass "S6 다시 쓰기가 한 주기 안에 끝나지 않으면 곁 서비스가 'redis-aof-rewrite: FAILED rewrite-timeout' 줄을 남긴다"
	else fail "S6 다시 쓰기가 한 주기 넘게 끝나지 않는데 곁 서비스의 로그에 드러나는 줄이 없다"; fi

	# S10 곁 서비스가 시킨 다시 쓰기(A)가 끝난 바로 뒤에 다른 다시 쓰기(B — 자동 · 다른 클라이언트)가 시작돼 한 주기보다 오래 돌아도,
	#     성공한 A 를 실패(rewrite-timeout)로 적지 않는다. A 는 짧게(키마다 20ms) · A 가 fork 된 뒤에 느리게 바꾸면(키마다 0.5초) B 만 느리다
	#     (자식은 fork 때의 설정을 쓴다). B 는 Redis 안에서 A 가 끝나자마자 시킨다 — 곁 서비스의 1초 조회보다 먼저.
	local overlap="$prefix-rewriter-redis" overlap_lines_before overlap_reply overlap_new=no
	wait_rewrite_done "$overlap"
	overlap_lines_before="$(failed_lines "$prefix-rewriter" "")"
	redis_in "$overlap" CONFIG SET rdb-key-save-delay 20000 >/dev/null
	overlap_reply="$(docker exec "$overlap" sh -c '
		field() { redis-cli INFO persistence | tr -d "\r" | sed -n "s/^$1://p"; }
		base="$(field aof_rewrites)"; n=0
		while [ "$(field aof_rewrites)" = "$base" ] && [ "$n" -lt 1500 ]; do n=$((n + 1)); sleep 0.01; done
		[ "$(field aof_rewrites)" = "$base" ] && { echo "A-not-started"; exit 0; }
		redis-cli CONFIG SET rdb-key-save-delay 500000 >/dev/null
		while [ "$(field aof_rewrite_in_progress)" = 1 ]; do :; done
		redis-cli BGREWRITEAOF
		redis-cli CONFIG SET rdb-key-save-delay 0 >/dev/null' 2>&1 | tr -d '\r' | head -1)"
	# B(약 키 수 × 0.5초)가 끝나고 곁 서비스가 한 회차 더 돌 때까지 본다 — 뒤의 fork 는 다시 빠르다(위에서 되돌렸다)
	wait_failed_line "$prefix-rewriter" "" "$overlap_lines_before" $((short_interval * 3 + 4)) && overlap_new=yes
	redis_in "$overlap" CONFIG SET rdb-key-save-delay 0 >/dev/null   # A 가 시작되지 않아 위에서 되돌리지 못한 때
	wait_rewrite_done "$overlap"
	local overlap_status; overlap_status="$(info_field "$overlap" persistence aof_last_bgrewrite_status)"
	measure "곁 서비스의 다시 쓰기 바로 뒤의 다른 다시 쓰기" "B 를 시킨 응답 '$overlap_reply' · 끝난 뒤 aof_last_bgrewrite_status=$overlap_status · FAILED 줄 $overlap_lines_before → $(failed_lines "$prefix-rewriter" "")"
	if [[ "$overlap_reply" != *started* ]] || [ "$overlap_status" != ok ]; then
		fail "S10 대조 실패 — A 가 끝난 바로 뒤에 B 를 시작하지 못했거나 다시 쓰기가 실패했다(응답 '$overlap_reply' · 상태 $overlap_status)"
	elif [ "$overlap_new" = no ]; then pass "S10 시킨 다시 쓰기가 끝난 바로 뒤 다른 다시 쓰기가 한 주기 넘게 돌아도 곁 서비스는 FAILED 줄을 남기지 않는다"
	else fail "S10 시킨 다시 쓰기는 성공했는데 곁 서비스가 FAILED 줄을 남겼다: $(docker logs "$prefix-rewriter" 2>&1 | grep 'redis-aof-rewrite: FAILED' | tail -1)"; fi

	# S7 다시 쓰기가 실패하면(디스크 가득) 곁 서비스가 드러나는 한 줄(rewrite-error)을 남긴다 — 2.6MB tmpfs 를 데이터로 반쯤 채운다
	#     (자동 다시 쓰기는 끈다 — 곁 서비스가 시킨 다시 쓰기의 결과를 본다)
	docker rm -f -v "$prefix-rewriter-redis" >/dev/null
	local error_before; error_before="$(failed_lines "$prefix-rewriter" rewrite-error)"
	if start_rewriter_redis --mount type=tmpfs,destination=/data,tmpfs-size=2600k -- --auto-aof-rewrite-percentage 0; then
		docker exec "$prefix-rewriter-redis" sh -c 'for i in $(seq 1 14); do redis-cli SET "fill:$i" "$(head -c 75000 /dev/urandom | base64 | tr -d "\n")" >/dev/null; done'
		local error_seen=no; wait_failed_line "$prefix-rewriter" rewrite-error "$error_before" $((short_interval * 3 + 5)) && error_seen=yes
		local rewrite_status; rewrite_status="$(info_field "$prefix-rewriter-redis" persistence aof_last_bgrewrite_status)"
		measure "디스크가 가득 차 다시 쓰기가 실패할 때" "Redis 의 aof_last_bgrewrite_status=$rewrite_status · 곁 서비스의 'FAILED rewrite-error' 줄 $error_before → $(failed_lines "$prefix-rewriter" rewrite-error): $(docker logs "$prefix-rewriter" 2>&1 | grep -m1 'FAILED rewrite-error')"
		if [ "$rewrite_status" != "err" ]; then fail "S7 대조 실패 — 다시 쓰기가 실패하지 않았다(aof_last_bgrewrite_status=$rewrite_status)"
		elif [ "$error_seen" = yes ]; then pass "S7 다시 쓰기가 실패하면 곁 서비스가 'redis-aof-rewrite: FAILED rewrite-error' 줄을 남긴다"
		else fail "S7 다시 쓰기가 실패했는데(err) 곁 서비스의 로그에 드러나는 줄이 없다"; fi
		# S7b 디스크를 완전히 채우면 Redis 는 BGREWRITEAOF 를 시작하지 않고 곧바로 거절한다(ERR Can't execute …) — 그 응답도 한 줄(FAILED rewrite-error)로 남긴다
		docker exec "$prefix-rewriter-redis" sh -c 'dd if=/dev/zero of=/data/fill bs=4k 2>/dev/null; true'
		local refused_seen=no refused_line="" full_started; full_started=$(date +%s)
		while [ $(( $(date +%s) - full_started )) -lt $((short_interval * 3 + 5)) ]; do
			docker logs "$prefix-rewriter" 2>&1 | grep -q "^ERR Can't execute" && refused_seen=yes
			refused_line="$(docker logs "$prefix-rewriter" 2>&1 | grep -m1 "redis-aof-rewrite: FAILED rewrite-error .*Can't execute")"
			[ -n "$refused_line" ] && break
			sleep 0.5
		done
		sleep 0.5; docker logs "$prefix-rewriter" 2>&1 | grep -q "^ERR Can't execute" && refused_seen=yes   # 표준 출력의 줄이 오류의 줄보다 늦게 실릴 수 있다
		measure "디스크가 완전히 차 다시 쓰기를 시작하지 못할 때" "Redis 의 거절 '$( [ "$refused_seen" = yes ] && echo 보임 || echo 안 보임)' · 곁 서비스의 줄: ${refused_line:-없음}"
		if [ -n "$refused_line" ]; then pass "S7b 디스크가 완전히 차 Redis 가 다시 쓰기를 곧바로 거절하면 곁 서비스가 그 응답을 'redis-aof-rewrite: FAILED rewrite-error' 한 줄로 남긴다"
		elif [ "$refused_seen" != yes ]; then fail "S7b 대조 실패 — 디스크를 채워도 Redis 가 다시 쓰기를 거절하지 않았다"
		else fail "S7b Redis 가 다시 쓰기를 거절했는데(ERR Can't execute) 곁 서비스의 로그에 드러나는 줄이 없다"; fi
	else fail "S7 준비 실패 — tmpfs 의 Redis 가 뜨지 않았다"; fi
	docker rm -f -v "$prefix-rewriter-redis" >/dev/null 2>&1

	# S5 곁 컨테이너의 로그 — 방문의 원문 · 비밀값이 없다
	local rewriter_log; rewriter_log="$(docker logs "$prefix-rewriter" 2>&1)"
	local leaked; leaked="$(grep -cE '203\.0\.113\.|198\.51\.100\.|fake-password|fake-sync' <<<"$rewriter_log")"
	measure "곁 컨테이너의 로그" "$(wc -l <<<"$rewriter_log") 줄 · 종류: $(sort <<<"$rewriter_log" | uniq -c | sort -rn | head -4 | tr -s ' ' | tr '\n' ';')"
	[ "$leaked" = "0" ] && pass "S5 곁 컨테이너의 로그에 방문의 원문(주소) · 비밀값이 없다" || fail "S5 곁 컨테이너의 로그에 주소 · 비밀값이 $leaked 줄 실렸다"

	# S9 곁 서비스는 docker stop(SIGTERM)에 바로 끝난다 — 기다리는 10초 뒤의 SIGKILL 까지 가지 않는다
	local stop_started stop_ms stop_code
	stop_started=$(date +%s%N); docker stop -t 10 "$prefix-rewriter" >/dev/null; stop_ms=$(( ($(date +%s%N) - stop_started) / 1000000 ))
	stop_code="$(docker inspect -f '{{.State.ExitCode}}' "$prefix-rewriter")"
	measure "곁 서비스의 docker stop" "${stop_ms}ms · 종료 코드 $stop_code"
	[ "$stop_ms" -lt 3000 ] && [ "$stop_code" = "0" ] && pass "S9 곁 서비스가 SIGTERM 에 바로 끝난다(${stop_ms}ms · 종료 코드 0)" \
		|| fail "S9 곁 서비스가 SIGTERM 에 바로 끝나지 않는다(${stop_ms}ms · 종료 코드 $stop_code — 137 이면 SIGKILL)"
	docker rm -f -v "$prefix-rewriter" "$prefix-rewriter-redis" >/dev/null 2>&1

	# S9b Redis 에 닿지 못해 redis-cli 가 매달린 동안에도(이름 풀이의 시간 초과 — 이 호스트에서 약 20초) SIGTERM 에 바로 끝난다.
	#     지금 이 네트워크에는 이름 `redis` 가 없다
	local hang="$prefix-stop" hang_seen=no hang_attempt
	rewriter_run "$hang" "$prefix-network" "$floor_variable=1" "$interval_variable=1" || fail "S9b 준비 실패"
	for hang_attempt in $(seq 1 80); do
		docker exec "$hang" ps -o args 2>/dev/null | grep -q '^redis-cli' && { hang_seen=yes; break; }
		sleep 0.1
	done
	sleep 1
	local hang_still; hang_still="$(docker exec "$hang" ps -o args 2>/dev/null | grep -c '^redis-cli')"
	stop_started=$(date +%s%N); docker stop -t 10 "$hang" >/dev/null; stop_ms=$(( ($(date +%s%N) - stop_started) / 1000000 ))
	stop_code="$(docker inspect -f '{{.State.ExitCode}}' "$hang")"
	measure "Redis 에 닿지 못하는 동안의 docker stop" "redis-cli 가 1초 넘게 매달림=$( [ "$hang_seen" = yes ] && [ "$hang_still" -ge 1 ] && echo yes || echo no) · ${stop_ms}ms · 종료 코드 $stop_code · 로그: $(docker logs "$hang" 2>&1 | head -2 | tr '\n' ';')"
	if [ "$hang_seen" != yes ] || [ "$hang_still" -lt 1 ]; then fail "S9b 대조 실패 — Redis 에 닿지 못하는 redis-cli 가 매달린 꼴을 만들지 못했다(이 환경의 이름 풀이가 빨리 실패한다)"
	elif [ "$stop_ms" -lt 3000 ] && [ "$stop_code" = "0" ]; then pass "S9b Redis 에 닿지 못해 redis-cli 가 매달린 동안에도 SIGTERM 에 바로 끝난다(${stop_ms}ms · 종료 코드 0)"
	else fail "S9b Redis 에 닿지 못해 redis-cli 가 매달린 동안 SIGTERM 에 바로 끝나지 않는다(${stop_ms}ms · 종료 코드 $stop_code — 137 이면 SIGKILL)"; fi
	docker rm -f -v "$hang" >/dev/null 2>&1

	# S8 주기의 값 — compose 의 바닥(60) 그대로: 60 이상의 정수가 아니면 드러나는 한 줄(BAD-INTERVAL)을 남기고 1800 으로 돈다(조용히 다른 주기로 돌지 않는다)
	#     네트워크 없이 띄운다 — 1800 초를 쉬는 동안에는 Redis 에 닿지 않는다(닿으려 하면 그 줄이 로그에 남는다)
	local values=("0" "abc" "-5" "30" "" "1800") number value
	for number in 1 2 3 4 5 6; do rewriter_run "$prefix-interval-$number" none "$interval_variable=${values[$((number - 1))]}" || fail "S8 준비 실패($number)"; done
	sleep 2
	for number in 1 2 3 4 5 6; do
		value="${values[$((number - 1))]}"
		local interval_log bad_lines other_lines sleeping
		interval_log="$(docker logs "$prefix-interval-$number" 2>&1)"
		bad_lines="$(grep -c 'redis-aof-rewrite: BAD-INTERVAL' <<<"$interval_log")"
		other_lines="$(grep -v 'redis-aof-rewrite: BAD-INTERVAL' <<<"$interval_log" | grep -c .)"
		sleeping="$(docker exec "$prefix-interval-$number" ps -o args 2>/dev/null | grep -cx 'sleep 1800')"
		local expected_bad=1; [ "$value" = "1800" ] && expected_bad=0
		measure "주기 '$value'" "BAD-INTERVAL 줄 $bad_lines · 다른 줄 $other_lines · 'sleep 1800' 프로세스 $sleeping $( [ "$bad_lines" -gt 0 ] && grep -m1 BAD-INTERVAL <<<"$interval_log")"
		if [ "$bad_lines" = "$expected_bad" ] && [ "$other_lines" = "0" ] && [ "$sleeping" = "1" ]; then
			pass "S8 주기 '$value': $( [ "$expected_bad" = 1 ] && echo "드러나는 한 줄을 남기고 " )1800초로 돈다"
		else fail "S8 주기 '$value': BAD-INTERVAL 줄 $bad_lines(기대 $expected_bad) · 다른 줄 $other_lines · 'sleep 1800' $sleeping — 조용히 다른 주기로 돈다"; fi
		docker rm -f -v "$prefix-interval-$number" >/dev/null 2>&1
	done
}

case "$mode" in
	text) text_checks ;;
	run) trap cleanup EXIT; run_checks; rewriter_checks ;;
	all) text_checks; trap cleanup EXIT; run_checks; rewriter_checks ;;
	*) echo "쓰임: $0 [text|run|all]" >&2; exit 2 ;;
esac

if [ "${#measurements[@]}" -gt 0 ]; then
	echo "== 잰 값"
	for entry in "${measurements[@]}"; do echo "  ${entry%%|*}: ${entry#*|}"; done
fi
echo "== 실패 $failures"
[ "$failures" = 0 ]
