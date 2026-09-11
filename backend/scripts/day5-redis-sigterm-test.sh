#!/usr/bin/env bash
# Day5 3단계 §1: Redis baseline SIGTERM 통합 테스트 (docs/infra-sprint/redis-streams-audit.md
# §g-2 설계 그대로 실행). SQS Worker SIGTERM smoke(day5-smoke.sh의 Smoke 5)와 별개 테스트다.
#
# 실제 Consumer(AnalysisTaskConsumer, 기존 코드 무수정)가 QUEUED 메시지를 받아
# startVisionProcessing()을 커밋한 뒤 FakeVisionAnalysisService(Sleeper 기반 결정적 지연)
# 안에서 자고 있는 "실제 in-flight" 순간에 실제 OS 레벨 SIGTERM을 보낸다(mock/직접 메서드
# 호출/Context.close()로 대체하지 않음 - 네이티브 Windows에서 Java 프로세스에 진짜 SIGTERM을
# 보낼 방법이 없어, docker 컨테이너로 자식 프로세스를 띄우고 `docker stop -t <timeout>`으로
# 보낸다 - Smoke 5와 동일한 방식).
#
# autique-local-redis(사용자의 기존 로컬 인프라, backend_default 네트워크)는 스트림 발행/소비
# 자체에만 쓰므로 그대로 재사용한다 - 별도 Redis 컨테이너를 만들지 않는다.
#
# MySQL은 autique-local-mysql을 재사용하지 "않는다" - 사전 점검(DESCRIBE product_analysis_session)
# 에서 그 DB의 실제 status 컬럼 ENUM에 'QUEUED'가 빠져 있는 걸 확인했다(Hibernate ddl-auto=update는
# 기존 enum 컬럼의 값 목록을 재생성하지 않는 알려진 한계 - Java의 AnalysisStatus enum이 나중에
# QUEUED를 추가했지만 그 DB의 테이블은 더 오래된 상태로 멈춰 있다). 이건 사용자의 실제 로컬 개발
# DB이므로 스키마를 ALTER하거나 데이터를 건드리지 않는다(보호 대상) - 대신 Day5 1단계와 같은
# 패턴으로 일회용 빈 MySQL 컨테이너를 띄워 최신 엔티티 코드가 ddl-auto=update로 올바른(QUEUED
# 포함) 스키마를 처음부터 만들게 한다. 이번에 만드는 컨테이너는 redis-sigterm-mysql(일회용 DB)과
# redis-sigterm-child(앱) 둘 뿐이며 스크립트 끝에서 모두 rm한다.
set -euo pipefail
cd "$(dirname "$0")/.."

MYSQL_CONTAINER=redis-sigterm-mysql
MYSQL_PW=testpw
MYSQL_DB=redis_sigterm_test
REDIS_CONTAINER=autique-local-redis
CHILD=redis-sigterm-child
VISION_DELAY_MS=8000
STOP_TIMEOUT=90
STREAM_KEY=ai:analysis:requests
GROUP=ai-analysis-workers

mysql_q() { docker exec "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_PW" "$MYSQL_DB" -N -e "$1" 2>/dev/null; }
redis_c() { docker exec "$REDIS_CONTAINER" redis-cli "$@" 2>/dev/null; }

echo "=== 1/8 정리(이전 실행 잔여물) ==="
docker rm -f "$CHILD" "$MYSQL_CONTAINER" >/dev/null 2>&1 || true

echo "=== 2/8 일회용 MySQL 기동(빈 스키마 - 최신 엔티티 코드로 ddl-auto=update가 만들게 함) ==="
docker run -d --name "$MYSQL_CONTAINER" --network backend_default \
  -e MYSQL_ROOT_PASSWORD="${MYSQL_PW}" \
  -e MYSQL_DATABASE="${MYSQL_DB}" \
  mysql:8.4 >/dev/null

echo "=== 3/8 MySQL ready 대기(bounded polling, 최대 60초) ==="
db_ready=0
for i in $(seq 1 60); do
  if docker exec "$MYSQL_CONTAINER" mysqladmin ping -h localhost -uroot -p"${MYSQL_PW}" --silent >/dev/null 2>&1; then
    db_ready=1
    break
  fi
  sleep 1
done
if [ "$db_ready" -ne 1 ]; then
  echo "FAIL: 일회용 MySQL이 60초 안에 준비되지 않음"
  docker rm -f "$MYSQL_CONTAINER" >/dev/null 2>&1 || true
  exit 1
fi

echo "=== 4/8 redis-baseline-test 자식 컨테이너 기동 (vision-delay-ms=${VISION_DELAY_MS}, ddl-auto=update로 스키마 자동 생성) ==="
docker run -d --name "$CHILD" --network backend_default \
  -e SPRING_PROFILES_ACTIVE=local,redis-baseline-test \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://${MYSQL_CONTAINER}:3306/${MYSQL_DB}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8" \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD="${MYSQL_PW}" \
  -e LOCAL_DB_PASSWORD="${MYSQL_PW}" \
  -e LOCAL_REDIS_HOST="${REDIS_CONTAINER}" \
  -e AWS_ACCESS_KEY_ID=test \
  -e AWS_SECRET_ACCESS_KEY=test \
  -e OPENAI_API_KEY=unused-in-redis-sigterm-test \
  -e ANALYSIS_REDIS_BASELINE_TEST_VISION_DELAY_MS="${VISION_DELAY_MS}" \
  vintic-backend:experiment >/dev/null

echo "=== 5/8 자식 기동 대기(bounded polling, 최대 60초) ==="
ready=0
for i in $(seq 1 60); do
  if docker logs "$CHILD" 2>&1 | grep -q "Started BackendApplication"; then
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  echo "FAIL: 자식 프로세스가 60초 안에 기동하지 않음"
  docker logs "$CHILD" 2>&1 | tail -40
  docker rm -f "$CHILD" >/dev/null 2>&1 || true
  exit 1
fi
echo "자식 기동 완료(Hibernate ddl-auto=update로 스키마 생성 완료)"

echo "=== 6/8 QUEUED 세션 seed (이제 스키마가 올바르게 생성됐으므로 이제 insert) ==="
mysql_q "INSERT INTO product_analysis_session (status) VALUES ('QUEUED');"
SESSION_ID=$(mysql_q "SELECT id FROM product_analysis_session ORDER BY id DESC LIMIT 1;")
echo "sessionId=${SESSION_ID}"

echo "=== 7/8 메시지 발행(XADD) + in-flight(VISION_PROCESSING) 확인 (bounded polling, 최대 15초) ==="
redis_c XADD "$STREAM_KEY" '*' payload "{\"analysisId\":${SESSION_ID},\"imageUrls\":[\"https://example.com/a.jpg\"]}" >/dev/null

inflight=0
for i in $(seq 1 30); do
  status=$(mysql_q "SELECT status FROM product_analysis_session WHERE id=${SESSION_ID};")
  if [ "$status" = "VISION_PROCESSING" ]; then
    inflight=1
    break
  fi
  sleep 0.5
done
if [ "$inflight" -ne 1 ]; then
  echo "FAIL: 15초 안에 VISION_PROCESSING(in-flight)을 확인하지 못함 - 마지막 status=${status:-unknown}"
  docker logs "$CHILD" 2>&1 | tail -40
  docker rm -f "$CHILD" >/dev/null 2>&1 || true
  exit 1
fi
echo "in-flight 확인됨: status=VISION_PROCESSING (Consumer가 실제로 FakeVisionAnalysisService sleep 중)"

echo "=== 8/8 실제 SIGTERM 전송 (docker stop -t ${STOP_TIMEOUT}) 및 소요시간 측정 ==="
T0=$(date +%s%3N)
docker stop -t "$STOP_TIMEOUT" "$CHILD" >/dev/null
T1=$(date +%s%3N)
ELAPSED_MS=$((T1 - T0))
echo "docker stop 반환까지 걸린 시간: ${ELAPSED_MS}ms (SIGTERM 발송 시점부터)"

echo "=== 결과 확인 ==="
FINAL_STATUS=$(mysql_q "SELECT status FROM product_analysis_session WHERE id=${SESSION_ID};")
PENDING_COUNT=$(redis_c XPENDING "$STREAM_KEY" "$GROUP" | head -1)
echo "최종 DB status=${FINAL_STATUS}"
echo "XPENDING summary(요약 1줄)=${PENDING_COUNT}"

RESULT="UNKNOWN"
if [ "$FINAL_STATUS" = "COMPLETED" ] || [ "$FINAL_STATUS" = "VISION_FAILED" ]; then
  RESULT="CALLBACK_COMPLETED_BEFORE_EXIT"
else
  RESULT="CALLBACK_INTERRUPTED_status_remained_${FINAL_STATUS}"
fi
echo "판정: ${RESULT}"

# 공유 autique-local-redis에 잔여 PEL이 남지 않도록 정리한다(메시지가 ACK됐으면 no-op).
if [ "$PENDING_COUNT" != "0" ]; then
  redis_c XACK "$STREAM_KEY" "$GROUP" "$(redis_c XPENDING "$STREAM_KEY" "$GROUP" - + 1 | head -1)" >/dev/null 2>&1 || true
fi

docker rm -f "$CHILD" "$MYSQL_CONTAINER" >/dev/null 2>&1 || true

echo
echo "=== SUMMARY (redis-streams-audit.md에 옮겨 적을 값) ==="
echo "sessionId=${SESSION_ID}"
echo "visionDelayMs=${VISION_DELAY_MS}"
echo "dockerStopElapsedMs=${ELAPSED_MS}"
echo "finalDbStatus=${FINAL_STATUS}"
echo "xpendingAfterStop=${PENDING_COUNT}"
echo "result=${RESULT}"
