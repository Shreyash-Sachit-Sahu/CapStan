#!/usr/bin/env bash
#
# Demo pre-flight. Start the two servers yourself, then run this and read the
# last line.
#
#   terminal 1:  docker compose up -d --wait
#   terminal 2:  cd backend  && ./mvnw -o spring-boot:run "-Dspring-boot.run.profiles=demo"
#   terminal 3:  cd frontend && npm run build && npm run start
#   then:        bash scripts/demo-preflight.sh
#
# This script deliberately does NOT start or stop anything. An earlier version
# did, and spawning detached daemons portably from Git Bash cost two failed runs
# (`setsid` does not exist there, and a backgrounded child that inherits the
# script's stdout keeps `| tee` hanging forever after the script has finished).
# Process lifecycle belongs in your terminals, where you can see it. What this
# script owns is ordering and verification, which is where the real failures were.
#
# Every demo failure we have actually had was an ordering error, not a code
# error, and all three fail SILENTLY — the system looks healthy and the beat dies
# on stage:
#
#   1. Rebuilding the frontend under a running `next start`. The server holds the
#      manifest it booted with, so it serves HTML referencing chunk hashes that
#      are no longer on disk. Every route still returns 200 and the front page is
#      perfect; only the case pages die, in the browser, with ChunkLoadError. An
#      HTTP status check cannot see this, so we fetch the chunk the HTML asks for.
#
#   2. Running the sweep after the holdout run. clearExecutionState() wipes
#      payment_attempt, intervention and audit_event GLOBALLY at the start of
#      every run — that is the G11 cross-batch contamination fix and it is
#      correct. So a sweep leaves only its final batch with case-level state.
#      The persisted reports live in another table and survive, so the front page
#      looks flawless while tabs 2 and 3 show one lonely CASE_OPENED row.
#
#   3. Capturing the tamper event id before that run. Ids are sequence-generated
#      and the run replaces them, so an id captured first is a 404 when pasted.
#
# Idempotent. Safe to run twice. ~90s, almost all of it the backtest.

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

CASE_LOST=8fa8b03a-c0d7-4f16-b6a4-72345f949974   # 1:35 beat, lost response
CASE_WRONG=a5225e40-474e-4a54-af3e-d35da24dacfa  # 2:25 beat, model wrong

FAILED=0
step() { printf '\n== %s\n' "$*"; }
ok()   { printf '   ok    %s\n' "$*"; }
warn() { printf '   note  %s\n' "$*"; }
fail() { printf '   FAIL  %s\n' "$*"; FAILED=1; }
die()  { printf '\nPRE-FLIGHT ABORTED: %s\n' "$*"; exit 1; }

psql_q() {
  timeout 30 docker compose exec -T postgres psql -U capstan -d capstan -tAc "$1" 2>/dev/null \
    | tr -d '\r' | head -1
}
http() { curl -s -o /dev/null -w '%{http_code}' -m "${2:-20}" "$1"; }

# ---------------------------------------------------------------- 1. infra
step "1/6  Infrastructure"
docker version >/dev/null 2>&1 || die "Docker is not running. Start Docker Desktop, then:
    docker compose up -d --wait"
for svc in capstan-postgres capstan-redis capstan-rabbit; do
  status=$(docker inspect -f '{{.State.Health.Status}}' "$svc" 2>/dev/null)
  [ "$status" = "healthy" ] && ok "$svc healthy" || fail "$svc is '${status:-absent}', expected healthy"
done
[ "$FAILED" = 0 ] || die "infrastructure is not healthy. Try:
    docker compose up -d --wait     (then: docker compose logs)"

# ---------------------------------------------------------------- 2. backend
step "2/6  Backend"
if [ "$(http http://localhost:8080/actuator/health 5)" != "200" ]; then
  die "backend is not up on :8080. In its own terminal:
    cd backend && ./mvnw -o spring-boot:run \"-Dspring-boot.run.profiles=demo\"

  If it refuses the port, an orphan JVM is holding it — spring-boot:run forks a
  child, so Ctrl-C on the wrapper can leave it behind:
    netstat -ano | grep :8080      then stop that PID"
fi
ok "http://localhost:8080 is UP"

# The tamper endpoint is @Profile(\"demo\"). Without the profile the 1:35 beat
# 404s and nothing in the UI warns you beforehand. Actuator exposes only
# health/info/metrics here, so ask the endpoint itself: absent profile means no
# handler mapping at all, which is a 404 on a path that otherwise 400s or 500s.
probe=$(http "http://localhost:8080/api/admin/tamper/0" 10)
if [ "$probe" = "404" ]; then
  fail "demo profile NOT active — the 1:35 tamper beat will 404. Restart the backend with:
           ./mvnw -o spring-boot:run \"-Dspring-boot.run.profiles=demo\""
else
  ok "demo profile active — tamper endpoint present (probe returned $probe, not 404)"
fi

code=$(http "http://localhost:8080/api/backtest/report?batch=holdout")
[ "$code" = "200" ] && ok "backtest report endpoint answers" || fail "report endpoint -> $code"

# ---------------------------------------------------------------- 3. data
step "3/6  Batch data"
cases=$(psql_q "select count(*) from recovery_case where batch_label='holdout';")
if [ "${cases:-0}" -lt 300 ]; then
  warn "holdout not loaded (${cases:-0} cases) — loading, then diagnosing (~2 min)"
  curl -sf -X POST 'http://localhost:8080/api/admin/batch/load?batch=holdout' \
    -H 'Content-Type: application/json' --data-binary @fixtures/batch_holdout.json >/dev/null \
    || die "batch load failed"
  cases=$(psql_q "select count(*) from recovery_case where batch_label='holdout';")
fi
ok "$cases holdout cases loaded"

undiagnosed=$(psql_q "select count(*) from recovery_case where batch_label='holdout' and diagnosed_cause is null;")
if [ "${undiagnosed:-1}" -gt 0 ]; then
  warn "$undiagnosed undiagnosed — running prepare (~2 min, Tier 3 is throttled)"
  curl -sf -m 900 -X POST 'http://localhost:8080/api/backtest/prepare?batch=holdout' >/dev/null \
    || die "diagnosis prepare failed"
fi
ok "all holdout cases diagnosed"

# ---------------------------------------------------------------- 4. the run
step "4/6  Holdout backtest  (~80s — this MUST be the last run before you present)"
code=$(curl -s -o /dev/null -w '%{http_code}' -m 400 -X POST \
  'http://localhost:8080/api/backtest/run?batch=holdout&inject=timeout_rate:0.05')
[ "$code" = "200" ] || die "backtest run returned $code — check the backend terminal"
ok "run complete and persisted"

# The check that catches the blank-case-page failure.
lost_events=$(psql_q  "select count(*) from audit_event where case_id='$CASE_LOST';")
lost_debits=$(psql_q  "select count(*) from payment_attempt where case_id='$CASE_LOST';")
wrong_events=$(psql_q "select count(*) from audit_event where case_id='$CASE_WRONG';")
wrong_debits=$(psql_q "select count(*) from payment_attempt where case_id='$CASE_WRONG';")

[ "${lost_events:-0}" -ge 5 ] \
  && ok "tab 2 (lost response): $lost_events events" \
  || fail "tab 2 has only ${lost_events:-0} events — the 1:35 beat has nothing to show"
[ "${lost_debits:-0}" = "1" ] \
  && ok "tab 2 charged exactly once, as the script says" \
  || fail "tab 2 shows ${lost_debits:-0} debits, the script says one"
[ "${wrong_events:-0}" -ge 3 ] \
  && ok "tab 3 (model wrong): $wrong_events events" \
  || fail "tab 3 has only ${wrong_events:-0} events — the 2:25 beat has nothing to show"
[ "${wrong_debits:-0}" = "0" ] \
  && ok "tab 3 zero debits — G10 held, as the script says" \
  || fail "tab 3 shows ${wrong_debits:-0} debits, the script says zero"

# ---------------------------------------------------------------- 5. frontend
step "5/6  Frontend"
if [ "$(http http://localhost:3000/ 5)" != "200" ]; then
  die "frontend is not up on :3000. In its own terminal:
    cd frontend && npm run build && npm run start
  Build first, then start — never rebuild while it is running."
fi
ok "http://localhost:3000 is up"

# Prove the served HTML and the chunks on disk agree. Every route below returns
# 200 even when this is broken, which is exactly why it needs its own check.
chunk=$(curl -s -m 20 "http://localhost:3000/cases/$CASE_LOST" \
        | grep -oE 'app/cases/%5Bid%5D/page-[a-f0-9]+\.js' | sort -u | head -1)
if [ -z "$chunk" ]; then
  fail "could not find the case-page chunk reference in the served HTML"
else
  code=$(http "http://localhost:3000/_next/static/chunks/$chunk")
  if [ "$code" = "200" ]; then
    ok "case-page chunk resolves (${chunk##*/})"
  else
    fail "served HTML references ${chunk##*/} but it returns $code — STALE BUILD.
           The case tabs will ChunkLoadError in the browser. Fix:
             stop the frontend, then  npm run build && npm run start"
  fi
fi

for path in "/" "/exceptions" "/cases/$CASE_LOST" "/cases/$CASE_WRONG"; do
  code=$(http "http://localhost:3000$path")
  [ "$code" = "200" ] && ok "GET $path -> 200" || fail "GET $path -> $code"
done

# ---------------------------------------------------------------- 6. tamper id
step "6/6  Tamper id  (read after the run, so it is the id that exists now)"
TAMPER=$(psql_q "select id from audit_event where case_id='$CASE_LOST' and seq=2;")
[ -n "${TAMPER:-}" ] && ok "seq 2 event id = $TAMPER" || fail "could not read the tamper event id"

# ---------------------------------------------------------------- summary
printf '\n────────────────────────────────────────────────────────────\n'
if [ "$FAILED" = 0 ]; then
  printf 'ALL CHECKS PASSED — safe to present.\n\n'
else
  printf 'SOME CHECKS FAILED — read the FAIL lines above. Do not present yet.\n\n'
fi
cat <<SUMMARY
Open these four tabs, in this order:

  1  http://localhost:3000/
  2  http://localhost:3000/cases/$CASE_LOST
  3  http://localhost:3000/cases/$CASE_WRONG
  4  http://localhost:3000/exceptions

Tamper command for the 1:35 beat — paste this, do not retype the id:

  curl -X POST http://localhost:8080/api/admin/tamper/${TAMPER:-<CAPTURE_FAILED>}

Do NOT run the sweep after this. It wipes case-level state and blanks tabs 2
and 3. If you do, just run this script again.
SUMMARY
exit "$FAILED"
