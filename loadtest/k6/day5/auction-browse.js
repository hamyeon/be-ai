// Day5 4단계 시나리오 1: 경매 상세 조회(읽기 전용, 비로그인도 가능한 실제 endpoint).
// AuctionController.getAuction(#55: 비로그인 접근 허용)을 그대로 쓴다 - 가상의 endpoint 없음.
//
// 실행 예:
//   BASE_URL=http://localhost:8080 VUs=1 duration=10s AUCTION_ID=48 k6 run auction-browse.js
//
// 필요 env:
//   BASE_URL(선택, 기본 http://localhost:8080), VUs(선택, 기본 1), duration(선택, 기본 30s)
//   AUCTION_ID(필수) - 조회할 실제 경매 ID. 이 브랜치에는 seeder가 없으므로(README 참고)
//     로컬 DB에 이미 존재하는 auction id를 직접 지정해야 한다. 없으면 auction-bid.js로
//     HotAuctionRoundSeeder를 먼저 1회 실행해 만든 hot-auction-seed.json의 auctionId를 써도 된다.
import http from 'k6/http';
import { check } from 'k6';
import { readCommonEnv, checkApiResponse, newE2eMetrics } from './common.js';

const env = readCommonEnv({ baseUrl: 'http://localhost:8080' });
const AUCTION_ID = __ENV.AUCTION_ID;

export const options = {
  scenarios: {
    auction_browse: {
      executor: 'constant-vus',
      vus: env.vus,
      duration: env.duration,
      exec: 'browse',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};

const metrics = newE2eMetrics('auction_browse');

export function browse() {
  if (!AUCTION_ID) {
    throw new Error('AUCTION_ID env가 필요합니다 - 조회할 실제 경매 ID를 지정하세요.');
  }
  const start = Date.now();
  const res = http.get(`${env.baseUrl}/api/auctions/${AUCTION_ID}`, {
    tags: { name: 'auction_detail' },
  });
  const ok = checkApiResponse(res, 200, 'auction_detail')
    && check(res, { 'auction_detail: data.auctionId present': (r) => {
      try { return JSON.parse(r.body).data.auctionId !== undefined; } catch (e) { return false; }
    }});
  metrics.duration.add(Date.now() - start);
  metrics.successRate.add(ok);
}
