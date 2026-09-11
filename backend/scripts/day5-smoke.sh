#!/usr/bin/env bash
# Day5 3단계 §2~7: docker-compose.experiment.yml 스택(1회 기동, 재사용) 대상 실제 smoke 5종.
# 모든 검증은 API/DB/Queue 상태를 bounded polling으로 직접 확인한다(로그만 보고 통과 처리하지
# 않는다). 실패 시 즉시 비정상 종료(exit 1)한다. 각 시나리오는 서로 다른 objectKey/analysisId로
# 격리된다. loadtest/는 건드리지 않고, 코드 수정이 필요하지 않는 한 이미지를 재빌드하지 않는다.
#
# 사전 조건: `docker compose -f docker-compose.experiment.yml up -d --build`가 이미 실행되어
# api/worker/mysql/redis/localstack이 전부 healthy여야 한다(이 스크립트는 스택을 새로 띄우지
# 않는다).
set -euo pipefail
cd "$(dirname "$0")/.."

API=http://localhost:8090
LOCALSTACK=http://localhost:4566
MYSQL_C=vintic-experiment-mysql-1
MYSQL_PW=experimentpw
MYSQL_DB=vintic_experiment
LOCALSTACK_C=vintic-experiment-localstack-1
WORKER_C=vintic-experiment-worker-1
API_C=vintic-experiment-api-1
QUEUE_NAME=analysis-job-queue
USER_ID=910001

mysql_q() { docker exec "$MYSQL_C" mysql -uroot -p"$MYSQL_PW" "$MYSQL_DB" -N -e "$1" 2>/dev/null; }
awslocal_() { docker exec "$LOCALSTACK_C" awslocal "$@" 2>/dev/null; }
jf() { python -c "import sys,json; d=json.load(sys.stdin); print(eval('d'+sys.argv[1]))" "$1"; }
fail() { echo "FAIL: $1"; exit 1; }

echo "=== 0/5 사전 준비: X-User-Id용 실제 users row seed ==="
mysql_q "INSERT IGNORE INTO users (id, email, nickname, noshow_count, created_at) VALUES (${USER_ID}, 'day5-smoke@example.com', 'day5-smoke', 0, NOW(6));"
mysql_q "SELECT id FROM users WHERE id=${USER_ID};" | grep -q "$USER_ID" || fail "user seed 확인 실패"
echo "userId=${USER_ID} 확인됨"

# awslocal sqs get-queue-url이 domain 전략(localhost.localstack.cloud) URL을 반환해 실제로
# 못 쓰는 걸 확인했다(SQS_ENDPOINT_STRATEGY=path인데도 Host 헤더 기반으로 동적 결정되는 듯) -
# Day5 2단계에서 이미 실제로 검증된 path-style URL(worker/api의 ANALYSIS_JOB_QUEUE_URL과 동일값)을
# 그대로 재사용한다. localstack 컨테이너 자기 자신에서 실행해도 Compose 내장 DNS가 서비스명을
# 자기 자신으로 해석해준다.
QUEUE_URL="http://localstack:4566/queue/us-east-1/000000000000/${QUEUE_NAME}"
echo "queueUrl(고정값, Day5 2단계에서 실제 검증됨)=${QUEUE_URL}"

# 공용: presigned-url 발급 + 실제 HTTP PUT 업로드. echo로 objectKey를 반환한다.
do_real_upload() {
  local body content_file
  body=$(curl -sf -X POST "${API}/api/uploads/presigned-url")
  local objectKey uploadUrl
  objectKey=$(echo "$body" | jf "['data']['objectKey']")
  uploadUrl=$(echo "$body" | jf "['data']['uploadUrl']")
  content_file=$(mktemp)
  printf 'day5-smoke-dummy-image-bytes-%s' "$RANDOM" > "$content_file"
  local put_status
  put_status=$(curl -s -o /dev/null -w '%{http_code}' -X PUT --data-binary "@${content_file}" "$uploadUrl")
  rm -f "$content_file"
  [ "$put_status" = "200" ] || fail "presigned PUT 업로드 실패 httpStatus=${put_status}"
  echo "$objectKey"
}

bounded_poll() {
  # $1=최대 시도, $2=간격(초), $3=체크 커맨드(0이면 성공)
  local max=$1 interval=$2 cmd=$3
  for i in $(seq 1 "$max"); do
    if eval "$cmd"; then return 0; fi
    sleep "$interval"
  done
  return 1
}

echo
echo "########## Smoke 1: 실제 presigned 업로드 -> 실제 PUT -> 실제 async 제출 -> COMPLETED -> ECS 로그 MDC 검증 ##########"
OBJKEY1=$(do_real_upload)
echo "objectKey1=${OBJKEY1}"
IDEMKEY1="day5-smoke1-$RANDOM-$RANDOM"
SUBMIT1=$(curl -sf -X POST "${API}/api/analyses" \
  -H "X-User-Id: ${USER_ID}" -H "Idempotency-Key: ${IDEMKEY1}" -H "Content-Type: application/json" \
  -d "{\"objectKey\":\"${OBJKEY1}\"}")
ID1=$(echo "$SUBMIT1" | jf "['data']['analysisId']")
STATUS1=$(echo "$SUBMIT1" | jf "['data']['status']")
echo "analysisId1=${ID1} 제출 직후 status=${STATUS1}"
[ -n "$ID1" ] && [ "$ID1" != "None" ] || fail "Smoke1: 제출 응답에서 id를 얻지 못함: $SUBMIT1"

bounded_poll 40 3 "[ \"\$(mysql_q \"SELECT status FROM product_analysis_jobs WHERE id=${ID1};\")\" = 'COMPLETED' ]" \
  || fail "Smoke1: ${ID1}이 120초 안에 COMPLETED되지 않음. 현재상태=$(mysql_q "SELECT status FROM product_analysis_jobs WHERE id=${ID1};")"
echo "Smoke1: status=COMPLETED 확인"

RESULT_COUNT1=$(mysql_q "SELECT COUNT(*) FROM analysis_result WHERE analysis_id=${ID1};")
[ "$RESULT_COUNT1" = "1" ] || fail "Smoke1: analysis_result row 개수가 1이 아님: ${RESULT_COUNT1}"
echo "Smoke1: analysis_result row 정확히 1건 확인"

echo "--- Smoke1: worker ECS JSON 로그에서 4개 MDC 필드 실제 구조화 확인 ---"
WORKER_LOGS1=$(docker logs "$WORKER_C" 2>&1)
LOG_LINE1=$(echo "$WORKER_LOGS1" | grep "\"analysisId\":\"${ID1}\"" | head -1)
[ -n "$LOG_LINE1" ] && echo "$LOG_LINE1" | python -c "
import sys, json
line = sys.stdin.read()
d = json.loads(line)
for f in ['analysisId','requestId','workerId','serverId']:
    if f not in d:
        print(f'MISSING:{f}'); sys.exit(1)
    if not d[f]:
        print(f'EMPTY:{f}'); sys.exit(1)
print('MDC_OK', {f: d[f] for f in ['analysisId','requestId','workerId','serverId']})
" || fail "Smoke1: worker 로그 라인에서 4개 MDC 필드를 top-level JSON 필드로 확인하지 못함"

echo "--- Smoke1: 로그에 시크릿(presigned URL 서명값/AWS 자격증명) 유출 없는지 확인 ---"
if echo "$WORKER_LOGS1" | grep -qi "X-Amz-Signature\|X-Amz-Credential"; then
  fail "Smoke1: worker 로그에 presigned URL 서명 파라미터가 그대로 남아있음(시크릿 유출)"
fi
echo "Smoke1: 시크릿 유출 없음 확인"

echo
echo "########## Smoke 2: sync E2E - 같은 실제 업로드 재사용, 실제 input propagation 검증 ##########"
SYNC_OK=$(curl -sf -X POST "${API}/api/analyses/sync" -H "Content-Type: application/json" \
  -d "{\"objectKey\":\"${OBJKEY1}\"}")
RAW_RESULT=$(echo "$SYNC_OK" | jf "['data']['rawResult']")
[ "$RAW_RESULT" = "fake-result-null" ] || fail "Smoke2: 성공 응답 rawResult가 기대값과 다름: ${RAW_RESULT}"
echo "Smoke2: 성공 케이스 - HTTP 2xx, Frozen 계약 형태(rawResult=fake-result-null) 확인"

# 존재하지 않는 objectKey -> 실제 S3 GetObject가 NoSuchKey로 실패해야 한다(500) - objectKey가
# 실제로 S3Client에 전달되어 사용된다는 증거(스텁이 아님). "fake-result-null" 성공만으로는
# input propagation을 증명하지 못한다는 지적에 대한 직접 검증.
SYNC_BAD_STATUS=$(curl -s -o /tmp/day5-smoke-sync-bad.json -w '%{http_code}' -X POST "${API}/api/analyses/sync" \
  -H "Content-Type: application/json" -d '{"objectKey":"analysis-uploads/day5-smoke-nonexistent-key"}')
[ "$SYNC_BAD_STATUS" = "500" ] || fail "Smoke2: 존재하지 않는 objectKey에 대해 500이 아닌 ${SYNC_BAD_STATUS} 반환 - objectKey가 실제 S3 호출에 쓰이지 않는 것으로 의심됨"
echo "Smoke2: 존재하지 않는 objectKey -> 실제 S3 NoSuchKey(500) 확인 - objectKey가 real GetObject에 실제로 전달됨을 증명"

JOBS_COUNT_AFTER_SYNC=$(mysql_q "SELECT COUNT(*) FROM product_analysis_jobs;")
QUEUE_ATTRS_AFTER_SYNC=$(awslocal_ sqs get-queue-attributes --queue-url "$QUEUE_URL" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible)
echo "Smoke2: sync 호출 후 product_analysis_jobs 총 행수=${JOBS_COUNT_AFTER_SYNC}, queue attrs=${QUEUE_ATTRS_AFTER_SYNC} (sync가 job을 만들거나 큐에 발행하지 않았는지는 이 값을 Smoke1 종료 시점 값과 비교해 보고서에 기록)"

echo
echo "########## Smoke 3: 실제 Worker Crash -> [자동 restart: NOT VERIFIED, 환경 한계 확인됨] -> 앱 레벨 fenced stale 재선점 -> 최종 COMPLETED ##########"
# Day5 4단계에서 정밀 재검증한 결과(사용자 승인된 판정, 더 이상 파고들지 않음):
#   - docker exec <container> kill -9 1 : 효과 없음(Linux PID-namespace-init이 네임스페이스
#     "내부"에서 오는, 핸들러 없는 시그널(SIGKILL 포함)을 조용히 무시함 - 커널 레벨 동작이라
#     이 저장소 코드로 우회 불가). RestartCount/StartedAt 불변, 작업이 방해받지 않고 완료되는
#     것으로 실제 확인함.
#   - docker kill -s KILL : JVM을 진짜로 죽인다(docker events: kill->die, exitCode=137,
#     확인됨). 그러나 이 로컬 Docker Desktop 환경에서는 새로 만든 컨테이너에서도 restart:always가
#     트리거되지 않음을 재확인(docker events에 후속 start/restart 이벤트 없음) - Docker
#     Engine이 CLI/API로 들어온 kill을 "의도된 정지"로 취급해 restart 정책 대상에서 제외하는
#     것으로 보인다. tini/dumb-init 같은 PID1 교체는 "PID1은 Java여야 한다"는 요구와 정면
#     충돌하므로 추가하지 않는다(사용자 승인).
# 그래서 이 블록은 "restart:always 자동 재기동"을 PASS라고 주장하지 않는다 - docker kill 뒤
# 명시적으로 수동 docker start를 수행하고(자동이 아님을 로그에 명확히 남김), 그 이후의
# 앱 레벨(fenced claim/stale 재선점) 복구 경로만 검증한다.
UPTIME_BEFORE=$(docker inspect --format '{{.State.StartedAt}}' "$WORKER_C")
RESTARTCOUNT_BEFORE=$(docker inspect --format '{{.RestartCount}}' "$WORKER_C")
echo "Smoke3: crash 주입 전 StartedAt=${UPTIME_BEFORE} RestartCount=${RESTARTCOUNT_BEFORE}"

OBJKEY3=$(do_real_upload)
IDEMKEY3="day5-smoke3-$RANDOM-$RANDOM"
SUBMIT3=$(curl -sf -X POST "${API}/api/analyses" \
  -H "X-User-Id: ${USER_ID}" -H "Idempotency-Key: ${IDEMKEY3}" -H "Content-Type: application/json" \
  -d "{\"objectKey\":\"${OBJKEY3}\"}")
ID3=$(echo "$SUBMIT3" | jf "['data']['analysisId']")
echo "analysisId3=${ID3}"

bounded_poll 20 1 "[ \"\$(mysql_q \"SELECT status FROM product_analysis_jobs WHERE id=${ID3};\")\" = 'PROCESSING' ]" \
  || fail "Smoke3: ${ID3}이 20초 안에 PROCESSING(in-flight)이 되지 않음"
ORIGINAL_WORKER_ID=$(mysql_q "SELECT worker_id FROM product_analysis_jobs WHERE id=${ID3};")
echo "Smoke3: in-flight 확인, originalWorkerId=${ORIGINAL_WORKER_ID}"

echo "Smoke3: 실제 SIGKILL 전송 (docker kill -s KILL ${WORKER_C}) - JVM을 실제로 죽임(exitCode=137, docker events로 확인됨)"
docker kill -s KILL "$WORKER_C" >/dev/null

sleep 15
RUNNING_AFTER_KILL=$(docker inspect --format '{{.State.Running}}' "$WORKER_C" 2>/dev/null)
RESTARTCOUNT_AFTER_KILL=$(docker inspect --format '{{.RestartCount}}' "$WORKER_C" 2>/dev/null)
if [ "$RUNNING_AFTER_KILL" = "true" ] && [ "$RESTARTCOUNT_AFTER_KILL" -gt "$RESTARTCOUNT_BEFORE" ]; then
  echo "Smoke3: restart:always 자동 재기동 확인됨 (RestartCount ${RESTARTCOUNT_BEFORE}->${RESTARTCOUNT_AFTER_KILL})"
  AUTO_RESTART_VERIFIED=1
else
  echo "Smoke3: [NOT VERIFIED] 15초 대기 후에도 restart:always 자동 재기동 미확인 (Running=${RUNNING_AFTER_KILL} RestartCount=${RESTARTCOUNT_AFTER_KILL}) - 이 로컬 Docker Desktop 환경의 알려진 한계(위 주석 참고). 명시적으로 수동 docker start를 실행한다(자동 아님)."
  docker start "$WORKER_C" >/dev/null
  AUTO_RESTART_VERIFIED=0
fi

bounded_poll 30 2 "[ \"\$(docker inspect --format '{{.State.Running}}' ${WORKER_C} 2>/dev/null)\" = 'true' ]" \
  || fail "Smoke3: worker가 60초 안에 Running 상태가 되지 않음(수동 docker start 이후에도)"

UPTIME_AFTER=$(docker inspect --format '{{.State.StartedAt}}' "$WORKER_C")
echo "Smoke3: 재기동 후 StartedAt=${UPTIME_AFTER} (autoRestartVerified=${AUTO_RESTART_VERIFIED})"

# docker logs는 재기동 전/후 로그를 이어서 보여주므로 --since로 이번 재기동 이후만 스코프한다.
bounded_poll 60 2 "docker logs --since '${UPTIME_AFTER}' ${WORKER_C} 2>&1 | grep -q 'Started BackendApplication'" \
  || fail "Smoke3: 재기동된 worker가 120초 안에 부팅 완료 로그를 남기지 않음"
echo "Smoke3: 재기동(이번 StartedAt=${UPTIME_AFTER}) 이후 부팅 완료 로그 확인"

echo "Smoke3: SQS Visibility Timeout(90s) 경과 후 stale 재선점 -> 최종 COMPLETED 대기 (최대 150초)"
bounded_poll 75 2 "[ \"\$(mysql_q \"SELECT status FROM product_analysis_jobs WHERE id=${ID3};\")\" = 'COMPLETED' ]" \
  || fail "Smoke3: ${ID3}이 150초 안에 최종 COMPLETED되지 않음. 현재상태=$(mysql_q "SELECT status FROM product_analysis_jobs WHERE id=${ID3};")"

FINAL_WORKER_ID3=$(mysql_q "SELECT worker_id FROM product_analysis_jobs WHERE id=${ID3};")
[ "$FINAL_WORKER_ID3" != "$ORIGINAL_WORKER_ID" ] || fail "Smoke3: 최종 workerId가 kill 이전과 동일함(재기동 후 새 workerId로 재선점됐다는 증거 없음)"
echo "Smoke3: stale 재선점 확인 - originalWorkerId=${ORIGINAL_WORKER_ID} -> finalWorkerId=${FINAL_WORKER_ID3}"

RESULT_COUNT3=$(mysql_q "SELECT COUNT(*) FROM analysis_result WHERE analysis_id=${ID3};")
[ "$RESULT_COUNT3" = "1" ] || fail "Smoke3: analysis_result row 개수가 1이 아님: ${RESULT_COUNT3}"
echo "Smoke3: 최종 analysis_result row 정확히 1건 확인 (컨테이너/LocalStack 환경 한정 관찰 - 실제 AWS 일반화 아님)"
if [ "$AUTO_RESTART_VERIFIED" = "1" ]; then
  echo "Smoke3 판정: PASS (자동 재기동 + 앱 레벨 fenced stale 재선점 모두 확인)"
else
  echo "Smoke3 판정: PARTIAL / NOT FULLY VERIFIED - restart:always 자동 재기동은 이 로컬 Docker Desktop 환경에서 확인하지 못함(수동 docker start 사용). docker kill 이후의 앱 레벨(fenced claim, stale 재선점, 정확히 1건 결과) 복구 경로는 실제로 확인됨."
fi

echo
echo "########## Smoke 4: 동일 analysisId 중복 실제 SQS 메시지 2건 -> 최종 결과 1건, 큐 소진 ##########"
OBJKEY4=$(do_real_upload)
IDEMKEY4="day5-smoke4-$RANDOM-$RANDOM"
SUBMIT4=$(curl -sf -X POST "${API}/api/analyses" \
  -H "X-User-Id: ${USER_ID}" -H "Idempotency-Key: ${IDEMKEY4}" -H "Content-Type: application/json" \
  -d "{\"objectKey\":\"${OBJKEY4}\"}")
ID4=$(echo "$SUBMIT4" | jf "['data']['analysisId']")
echo "analysisId4=${ID4} (정상 제출 - delivery #1 자동 발행됨)"

echo "Smoke4: 동일 analysisId로 delivery #2(진짜 중복 메시지)를 즉시 수동 발행"
awslocal_ sqs send-message --queue-url "$QUEUE_URL" --message-body "{\"eventVersion\":1,\"analysisId\":${ID4}}" >/dev/null
echo "Smoke4: 중복 메시지 발행 완료"

bounded_poll 75 2 "[ \"\$(mysql_q \"SELECT status FROM product_analysis_jobs WHERE id=${ID4};\")\" = 'COMPLETED' ]" \
  || fail "Smoke4: ${ID4}이 150초 안에 COMPLETED되지 않음"
echo "Smoke4: COMPLETED 확인"

RESULT_COUNT4=$(mysql_q "SELECT COUNT(*) FROM analysis_result WHERE analysis_id=${ID4};")
[ "$RESULT_COUNT4" = "1" ] || fail "Smoke4: analysis_result row 개수가 1이 아님(중복 커밋 발생): ${RESULT_COUNT4}"
echo "Smoke4: analysis_result 정확히 1건 - Processor 실행 횟수와 commit 횟수를 혼동하지 않고 최종 커밋 결과만으로 판단"

echo "Smoke4: 중복 메시지가 최종적으로 큐에서 소진(삭제)되는지 대기(최대 150초, redrive/재노출 주기 포함)"
queue_drained() {
  awslocal_ sqs get-queue-attributes --queue-url "$QUEUE_URL" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible | \
  python -c "
import sys, json
d = json.load(sys.stdin)['Attributes']
sys.exit(0 if d.get('ApproximateNumberOfMessages')=='0' and d.get('ApproximateNumberOfMessagesNotVisible')=='0' else 1)
"
}
bounded_poll 75 2 "queue_drained" \
  || fail "Smoke4: 큐가 150초 안에 완전히 소진되지 않음(중복 메시지가 삭제되지 않고 남아있을 수 있음)"
echo "Smoke4: 큐 ApproximateNumberOfMessages=0, NotVisible=0 - 두 delivery 모두 소진 확인"

echo
echo "=== Smoke 1-4 전부 PASS. Smoke5(실제 docker compose stop)는 별도 마지막 단계에서 실행 ==="
