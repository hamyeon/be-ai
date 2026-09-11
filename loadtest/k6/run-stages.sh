#!/usr/bin/env bash
# hot-auction k6 부하 테스트를 stage(VU 수) x REPEATS만큼 순차 실행한다.
# k6 스크립트 자체(hot-auction-bid.js)는 항상 "한 stage 1회"만 돌린다 - stage/반복 오케스트레이션과
# "매 run마다 fresh auction으로 시작"은 이 스크립트가 바깥에서 맡는다.
#
# run 간 상태 격리: 각 iteration마다
#   1) HotAuctionRoundSeeder를 먼저 실행해 이번 run 전용 새 hot auction + unrelated auction을
#      만들고(HotAuctionUserSeeder가 만든 seller/bidder 풀은 재사용),
#   2) 그 결과(hot-auction-seed.json)로 k6를 돌리고,
#   3) HotAuctionInvariantCheck로 "이번 run의 auctionId"만 검증한다.
# 이전 run의 Auction/Bid는 다음 run과 다른 auctionId를 쓰므로 절대 섞이지 않는다(HotAuctionRoundSeeder
# 클래스 주석 참고) - 같은 auction을 재사용하며 가격/Bid를 리셋하는 방식은 쓰지 않는다.
#
# Hikari/InnoDB 관측: k6가 도는 동안 /actuator/metrics/hikaricp.connections.{active,pending}를
# 짧은 간격으로 폴링해 이번 run 중 관측된 최댓값을 남기고, InnoDB 행 잠금 관련 글로벌 상태값
# (Innodb_row_lock_*)의 run 전후 델타를 함께 기록한다 - 전부 이미 켜져 있는 actuator/기본 InnoDB
# 카운터를 읽기만 할 뿐, production 코드나 Hikari/락 설정을 바꾸지 않는다.
#
# 추가 관측(150 VU 실험부터):
#   - performance_schema.events_statements_history_long: run 시작 직전 TRUNCATE(세션 한정 초기화,
#     설정 파일 변경 아님)한 뒤, run 종료 후 같은 THREAD_ID 안에서 "FOR UPDATE SELECT 완료 시각"
#     -> "다음 COMMIT/ROLLBACK 시각"을 페어링해 lock hold time(ms) raw sample을 뽑는다
#     (observability.js lockhold). 매칭 안 되는 건 uncorrelated로만 세고 추정하지 않는다.
#   - performance_schema.events_errors_summary_global_by_error: MySQL 에러코드 1205
#     (ER_LOCK_WAIT_TIMEOUT)/1213(ER_LOCK_DEADLOCK)의 run 전후 델타 - 이미 항상 켜져 있는
#     DB 레벨 카운터를 읽기만 한다(GlobalExceptionHandler는 40909 응답 시 아무 로그도 남기지
#     않으므로, "단순 40909 개수"와 구분되는 "실제 DB lock wait timeout/deadlock 발생 수"는
#     이 카운터가 유일한 근거다).
#   - backend-live.log(IntelliJ "Save console output to file"로 사용자가 직접 설정) run 구간만
#     잘라 results/backend-log-{tag}.log로 보존, k6 콘솔 출력도 results/k6-console-{tag}.log로
#     저장 - system failure 발생 시 실제 서버 예외 클래스/스택트레이스를 results/rootcause-{tag}.md로
#     추출한다(observability.js rootcause). 원본 backend-live.log는 절대 건드리지 않는다(읽기만 함).
#
# 사용법:
#   LOCAL_DB_PASSWORD=<비번> STAGES="8,20,50" REPEATS=1 ./run-stages.sh
#   LOCAL_DB_PASSWORD=<비번> STAGES="8" REPEATS=3 BASE_URL="http://localhost:8080" ./run-stages.sh
#
# 기본값: STAGES="8,20,50,100,200" REPEATS=1
set -euo pipefail
cd "$(dirname "$0")"

STAGES="${STAGES:-8,20,50,100,200}"
REPEATS="${REPEATS:-1}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
UNRELATED_VUS="${UNRELATED_VUS:-2}"
START_PRICE="${START_PRICE:-100000}"
BID_INCREMENT="${BID_INCREMENT:-5000}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-autique-local-mysql}"
# 하드코딩된 기본값을 두지 않는다 - docker-compose.local.yml의 MYSQL_ROOT_PASSWORD와 같은 값을
# 그대로 넘겨야 한다(그쪽도 환경변수로 오버라이드 가능하므로 여기서 기본값을 추정하지 않는다).
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD가 설정되지 않았습니다 - docker-compose.local.yml을 띄울 때 쓴 것과 같은 값을 넘기세요(기본으로 띄웠다면 그 compose 파일의 기본값 참고).}"
# IntelliJ Run Configuration의 "Save console output to file" 경로와 반드시 같아야 한다.
# 파일이 없으면 로그 관련 산출물은 전부 N/A로만 남고 나머지 관측/실행은 그대로 진행된다.
BACKEND_LOG_FILE="${BACKEND_LOG_FILE:-results/backend-live.log}"
# causal diagnostic 전용(기본 0초 = 기존 burst와 100% 동일, 동작 무변경). hot-auction-bid.js로
# 그대로 전달되어 VU별 요청 시작 시각을 [0, SPREAD_SECONDS]에 균등 분산시킨다 - "순간 TCP
# accept/backlog 압력" 가설을 검증하기 위한 것으로, production/Tomcat/Hikari 설정은 무관하다.
SPREAD_SECONDS="${SPREAD_SECONDS:-0}"
# baseline 파일명(vu{N}-run{n})과 겹치지 않게 별도 태그를 붙이고 싶을 때만 설정한다(예:
# RUN_LABEL=spread). 기본은 빈 문자열 - 기존 run_tag 형식과 100% 동일하게 유지된다.
RUN_LABEL="${RUN_LABEL:-}"

if [ -z "${LOCAL_DB_PASSWORD:-}" ]; then
  echo "LOCAL_DB_PASSWORD가 설정되지 않았습니다 - 매 run마다 fresh auction을 만들려면 필요합니다." >&2
  exit 1
fi

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 CLI를 찾을 수 없습니다. https://k6.io/docs/get-started/installation/ 참고." >&2
  exit 1
fi

if [ ! -f "data/hot-auction-users.json" ]; then
  echo "data/hot-auction-users.json이 없습니다. 먼저 HotAuctionUserSeeder를 1회 실행하세요:" >&2
  echo "  cd ../../backend && LOCAL_DB_PASSWORD=... ./gradlew.bat test --tests \"com.vintic.backend.loadtest.HotAuctionUserSeeder\"" >&2
  exit 1
fi

mkdir -p results
IFS=',' read -ra STAGE_LIST <<< "$STAGES"

hikari_metric() {
  curl -s "${BASE_URL}/actuator/metrics/hikaricp.connections.$1" --max-time 2 \
    | node -e "let d='';process.stdin.on('data',c=>d+=c);process.stdin.on('end',()=>{try{const j=JSON.parse(d);console.log(j.measurements[0].value);}catch(e){console.log('');}})" 2>/dev/null
}

innodb_row_lock_status() {
  docker exec "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e \
    "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_row_lock_time_avg','Innodb_row_lock_time_max');" \
    2>/dev/null
}

# events_statements_history_long consumer는 이 프로젝트 MySQL 컨테이너 기본값이 OFF다(용량 10000
# rows짜리 global ring buffer라 상시 켜두면 다른 워크로드까지 섞인다) - run 시작 직전에만 켜고,
# 그 직전까지 쌓인 내용을 TRUNCATE해 이번 run 전용으로 비운다. 둘 다 런타임 SET/TRUNCATE일 뿐
# my.cnf 등 영구 설정을 바꾸지 않는다 - 서버 재기동하면 저절로 원래 상태(OFF)로 돌아간다.
perf_schema_prepare() {
  docker exec "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e \
    "UPDATE performance_schema.setup_consumers SET ENABLED='YES' WHERE NAME='events_statements_history_long'; TRUNCATE TABLE performance_schema.events_statements_history_long;" \
    2>/dev/null || true
}

# ERROR_NUMBER \t SUM_ERROR_RAISED (1205=ER_LOCK_WAIT_TIMEOUT, 1213=ER_LOCK_DEADLOCK) - 서버
# 시작 이후 누적값이라 run 전후 스냅샷의 델타만 의미가 있다.
error_summary_snapshot() {
  docker exec "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e \
    "SELECT ERROR_NUMBER, SUM_ERROR_RAISED FROM performance_schema.events_errors_summary_global_by_error WHERE ERROR_NUMBER IN (1205,1213);" \
    2>/dev/null
}

# 이번 run 동안의 Auction PESSIMISTIC_WRITE 획득(AuctionRepository.findByIdForUpdate() -
# "from auctions ... for update") 각각에 대해, 같은 THREAD_ID(=같은 물리 커넥션, HikariCP는
# 커넥션을 동시에 공유하지 않으므로 한 시점엔 한 트랜잭션만 처리한다)에서 그 다음에 나온
# COMMIT/ROLLBACK의 TIMER_END를 짝지어 뽑는다. 못 찾으면 NULL - lockhold.js가 uncorrelated로만
# 세고 hold time을 추정하지 않는다.
#
# "from auctions " 조건이 반드시 필요하다: BidCommandService.executeManualBidOnLoadedAuction()이
# 성공한 bid마다 곧이어 auto_bid_settings에도 PESSIMISTIC_WRITE SELECT(cancelOwnActiveAutoBidIfPresent
# 등)를 날리는데, 그 SQL_TEXT에도 "for update"가 그대로 들어있어 테이블 조건 없이 필터링하면
# 완전히 다른(대개 훨씬 짧은, 결과 0건인) 락의 hold time이 Auction 락 표본에 섞여 든다 -
# 실제로 검증 중 발견해 고쳤다.
#
# THREAD_ID별로 EVENT_ID 다음 COMMIT/ROLLBACK을 찾는 상관 서브쿼리(구버전)는 150 VU를 1.5초에
# 걸쳐 분산시킨 run에서 7분 넘게 멈춘 걸 실제로 겪었다 - run 시간이 길어질수록(도커 헬스체크
# 등 배경 트래픽까지 누적되어 events_statements_history_long이 수천 행으로 불어나면)
# THREAD_ID당 반복 서브쿼리 비용이 감당이 안 됐다. 그래서 MySQL에서는 "LOCK/END 표시가 붙은
# 행만" 평평하게 뽑고(단순 스캔, 서브쿼리 없음), THREAD_ID 안에서 LOCK -> 다음 END를 짝짓는
# 실제 페어링은 observability.js(Node)에서 O(n)으로 한다.
lockhold_raw_dump() {
  docker exec "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e \
    "SELECT THREAD_ID, EVENT_ID, TIMER_END,
       IF(EVENT_NAME = 'statement/sql/select' AND LOWER(SQL_TEXT) LIKE '%from auctions %' AND LOWER(SQL_TEXT) LIKE '%for update%', 'LOCK',
          IF(EVENT_NAME IN ('statement/sql/commit', 'statement/sql/rollback'), 'END', NULL)) AS kind
     FROM performance_schema.events_statements_history_long
     HAVING kind IS NOT NULL
     ORDER BY THREAD_ID, EVENT_ID;" \
    2>/dev/null
}

for stage in "${STAGE_LIST[@]}"; do
  for run in $(seq 1 "$REPEATS"); do
    run_tag="vu${stage}${RUN_LABEL:+-$RUN_LABEL}-run${run}"
    summary_file="results/summary-${run_tag}.json"
    echo "=== [${run_tag}] 1/5 fresh auction 시딩 ==="
    (cd ../../backend && LOCAL_DB_PASSWORD="$LOCAL_DB_PASSWORD" ./gradlew.bat test \
      --tests "com.vintic.backend.loadtest.HotAuctionRoundSeeder" \
      -DstartPrice="$START_PRICE" -DbidIncrement="$BID_INCREMENT" --rerun)
    cp data/hot-auction-seed.json "results/seed-${run_tag}.json"

    echo "=== [${run_tag}] 2/5 k6 실행 (VUS=${stage}, SPREAD_SECONDS=${SPREAD_SECONDS}) - Hikari/InnoDB/perf_schema/에러카운터 관측 포함 ==="
    hikari_max_file="/tmp/hikari-poll-${run_tag}.txt"
    lockhold_tsv_file="/tmp/lockhold-${run_tag}.tsv"
    : > "$hikari_max_file"
    innodb_before="$(innodb_row_lock_status)"
    errors_before="$(error_summary_snapshot)"
    configured_max="$(hikari_metric max)"
    perf_schema_prepare

    if [ -f "$BACKEND_LOG_FILE" ]; then
      log_offset="$(wc -l < "$BACKEND_LOG_FILE" | tr -d ' ')"
    else
      log_offset=""
      echo "  (경고) BACKEND_LOG_FILE(${BACKEND_LOG_FILE})이 없음 - 이번 run의 서버 로그/rootcause는 N/A로 남습니다."
    fi

    # k6가 도는 동안 0.2초 간격으로 active/pending을 폴링해 파일에 append한다 - k6 종료와 함께 kill.
    (
      while true; do
        a="$(hikari_metric active)"; p="$(hikari_metric pending)"
        echo "${a:-NaN} ${p:-NaN}" >> "$hikari_max_file"
        sleep 0.2
      done
    ) &
    poll_pid=$!

    VUS="$stage" \
    UNRELATED_VUS="$UNRELATED_VUS" \
    BASE_URL="$BASE_URL" \
    RUN_TAG="$run_tag" \
    SPREAD_SECONDS="$SPREAD_SECONDS" \
      k6 run --summary-export "$summary_file" hot-auction-bid.js 2>&1 | tee "results/k6-console-${run_tag}.log"

    kill "$poll_pid" 2>/dev/null || true
    wait "$poll_pid" 2>/dev/null || true
    innodb_after="$(innodb_row_lock_status)"
    errors_after="$(error_summary_snapshot)"
    lockhold_raw_dump > "$lockhold_tsv_file" 2>/dev/null || true

    hikari_active_max="$(awk '{if($1+0>m)m=$1+0}END{print m+0}' "$hikari_max_file")"
    hikari_pending_max="$(awk '{if($2+0>m)m=$2+0}END{print m+0}' "$hikari_max_file")"
    node -e "
      const fs = require('fs');
      const before = \`${innodb_before}\`.trim().split('\n').filter(Boolean).map(l=>l.split('\t'));
      const after = \`${innodb_after}\`.trim().split('\n').filter(Boolean).map(l=>l.split('\t'));
      const b = Object.fromEntries(before), a = Object.fromEntries(after);

      const errBeforeRaw = \`${errors_before}\`.trim();
      const errAfterRaw = \`${errors_after}\`.trim();
      const errBefore = errBeforeRaw ? Object.fromEntries(errBeforeRaw.split('\n').map(l=>l.split('\t'))) : null;
      const errAfter = errAfterRaw ? Object.fromEntries(errAfterRaw.split('\n').map(l=>l.split('\t'))) : null;
      const errDelta = (code) => (errBefore === null || errAfter === null)
        ? null
        : (Number(errAfter[code]||0) - Number(errBefore[code]||0));

      const out = {
        hikariActiveMax: ${hikari_active_max},
        hikariPendingMax: ${hikari_pending_max},
        hikariConfiguredMax: ${configured_max:-0},
        innodbRowLockWaitsDelta: (Number(a.Innodb_row_lock_waits||0) - Number(b.Innodb_row_lock_waits||0)),
        innodbRowLockTimeDeltaMs: (Number(a.Innodb_row_lock_time||0) - Number(b.Innodb_row_lock_time||0)),
        innodbRowLockTimeMaxMs: Number(a.Innodb_row_lock_time_max||0),
        // 1205=ER_LOCK_WAIT_TIMEOUT, 1213=ER_LOCK_DEADLOCK - null이면 두 스냅샷 중 하나가
        // docker exec 실패 등으로 비어 실제 측정에 실패했다는 뜻이다(0으로 추정하지 않음).
        mysqlLockWaitTimeout1205Delta: errDelta('1205'),
        mysqlDeadlock1213Delta: errDelta('1213')
      };
      fs.writeFileSync('results/observability-${run_tag}.json', JSON.stringify(out, null, 2));
      console.log('[observability]', JSON.stringify(out));
    "
    rm -f "$hikari_max_file"

    node observability.js lockhold "$lockhold_tsv_file" "results/lockhold-${run_tag}.json" "$run_tag"
    rm -f "$lockhold_tsv_file"

    echo "=== [${run_tag}] 3/5 invariant check ==="
    (cd ../../backend && LOCAL_DB_PASSWORD="$LOCAL_DB_PASSWORD" ./gradlew.bat test \
      --tests "com.vintic.backend.loadtest.HotAuctionInvariantCheck" --rerun) \
      | tee "results/invariant-${run_tag}.log"
    cp data/hot-auction-invariant-result.json "results/invariant-${run_tag}.json"

    echo "=== [${run_tag}] 4/5 backend-live.log 구간 보존 + rootcause 추출 ==="
    backend_log_slice="results/backend-log-${run_tag}.log"
    if [ -f "$BACKEND_LOG_FILE" ] && [ -n "$log_offset" ]; then
      tail -n +"$((log_offset + 1))" "$BACKEND_LOG_FILE" > "$backend_log_slice"
    else
      rm -f "$backend_log_slice"
    fi
    node observability.js rootcause "$backend_log_slice" "results/k6-console-${run_tag}.log" "results/rootcause-${run_tag}.md" "$run_tag" "$summary_file"

    echo "=== [${run_tag}] 5/5 완료 ==="
    echo
  done
done

echo "=== 모든 stage 완료 - 표 요약 ==="
node summarize.js results/summary-*.json
