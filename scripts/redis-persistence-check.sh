#!/usr/bin/env bash
# Redis 의 디스크 저장(AOF)을 실제 redis 컨테이너로 확인한다 — docker-compose.yml 의 `redis` 서비스가 가진 실행 인자 · 볼륨을 그대로 읽어 띄운다
# (설정을 이 스크립트에 옮겨 적지 않는다: compose 를 고치면 이 검사가 그 값을 본다).
#
#   scripts/redis-persistence-check.sh text   # 설정 파일의 글자만(컨테이너를 띄우지 않는다 · 몇 초)
#   scripts/redis-persistence-check.sh run    # 실제 컨테이너로(몇 분)
#   scripts/redis-persistence-check.sh        # 둘 다
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

# --- compose 의 redis 서비스(해석된 꼴) -------------------------------------------------------------------------
compose_json="$(cd /tmp && env -i PATH="$PATH" HOME="$HOME" \
	BIND_ADDR=192.0.2.10 LLM_URL=http://192.0.2.11:8000 SYNC_SECRET=fake-sync STUDY_DB_NAME=fake STUDY_DB_USER=fake STUDY_DB_PASSWORD=fake-password \
	docker compose --env-file /dev/null -p backend-redis-check -f "$compose_file" config --format json redis)" || {
	echo "FAIL  docker compose config 가 compose 파일을 읽지 못했다" >&2; exit 1; }

service_field() {   # service_field <python 식 — service 는 redis 서비스, document 는 전체>
	python3 -c 'import json,sys; document=json.load(sys.stdin); service=document["services"]["redis"]; print(eval(sys.argv[1]))' "$1" <<<"$compose_json"
}
# 실행 인자 — 한 줄에 하나(빈 값 "" 도 빈 줄로 남는다)
mapfile -t redis_command < <(service_field '"\n".join(service.get("command") or [])')
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
}

# ============================================================================================================
cleanup() {
	# 이 실행이 띄운 것만 — 정확한 이름으로
	local name
	for name in "$prefix-redis" "$prefix-fresh" "$prefix-cost" "$prefix-memory-default" "$prefix-memory-one" "$prefix-quiet" "$prefix-corrupt"; do
		docker rm -f -v "$name" >/dev/null 2>&1 || true
	done
	for name in "$data_volume" "$prefix-quiet-data" "$prefix-corrupt-data" "$prefix-permission-data"; do
		docker volume rm "$name" >/dev/null 2>&1 || true
	done
}

# 지운 항목의 글자를 바이트로 찾으려고 `--rdbcompression no` 만 덧붙인다 — 압축(LZF)은 바탕 파일 · DUMP 의 바이트 꼴만 바꾸고 어떤 항목이 남는지는
# 바꾸지 않는다(압축하면 같은 꼴이 반복되는 주소가 뒤 참조로 바뀌어 grep 이 놓친다 — 실측).
start_redis() {   # start_redis <컨테이너> <볼륨|none> [덧붙일 인자…]
	local name="$1" volume="$2"; shift 2
	local mount=()
	[ "$volume" != "none" ] && mount=(-v "$volume:/data")
	docker run -d --name "$name" "${mount[@]}" "$image" "${redis_command[@]}" --rdbcompression no "$@" >/dev/null || return 1
	local attempt
	for attempt in $(seq 1 50); do
		[ "$(docker exec "$name" redis-cli ping 2>/dev/null)" = "PONG" ] && return 0
		sleep 0.2
	done
	return 1
}
redis_in() { local name="$1"; shift; docker exec "$name" redis-cli "$@"; }
pipe_into() {   # 명령 묶음을 넣는다 — 오류가 하나라도 있으면 실패로 센다(조용히 넘기지 않는다)
	local summary; summary="$(docker exec -i "$1" redis-cli --pipe 2>&1 | tail -1)"
	[[ "$summary" == "errors: 0,"* ]] || fail "명령 묶음에 오류가 있었다($1): $summary"
}
on_disk() {   # on_disk <컨테이너> <글자> → appendonlydir 의 모든 파일(바탕 · 덧붙임)에서 그 글자의 수
	docker exec "$1" sh -c 'cat /data/appendonlydir/* 2>/dev/null' | grep -a -o -F "$2" | wc -l
}
rewrites_of() { redis_in "$1" INFO persistence | tr -d '\r' | awk -F: '/^aof_rewrites:/{print $2}'; }
info_field() { redis_in "$1" INFO "$2" | tr -d '\r' | awk -F: -v key="$3" '$1==key{print $2}'; }
wait_rewrite_done() {
	local attempt
	for attempt in $(seq 1 600); do
		[ "$(info_field "$1" persistence aof_rewrite_in_progress)" = "0" ] && [ "$(info_field "$1" persistence aof_rewrite_scheduled)" = "0" ] && return 0
		sleep 0.05
	done
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
	resp_logs logs 10000 200 10000 | pipe_into "$main"
	redis_in "$main" BGREWRITEAOF >/dev/null; wait_rewrite_done "$main"
	# 방문 한 건이 AOF 를 키우는 양(100건으로 잰다 · 다시 쓰기의 문턱보다 훨씬 작다)
	local size_before size_after visit_bytes
	sleep 1.2; size_before="$(info_field "$main" persistence aof_current_size)"
	resp_visits 7000000000000 1 100 192.0.2. | pipe_into "$main"
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
		resp_visits 8000000000001 $((minutes * 1000)) 300 203.0.113. | pipe_into "$main"
		sleep 1.2; wait_rewrite_done "$main"
		[ "$(on_disk "$main" "$busy_marker")" = "0" ] && break
	done
	local busy_left; busy_left="$(on_disk "$main" "$busy_marker")"
	measure "AOF 가 커지는 양" "방문 한 건(넣기·읽기·확인·지우기) $visit_bytes 바이트 · 바탕 $base_size 바이트 · 문턱 percentage=$threshold_percentage min-size=$minimum_size"
	measure "잦은 날(분당 300)" "$( [ "$busy_left" = 0 ] && echo "${minutes}분 몫 뒤 디스크에서 사라짐" || echo "60분 몫 뒤에도 디스크에 남음") · 자동 다시 쓰기 $(( $(rewrites_of "$main") - rewrites_before )) 번"
	if [ "$busy_seen" -lt 1 ]; then fail "R3b 대조 실패 — 지운 직후 AOF 에 원문이 없어 사라지는 시각을 잴 수 없다"
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
	resp_visits 8000000000003 1 30 198.51.100. | pipe_into "$main"
	sleep 1.5; wait_rewrite_done "$main"
	local quiet_left needed_bytes
	quiet_left="$(on_disk "$main" "$quiet_marker")"
	base_size="$(info_field "$main" persistence aof_base_size)"
	needed_bytes=$(( base_size * threshold_percentage / 100 ))
	[ "$needed_bytes" -lt $((minimum_size - base_size)) ] && needed_bytes=$((minimum_size - base_size))
	measure "드문 날(하루 30건)" "$( [ "$quiet_left" = 0 ] && echo "사라짐" || echo "하루 몫 뒤에도 디스크에 남음") · 다음 자동 다시 쓰기까지 더 쌓여야 할 양 ≈ $needed_bytes 바이트(방문만이면 약 $(( needed_bytes / visit_bytes )) 건 — 로그 · 지표 쓰기가 함께 채운다)"
	if [ "$quiet_seen" -lt 1 ]; then fail "R3c 대조 실패 — 지운 직후 AOF 에 원문이 없어 드문 날 · 다시 쓰기를 잴 수 없다"
	elif [ "$quiet_left" = "0" ]; then pass "R3c 드문 날: 지운 방문이 디스크에서 사라졌다"
	else warn "R3c 드문 날: 설정만으로는 지운 방문이 디스크에 남는다 — 1시간 상한은 주기적 다시 쓰기(BGREWRITEAOF)가 맡아야 한다(메인이 정할 것)"; fi
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

	# --- R4 비용(값을 재어 적는다 — 판정하지 않는다) -------------------------------------------------------
	start_redis "$prefix-cost" none || { fail "R4 준비 실패"; return; }
	resp_logs logs 10000 200 10000 | pipe_into "$prefix-cost"
	resp_logs metrics 20000 300 20000 | pipe_into "$prefix-cost"
	resp_logs traffic 5000 8000 5000 | pipe_into "$prefix-cost"
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
		resp_logs logs 50000 150 60000 | pipe_into "$name"
		after="$(info_field "$name" memory used_memory)"
		measure "로그 5만 항목(줄 150바이트) · stream-node-max-entries $node" "used_memory +$(( (after - before) / 1024 ))KiB · MEMORY USAGE $(( $(redis_in "$name" MEMORY USAGE logs SAMPLES 0) / 1024 ))KiB"
		docker rm -f -v "$name" >/dev/null
	done
}

case "$mode" in
	text) text_checks ;;
	run) trap cleanup EXIT; run_checks ;;
	all) text_checks; trap cleanup EXIT; run_checks ;;
	*) echo "쓰임: $0 [text|run|all]" >&2; exit 2 ;;
esac

if [ "${#measurements[@]}" -gt 0 ]; then
	echo "== 잰 값"
	for entry in "${measurements[@]}"; do echo "  ${entry%%|*}: ${entry#*|}"; done
fi
echo "== 실패 $failures"
[ "$failures" = 0 ]
