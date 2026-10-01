// hot-auction 부하 테스트: 같은 LIVE Auction 하나에 서로 다른 bidder가 거의 동시에 1회씩
// 입찰할 때 정합성/latency/실패 종류/다른 API 영향을 관찰한다.
//
// 실행 전제:
//   1) 로컬 Spring Boot 앱이 local 프로필로 떠 있어야 한다(예: IntelliJ, :8080).
//   2) HotAuctionLoadSeeder(backend/src/test/java/.../loadtest/HotAuctionLoadSeeder.java)를
//      먼저 1회 실행해 data/hot-auction-seed.json을 만들어야 한다.
//
// 인증: 이 프로젝트의 local 프로필은 JWT가 아니라 X-User-Id 헤더 + MockAuthInterceptor를 쓴다
// (dev/prod의 JWT는 Kakao 실제 OAuth 없이는 발급할 수 없어 스크립트로 재현 불가 - README 참고).
// seed.json의 각 bidderId는 실제 users row를 가리키므로 X-User-Id 자체가 이 프로젝트의 실제
// 인증 경로를 그대로 통과한다.
//
// executor: hot_bid는 per-vu-iterations(VU=STAGE_VUS, iterations=1)로 "각 VU=서로 다른
// bidder 1명, 1회만 입찰"인 동시 burst를 만든다. unrelated_read는 hot auction과 무관한 다른
// Auction의 상세조회를 낮은 constant 부하로 같은 시간대에 돌려, hot auction의 row lock 경쟁이
// 관계없는 조회 latency에 번지는지 비교한다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const seed = JSON.parse(open('./data/hot-auction-seed.json'));

const BASE_URL = __ENV.BASE_URL || seed.baseUrl || 'http://localhost:8080';
// 한 stage의 VU 수 - 8/20/50/100/200 중 하나를 이 값으로 넘겨 한 번에 한 stage만 돌린다.
// 여러 stage/여러 반복은 run-stages.sh가 이 스크립트를 그 횟수만큼 서로 다른 VUS로 호출한다.
const STAGE_VUS = parseInt(__ENV.VUS || '8', 10);
const UNRELATED_VUS = parseInt(__ENV.UNRELATED_VUS || '2', 10);
const UNRELATED_DURATION = __ENV.UNRELATED_DURATION || '20s';
const RUN_TAG = __ENV.RUN_TAG || `local-${Date.now()}`;
// causal diagnostic 전용(기본 0 = 기존 burst 그대로, 동작 무변경). 0보다 크면 각 VU의 요청
// 시작을 VU번호에 비례해 [0, SPREAD_SECONDS] 구간에 균등 분산시킨다 - "총 150 requests,
// 같은 auction, distinct bidder, unique idempotency key"는 그대로 두고 TCP accept 시점의
// 순간 동시성만 낮춰서, "순간 connection burst" 가설과 "그 외 원인"을 구분하기 위함이다.
// production/Tomcat/Hikari 설정은 건드리지 않는다 - k6 스크립트에서 sleep()만 추가한다.
const SPREAD_SECONDS = parseFloat(__ENV.SPREAD_SECONDS || '0');

// #45 GlobalExceptionHandler 기준 정상 도메인 거절(409) 코드. BID_AMOUNT_TOO_LOW(40904) 포함 -
// 높은 금액이 먼저 처리되면 이후 낮은 금액이 이 코드로 거절되는 것은 이 부하 테스트에서 정상이다.
// 40909(PessimisticLockingFailureException - CannotAcquireLockException/DeadlockLoserDataAccessException)는
// 의도적으로 여기 포함하지 않는다 - DB lock contention은 system failure로 분류해야 한다.
const BUSINESS_REJECTION_CODES = new Set([40901, 40902, 40903, 40904, 40905, 40913]);
const LOCK_FAILURE_CODE = 40909;

export const options = {
  scenarios: {
    hot_bid: {
      executor: 'per-vu-iterations',
      vus: STAGE_VUS,
      iterations: 1,
      maxDuration: __ENV.MAX_DURATION || '2m',
      exec: 'hotBid',
    },
    unrelated_read: {
      executor: 'constant-vus',
      vus: UNRELATED_VUS,
      duration: UNRELATED_DURATION,
      exec: 'unrelatedRead',
      startTime: '0s',
    },
  },
  // p50/p95/p99을 기본 콘솔 요약과 --summary-export JSON에 그대로 포함시킨다.
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};

const bidSuccess = new Counter('bid_success');
const bidBusinessRejection = new Counter('bid_business_rejection');
const bidSystemFailure = new Counter('bid_system_failure');
const bidLockFailure = new Counter('bid_lock_failure'); // system failure의 부분집합 - DB lock 관련만 별도 카운트
const bidDuration = new Trend('bid_duration', true);

const unrelatedGetDuration = new Trend('unrelated_get_duration', true);
const unrelatedGetFailure = new Counter('unrelated_get_failure');

function classifyBidResponse(res) {
  if (res.status === 201) {
    return 'SUCCESS';
  }
  if (res.status === 0) {
    // k6가 응답 자체를 못 받음(connection refused/timeout) - Hikari pool 고갈이나 서버 다운 후보.
    return 'SYSTEM_FAILURE';
  }
  if (res.status >= 500) {
    return 'SYSTEM_FAILURE';
  }
  if (res.status === 409) {
    let code = null;
    try {
      code = JSON.parse(res.body).error.code;
    } catch (e) {
      return 'SYSTEM_FAILURE'; // 409인데 계약된 error.code 형식이 아니면 예상 못한 실패로 취급
    }
    if (code === LOCK_FAILURE_CODE) {
      return 'LOCK_FAILURE';
    }
    if (BUSINESS_REJECTION_CODES.has(code)) {
      return 'BUSINESS_REJECTION';
    }
    return 'SYSTEM_FAILURE'; // 목록에 없는 409는 예상 못한 실패로 보수적으로 분류
  }
  // 401/403/404/400 등은 이 부하 테스트가 올바르게 구성됐다면 나타나면 안 되는 상태다 -
  // 그래도 나오면 system failure로 묶어서 눈에 띄게 한다(조용히 성공/거절로 섞지 않는다).
  return 'SYSTEM_FAILURE';
}

// SYSTEM_FAILURE(LOCK_FAILURE 포함) 발생 시 아래를 전부 콘솔에 구조화 출력한다 -
// run-stages.sh가 k6 콘솔 출력을 results/k6-console-{tag}.log로 그대로 저장하고, 이후
// backend-live.log의 같은 시간대 ERROR/스택트레이스와 대조(best-effort, 정확한 요청 단위
// 매칭이 아니라 시간대 상관관계임을 결과에 명시)하는 데 쓴다.
//   - httpStatus: res.status (0이면 k6가 HTTP 응답 자체를 못 받았다는 뜻 - TCP/TLS/타임아웃 등)
//   - error/errorCodeK6: k6가 직접 분류한 net-level 실패 사유/코드(res.error, res.error_code) -
//     "connection reset by peer" "context deadline exceeded"(timeout) 등 status=0의 실제 원인을
//     구분하는 가장 직접적인 값이다. https://k6.io/docs/javascript-api/k6-http/response/ 참고.
//   - errorCodeApp: 우리 API의 JSON body error.code(있으면) - status=0일 때는 body 자체가 없다.
//   - url: 실제 요청 URL(재시도/리다이렉트 여부 확인용)
//   - durationMs: res.timings.duration - Hikari connectionTimeout(30s)처럼 긴 대기 후 실패했는지,
//     거의 즉시(0ms대) 실패했는지 구분하는 데 쓴다.
function logFailure(kind, res, vu, idempotencyKey) {
  let errorCodeApp = null;
  try {
    errorCodeApp = JSON.parse(res.body).error.code;
  } catch (e) {
    errorCodeApp = null;
  }
  console.log(
    `SYS_FAIL kind=${kind} ts=${new Date().toISOString()} vu=${vu} ` +
    `httpStatus=${res.status} error=${JSON.stringify(res.error)} errorCodeK6=${res.error_code} ` +
    `errorCodeApp=${errorCodeApp} url=${res.url} durationMs=${res.timings.duration} idem=${idempotencyKey}`
  );
}

export function hotBid() {
  const vu = __VU; // 1-indexed
  if (SPREAD_SECONDS > 0) {
    // VU=1 -> 0초, VU=STAGE_VUS -> SPREAD_SECONDS초에 걸쳐 균등 분산(랜덤 지터 아님 - 재현
    // 가능한 causal diagnostic이 목적이라 결정적으로 고른 간격을 쓴다).
    const delaySeconds = ((vu - 1) / Math.max(1, STAGE_VUS - 1)) * SPREAD_SECONDS;
    sleep(delaySeconds);
  }
  const bidderId = seed.hotAuction.bidderIds[(vu - 1) % seed.hotAuction.bidderIds.length];
  // base + increment * i (i=1..VU) - currentPrice가 계속 갱신돼도 increment의 배수 관계는
  // 유지되므로(Auction.placeManualBid의 배수 정렬 검증), 어느 순서로 처리되든 BidNotAligned는
  // 나지 않는다. 먼저 처리된 더 높은 금액 때문에 낮은 금액이 BID_AMOUNT_TOO_LOW(40904)로 거절되는
  // 것은 이 워크로드에서 정상이다.
  const amount = seed.hotAuction.startPrice + seed.hotAuction.bidIncrement * vu;
  const idempotencyKey = `k6-${RUN_TAG}-vu${vu}-${Date.now()}-${Math.random().toString(36).slice(2)}`;

  const res = http.post(
    `${BASE_URL}/api/auctions/${seed.hotAuction.auctionId}/bids`,
    JSON.stringify({ amount: amount }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-User-Id': String(bidderId),
        'Idempotency-Key': idempotencyKey,
      },
      tags: { name: 'hot_bid', endpoint: 'place_bid' },
    }
  );

  bidDuration.add(res.timings.duration);

  const outcome = classifyBidResponse(res);
  if (outcome === 'SUCCESS') {
    bidSuccess.add(1);
  } else if (outcome === 'BUSINESS_REJECTION') {
    bidBusinessRejection.add(1);
  } else if (outcome === 'LOCK_FAILURE') {
    bidLockFailure.add(1);
    bidSystemFailure.add(1);
    logFailure('LOCK_FAILURE', res, vu, idempotencyKey);
  } else {
    bidSystemFailure.add(1);
    logFailure('SYSTEM_FAILURE', res, vu, idempotencyKey);
  }

  check(res, {
    'bid response has a body': (r) => r.body !== null && r.body !== '',
  });
}

export function unrelatedRead() {
  const res = http.get(
    `${BASE_URL}/api/auctions/${seed.unrelatedAuction.auctionId}`,
    {
      headers: { 'X-User-Id': String(seed.unrelatedAuction.sellerId) },
      tags: { name: 'unrelated_get', endpoint: 'auction_detail' },
    }
  );
  unrelatedGetDuration.add(res.timings.duration);
  if (res.status !== 200) {
    unrelatedGetFailure.add(1);
  }
  sleep(1); // "일정한 낮은 부하" - 매 iteration마다 쉬지 않고 몰아치지 않는다.
}
