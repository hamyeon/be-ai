#!/usr/bin/env bash
# 본격 부하 실행(run-stages.sh) 전에 반드시 먼저 통과시켜야 하는 smoke test다. 정확히 이
# 순서로 실행한다:
#   1) Seeder 실제 실행(users 1회 + 이번 smoke run 전용 fresh auction)
#   2) 서버 재시작 (사람이 직접 - actuator가 최근 추가돼 이미 떠 있던 프로세스에는 없을 수
#      있다. 스크립트가 여기서 멈추고 확인을 기다린다)
#   3) /actuator/health 확인
#   4) Hikari metric endpoint 확인
#   5) 8 VU k6 실행
#   6) post-state invariant check 실행
#
# 사용법: LOCAL_DB_PASSWORD=<비번> ./smoke-test.sh
set -euo pipefail
cd "$(dirname "$0")"

BASE_URL="${BASE_URL:-http://localhost:8080}"
START_PRICE="${START_PRICE:-100000}"
BID_INCREMENT="${BID_INCREMENT:-5000}"

if [ -z "${LOCAL_DB_PASSWORD:-}" ]; then
  echo "LOCAL_DB_PASSWORD가 설정되지 않았습니다." >&2
  exit 1
fi
if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 CLI를 찾을 수 없습니다. https://k6.io/docs/get-started/installation/ 참고." >&2
  exit 1
fi

echo "=== 1/6 Seeder 실행 ==="
if [ ! -f "data/hot-auction-users.json" ]; then
  echo "--- users 풀이 없어 HotAuctionUserSeeder부터 실행합니다 ---"
  (cd ../../backend && LOCAL_DB_PASSWORD="$LOCAL_DB_PASSWORD" ./gradlew.bat test \
    --tests "com.vintic.backend.loadtest.HotAuctionUserSeeder" -DbidderCount=200)
else
  echo "--- data/hot-auction-users.json 이미 존재 - 재사용 ---"
fi
(cd ../../backend && LOCAL_DB_PASSWORD="$LOCAL_DB_PASSWORD" ./gradlew.bat test \
  --tests "com.vintic.backend.loadtest.HotAuctionRoundSeeder" \
  -DstartPrice="$START_PRICE" -DbidIncrement="$BID_INCREMENT" --rerun)
mkdir -p results
cp data/hot-auction-seed.json results/seed-smoke.json

echo
echo "=== 2/6 서버 재시작 ==="
echo "로컬 Spring Boot 앱(local 프로필)을 지금 재시작하세요 - actuator 설정이 반영돼 있어야"
echo "다음 단계(health/metrics)가 통과합니다. 재시작이 끝나고 :8080이 응답할 때까지 기다린 뒤"
read -r -p "Enter를 눌러 계속하세요..."

echo
echo "=== 3/6 /actuator/health 확인 ==="
health_status=$(curl -s -o /tmp/health.json -w "%{http_code}" "${BASE_URL}/actuator/health" --max-time 5)
cat /tmp/health.json
echo
if [ "$health_status" != "200" ]; then
  echo "health check 실패(HTTP ${health_status}) - 서버 상태를 확인하세요." >&2
  exit 1
fi
echo "--- health OK ---"

echo
echo "=== 4/6 Hikari metric endpoint 확인 ==="
for metric in hikaricp.connections.active hikaricp.connections.pending hikaricp.connections.max; do
  echo "--- ${metric} ---"
  status=$(curl -s -o /tmp/metric.json -w "%{http_code}" "${BASE_URL}/actuator/metrics/${metric}" --max-time 5)
  cat /tmp/metric.json
  echo
  if [ "$status" != "200" ]; then
    echo "metric endpoint 실패(HTTP ${status}): ${metric} - management.endpoints.web.exposure.include에 metrics가 포함돼 있는지 확인하세요." >&2
    exit 1
  fi
done
echo "--- Hikari metrics OK ---"

echo
echo "=== 5/6 8 VU k6 실행 ==="
VUS=8 UNRELATED_VUS=2 BASE_URL="$BASE_URL" RUN_TAG="smoke" \
  k6 run --summary-export results/summary-smoke.json hot-auction-bid.js

echo
echo "=== 6/6 post-state invariant check ==="
(cd ../../backend && LOCAL_DB_PASSWORD="$LOCAL_DB_PASSWORD" ./gradlew.bat test \
  --tests "com.vintic.backend.loadtest.HotAuctionInvariantCheck" --rerun)

echo
echo "=== smoke test 전체 통과 ==="
node summarize.js results/summary-smoke.json
