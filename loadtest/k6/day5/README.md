# Day5 4단계 k6 스크립트

기존 `loadtest/k6/`(hot-auction-load 산출물, `data/`·`results/`)와 같은 트리 안에 Day5 전용
파일을 이 `day5/` 하위 디렉터리로 구분해 추가했다. 공용 helper는 전부 `common.js` 한 곳에
있고, 세 시나리오는 그 모듈만 import한다.

## 공통 환경변수 (모든 스크립트 공통)

| 이름 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `BASE_URL` | 아니오 | 스크립트별 기본값(아래 참고) | 대상 서버 |
| `VUs` (`VUS`도 인식) | 아니오 | `1` | 동시 가상 유저 수 |
| `duration` (`DURATION`도 인식) | 아니오 | `30s` | constant-vus 시나리오의 실행 시간 |

## 시나리오 1: 경매 조회 (`auction-browse.js`)

실제 `GET /api/auctions/{auctionId}`(비로그인 허용, `AuctionController#55`)를 반복 조회한다.

| 이름 | 필수 | 설명 |
|---|---|---|
| `AUCTION_ID` | **예** | 조회할 실제 경매 ID. 이 브랜치에는 seeder가 없으므로 로컬 DB에 이미 존재하는 id를 직접 지정한다(시나리오 2로 만든 `hot-auction-seed.json`의 auctionId를 재사용해도 된다). |

```bash
cd loadtest/k6/day5
BASE_URL=http://localhost:8080 VUs=1 duration=10s AUCTION_ID=48 k6 run auction-browse.js
```

실제 로컬(local 프로필, :8080) 대상 VUs=1/5s 실행으로 검증 완료(383 iterations, 100% 성공).

## 시나리오 2: 동일 경매 입찰 (`auction-bid.js`)

`experiment/hot-auction-load` 브랜치의 `loadtest/k6/hot-auction-bid.js`(이 브랜치에는 파일
자체가 없음, `git show experiment/hot-auction-load:loadtest/k6/hot-auction-bid.js`로 확인)와
같은 endpoint/payload/인증/에러 분류를 그대로 쓴다. `per-vu-iterations`(VU=`VUs`,
iterations=1)로 모든 VU가 **같은** `hot-auction-seed.json`의 `hotAuction.auctionId`에 각자
1회씩 입찰한다.

사전 준비(README 최상위 문서의 "인증 경계"와 동일 - local 프로필 전용):

1. 로컬 Spring Boot 앱이 **local 프로필**로 떠 있어야 한다(:8080).
2. `loadtest/k6/data/hot-auction-users.json`이 이미 있다(이 저장소에 기존 산출물로 존재 -
   User는 상태가 없어 재사용 가능, 실제로 DB에서 id 4~8이 여전히 유효함을 확인함). 없다면
   `experiment/hot-auction-load` 브랜치의 `HotAuctionUserSeeder`를 참고해야 한다(이번
   단계에서는 새로 만들지 않음 - 기존 풀이 이미 유효했다).
3. **매 실행 전에 fresh auction이 필요하다**(같은 auction 재사용 시 `currentPrice` 누적으로
   계산이 어긋남 - 기존 README와 동일한 이유). 이 브랜치로 포팅한
   `HotAuctionRoundSeeder`(`backend/src/test/java/com/vintic/backend/loadtest/
   HotAuctionRoundSeeder.java`, 로직 무변경 포팅)를 먼저 실행한다:

   ```bash
   cd backend
   LOCAL_DB_PASSWORD=<로컬 MySQL 비밀번호> ./gradlew.bat test \
     --tests "com.vintic.backend.loadtest.HotAuctionRoundSeeder"
   ```

| 이름 | 필수 | 설명 |
|---|---|---|
| `VUs` | 아니오(기본 1) | 동시 입찰자 수 = 실제 bidder 수 |
| `MAX_DURATION` | 아니오(기본 2m) | `per-vu-iterations`의 최대 허용 시간(이 executor는 `duration` 개념이 없다 - `duration` env는 이 시나리오에서 쓰이지 않는다) |

```bash
cd loadtest/k6/day5
BASE_URL=http://localhost:8080 VUs=8 k6 run auction-bid.js
```

실제 로컬 대상 8 VU 실행으로 검증 완료(8/8 iterations, bid_success=4, bid_business_rejection=4,
system/lock failure 0건 - 높은 금액이 먼저 처리되며 낮은 금액이 정상적으로 거절되는 기존
문서화된 워크로드 그대로).

## 시나리오 3: AI 분석 E2E (`ai-analysis-e2e.js`)

`docker-compose.experiment.yml` 스택(API 프로필 `local,experiment-api`) 대상. presigned URL
발급 → 실제 S3(LocalStack) PUT → 실제 비동기 분석 제출 → bounded polling → `COMPLETED`까지
측정한다. Processor는 experiment 환경의 `FakeAnalysisProcessor`를 쓴다 - 실제 외부 AI를
호출하지 않는다.

사전 준비:

```bash
cd backend
docker compose -f docker-compose.experiment.yml up -d --build
```

X-User-Id로 쓸 실제 `users` row가 `vintic_experiment` DB에 있어야 한다(Day5 3단계에서 이미
910001로 seed됨 - 다른 값을 쓰려면 그 id가 존재해야 한다).

| 이름 | 필수 | 설명 |
|---|---|---|
| `USER_ID` | 아니오(기본 `910001`) | 실제 존재해야 하는 users row id |
| `POLL_MAX_ATTEMPTS` | 아니오(기본 40) | 상태 polling 최대 시도 횟수 |
| `POLL_INTERVAL_SECONDS` | 아니오(기본 3) | polling 간격(초) |

```bash
cd loadtest/k6/day5
BASE_URL=http://localhost:8090 VUs=1 duration=30s USER_ID=910001 k6 run ai-analysis-e2e.js
```

실제 experiment 스택 대상 VUs=1/20s 실행으로 검증 완료(2 iterations, 100% 성공,
`ai_analysis_e2e_duration` p95≈12.1s - `analysis.processor.fake.delay-ms`(11164ms) 기준과
일치).

## 이번 단계에서 하지 않은 것

- 대규모 VU/장시간 부하 실행 (부하 수치 튜닝이나 성능 결론 도출이 목적이 아니라 실행 가능한
  골격 작성이 목적 - 지시 범위 그대로).
- `HotAuctionInvariantCheck`(입찰 후 DB 정합성 검증) 포팅 - 이번 단계 지시에 없어 최소 범위로
  `HotAuctionRoundSeeder`만 옮겼다.
- 기존 `loadtest/k6/hot-auction-bid.js`/`observability.js`/`summarize.js`/`run-stages.sh`/
  `smoke-test.sh` 자체를 이 브랜치로 옮기지 않았다 - `experiment/hot-auction-load` 브랜치에만
  있고 이 브랜치에는 없다(git status로 확인). 필요하면 그 브랜치에서 별도로 머지/포팅 여부를
  결정해야 한다(이번 단계 범위 밖).
