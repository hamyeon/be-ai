// Day5 4단계 시나리오 2: 동일 LIVE 경매 하나에 서로 다른 bidder가 거의 동시에 1회씩 입찰한다.
// experiment/hot-auction-load 브랜치의 loadtest/k6/hot-auction-bid.js(git show로 확인, 이
// 브랜치에는 파일 자체가 없음)와 같은 endpoint/payload/인증/에러 분류 방식을 그대로 재사용한다 -
// production 동시성 코드(AuctionRepository/BidCommandService/Auction)는 이 스크립트를 위해
// 전혀 바꾸지 않았다.
//
// 사전 준비(README "인증 경계"와 동일 - local 프로필 전용):
//   1) 로컬 Spring Boot 앱이 local 프로필로 떠 있어야 한다(:8080).
//   2) hot-auction-users.json이 이미 있다(이 저장소에 기존 산출물로 존재, bidder pool은
//      상태가 없어 재사용 가능 - 실제로 DB에서 유효함을 확인함). 없으면 다른 branch의
//      HotAuctionUserSeeder를 참고해 만들어야 한다(이번 단계에서 새로 만들지 않음).
//   3) 매 run마다 fresh auction이 필요하다(같은 auction 재사용 시 currentPrice 누적으로 계산이
//      어긋남) - 이 브랜치로 포팅한 HotAuctionRoundSeeder를 먼저 실행한다:
//        cd backend
//        LOCAL_DB_PASSWORD=<로컬 MySQL 비밀번호> ./gradlew.bat test \
//          --tests "com.vintic.backend.loadtest.HotAuctionRoundSeeder"
//      -> loadtest/k6/data/hot-auction-seed.json이 새로 갱신된다.
//
// 실행 예:
//   BASE_URL=http://localhost:8080 VUs=8 duration=1s k6 run auction-bid.js
//   (executor가 per-vu-iterations라 duration은 maxDuration 상한으로만 쓰인다 - 아래 참고)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { readCommonEnv, authHeaders, checkApiResponse } from './common.js';

const seed = JSON.parse(open('../data/hot-auction-seed.json'));
const env = readCommonEnv({ baseUrl: seed.baseUrl || 'http://localhost:8080' });

// #45 GlobalExceptionHandler 기준 정상 도메인 거절(409) 코드 - hot-auction-bid.js와 동일.
const BUSINESS_REJECTION_CODES = new Set([40901, 40902, 40903, 40904, 40905, 40913]);
const LOCK_FAILURE_CODE = 40909;

export const options = {
  scenarios: {
    hot_bid: {
      executor: 'per-vu-iterations',
      vus: env.vus,
      iterations: 1,
      // duration env는 이 executor에서 "1회 반복의 최대 허용 시간" 상한으로 재해석한다 -
      // per-vu-iterations는 자체 duration 개념이 없다.
      maxDuration: __ENV.MAX_DURATION || '2m',
      exec: 'hotBid',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};

const bidSuccess = new Counter('bid_success');
const bidBusinessRejection = new Counter('bid_business_rejection');
const bidSystemFailure = new Counter('bid_system_failure');
const bidLockFailure = new Counter('bid_lock_failure');
const bidDuration = new Trend('bid_duration', true);

function classifyBidResponse(res) {
  if (res.status === 201) return 'SUCCESS';
  if (res.status === 0) return 'SYSTEM_FAILURE';
  if (res.status >= 500) return 'SYSTEM_FAILURE';
  if (res.status === 409) {
    let code = null;
    try {
      code = JSON.parse(res.body).error.code;
    } catch (e) {
      return 'SYSTEM_FAILURE';
    }
    if (code === LOCK_FAILURE_CODE) return 'LOCK_FAILURE';
    if (BUSINESS_REJECTION_CODES.has(code)) return 'BUSINESS_REJECTION';
    return 'SYSTEM_FAILURE';
  }
  return 'SYSTEM_FAILURE';
}

export function hotBid() {
  const vu = __VU;
  const bidderId = seed.hotAuction.bidderIds[(vu - 1) % seed.hotAuction.bidderIds.length];
  const amount = seed.hotAuction.startPrice + seed.hotAuction.bidIncrement * vu;
  const idempotencyKey = `k6-day5-vu${vu}-${__ITER}-${Math.random().toString(36).slice(2)}`;

  const res = http.post(
    `${env.baseUrl}/api/auctions/${seed.hotAuction.auctionId}/bids`,
    JSON.stringify({ amount: amount }),
    {
      headers: authHeaders(bidderId, { 'Idempotency-Key': idempotencyKey }),
      tags: { name: 'place_bid' },
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
  } else {
    bidSystemFailure.add(1);
  }

  check(res, { 'bid response has a body': (r) => r.body !== null && r.body !== '' });
}
