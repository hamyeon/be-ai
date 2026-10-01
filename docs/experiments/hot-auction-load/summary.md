# hot-auction 부하 테스트 — 비관적 락 + Tomcat accept queue 실험 보고서

브랜치: `experiment/hot-auction-load`. production 코드(`backend/src/main/**`)와 Tomcat/Hikari/
`application*.yml` 설정은 이 실험 전체를 통틀어 한 번도 바꾸지 않았다(§6의 acceptCount
causal test만 예외 — 1회 실행 한정 환경변수, 상세는 §6 참고). 전체 raw 수치는
[`results.json`](./results.json)(기존 artifact만 통합, 새 측정/추정 없음), 재현 절차는
[`../../../loadtest/k6/README.md`](../../../loadtest/k6/README.md) 참고.

## 결론 (TL;DR)

1. 같은 Auction 하나에 동시 bidder가 몰리는 워크로드는 **100 VU까지는 system failure 0%**,
   **150 VU에서 이미 12.0~29.3%로 뛴다** — cliff는 100~150 VU 사이에서 시작된다(200 VU:
   36.0~44.5%).
2. 이 실패의 정체는 **DB row lock 경합이 아니라 TCP `connection refused`**다 — k6가 직접
   분류한 `res.error_code=1212`, 응답 지연 0.0ms(30초 Hikari pool 대기였다면 나올 수 없는
   값), 서버 애플리케이션 로그에 어떤 흔적도 없음(요청이 서버 코드에 도달조차 못함)으로
   확인했다. MySQL 레벨 lock wait timeout/deadlock(1205/1213) delta는 전 구간 0이었다.
3. **독립된 두 개의 단일 변수 실험**(burst 모양만 분산 / accept queue 크기만 변경)이 같은
   결론에 도달했다: burst를 1.5초로 분산하면 connection refused가 41건→0건으로, Tomcat
   `accept-count`를 100→300으로 올려도 41건→0건으로 소멸한다. **accept/backlog 계층이 150
   VU burst 실패의 주요 원인이라는 근거가 두 번 일관되게 나왔다.**
4. 단, accept queue를 늘리면 실패가 사라지는 대신 **p50이 314.5ms→1606.1ms로 늘고 business
   rejection이 68.0%→98.7%로 증가**한다 — 같은 row에 150개가 몰리는 워크로드의 근본적인
   처리량 한계 자체가 없어진 건 아니고, 실패의 "형태"가 바뀐 것이다.
5. correctness(가격/승자 정합성, lost update, idempotency 중복, 마감 후 입찰, price reversal)는
   **이번 실험 전 구간에서 위반 0건**이다.

## 1. 실험 목적 및 환경

같은 LIVE Auction 하나에 동시 bidder가 몰릴 때, production의 비관적 락
(`AuctionRepository.findByIdForUpdate()` + `@Lock(PESSIMISTIC_WRITE)`, `BidCommandService`)
구조가 어디까지 버티는지 실제 HTTP 경로(k6 → Spring API → HikariCP → MySQL)로 측정했다.

- **환경**: 로컬 `docker-compose.local.yml`의 `autique-local-mysql`(MySQL 8.4)/
  `autique-local-redis` + IntelliJ로 기동한 `BackendApplication`(local 프로필, :8080,
  Spring Boot 3.5.14, tomcat-embed-core 10.1.54).
- **harness**: `loadtest/k6/hot-auction-bid.js`(k6 시나리오) +
  `loadtest/k6/run-stages.sh`(stage × repeat 오케스트레이션, 매 run마다 fresh auction 시딩,
  Hikari/InnoDB/MySQL 에러카운터/lock hold time 관측) +
  `loadtest/k6/observability.js`(lock hold time 계산, 서버 로그 원인 추출) +
  `loadtest/k6/summarize.js`(비교표 출력) +
  `backend/src/test/java/.../loadtest/{HotAuctionUserSeeder,HotAuctionRoundSeeder,
  HotAuctionInvariantCheck}.java`(유저 풀/fresh auction 시딩, post-state invariant 검증).
- **워크로드**: 매 run마다 새 hot auction 1개 + 무관한 auction 1개(unrelated GET latency
  비교용)를 시딩하고, VU 수만큼의 서로 다른 bidder가 각자 고유 Idempotency-Key로 딱 1회씩
  동시 입찰한다.

## 2. Baseline 결과 (20 / 50 / 100 / 200 VU)

각 stage 3회 반복.

| VU | system failure 범위 | system failure 평균 | lockFail(40909) |
|---|---|---|---|
| 20 | 0% | 0% | 0 |
| 50 | 0% | 0% | 0 |
| 100 | 0% | 0% | 0 |
| 200 | 36.0% ~ 44.5% | 39.2% | 0 |

이 시점에는 200 VU에서 실패가 나지만 `lockFail(40909)=0`, Hikari pending도 관측되지 않아
"비관적 락이 원인"이라고 볼 근거가 없었다. 100~200 VU 사이 어딘가에서 cliff가 시작된다는
가설로 150 VU를 조사했다(§3).

## 3. 100~150 VU Failure Cliff

150 VU를 3회 반복한 결과:

| run | success | business rejection | system failure | p50/p95/p99(ms) |
|---|---|---|---|---|
| baseline-run1 | 4 | 128 | 18 (12.0%) | 487.7/888.5/929.9 |
| baseline-run2 | 5 | 101 | 44 (29.3%) | 279.5/664.1/699.1 |
| baseline-run3 | 6 | 104 | 40 (26.7%) | 339.3/758.2/791.7 |

`lockFail(40909)=0`, correctness violation 0건(전 3회). **cliff는 100~150 VU 사이에서 이미
시작된다**(0% → 12.0~29.3%) — 200 VU에서 갑자기 나타나는 현상이 아니었다.

## 4. Connection Refused 원인 분석

150 VU에서 발생하는 system failure의 실제 원인을 다음 순서로 좁혔다(전부 확인된 사실,
추정 아님):

1. **전부 `status=0`** — k6가 HTTP 응답 자체를 못 받았다.
2. **DB lock 문제가 아니다** — `lockFail(40909)=0`, MySQL
   `events_errors_summary_global_by_error`의 1205(lock wait timeout)/1213(deadlock) delta가
   **0**(관측 가능했던 전 구간). "비관적 락이 터졌다"는 표현은 근거가 없어 쓰지 않았다.
3. **HikariCP pool 대기(connectionTimeout=30000ms, JMX 라이브 조회로 확인)가 아니다** — k6의
   `res.error_code`를 직접 로깅해 확인한 결과 실패 요청의 `durationMs`가 전부 **0.0ms**
   (min=med=max). 30초 근처였다면 pool 대기가 맞았겠지만 즉시 실패였다.
4. **`res.error_code`가 원인을 직접 특정한다**: 150 VU burst 진단 run에서 41/41건 전부
   `errorCodeK6=1212 "dial: connection refused"`.
5. **서버 애플리케이션 로그에 이 실패들의 흔적이 전혀 없다**(±1000ms 시간대 상관분석
   0/41 매치) — `GlobalExceptionHandler`의 어떤 핸들러도 이 요청까지 도달하지 못했다는
   뜻이다. (40909 핸들러는 원래 로그를 남기지 않는 것도 소스로 확인했으므로, 이 결론은
   "로그가 없어서 원인불명"이 아니라 "요청이 애초에 서버 애플리케이션 코드에 도달하지
   않았다"는 적극적 증거다.)
6. **Tomcat/HikariCP의 effective 설정을 실제 값으로 확인**했다(JMX 라이브 조회 + 실제 로드된
   jar 바이트코드 분석 — 추정이 아니라 이 프로젝트가 쓰는 정확한 버전 기준):
   - Tomcat(override 없음 확인됨): `maxThreads=200`, `acceptCount=100`,
     `maxConnections=8192`, `connectionTimeout=20000ms`(Tomcat 자체 기본값 — Spring Boot가
     override하지 않음. 흔히 아는 "60초"가 아니다).
   - HikariCP(JMX 라이브 값): `MaximumPoolSize=10`, `ConnectionTimeout=30000ms`,
     `MinimumIdle=10`.

## 5. Burst vs Spread 1.5초 분산 진단

같은 150 requests, 같은 auction, distinct bidder, unique idempotency key 조건을 그대로 두고
**요청 시작 시각만** `[0, 1.5초]`에 균등 분산시켰다(`SPREAD_SECONDS=1.5`, k6 스크립트 레벨의
`sleep()`만 추가 — production/Tomcat/Hikari 설정 무변경).

| | Burst (`vu150-burst-run1`) | Spread 1.5s (`vu150-spread-run1`) |
|---|---|---|
| success | 7 (4.7%) | 130 (86.7%) |
| system failure | 41 (27.3%) | **0 (0.0%)** |
| connection refused | 41/41 | 0 |
| InnoDB row lock waits delta | 108 | 149 |
| correctness | 위반 없음 | 위반 없음 |

connection refused가 burst→spread 전환만으로 완전히 소멸했다 → **"순간 TCP connection
burst/accept queue 계층이 유력"**하다고 기록한다. 단, 이 실험만으로는 Tomcat `acceptCount`
자체가 원인인지, OS 레벨 backlog 등 다른 accept 경로 요인인지 구분하지 못했다(§6에서
`acceptCount`만 단독으로 바꿔 확인).

## 6. acceptCount 단독 변경 Causal Test

burst 모양은 원래대로 되돌리고(`SPREAD_SECONDS=0`), **IntelliJ Run Configuration에
환경변수 `SERVER_TOMCAT_ACCEPT_COUNT=300`을 설정하고 재기동한 뒤** 같은 150 VU burst를
1회 실행했다. `application*.yml`은 수정하지 않았고, Hikari/maxThreads/maxConnections 등
다른 설정은 그대로 뒀다.

| | Burst, acceptCount=100 (`vu150-burst-run1`) | Burst, acceptCount=300 (`vu150-acceptcount300-run1`) |
|---|---|---|
| success | 7 (4.7%) | 2 (1.3%) |
| business rejection | 102 (68.0%) | 148 (98.7%) |
| system failure | 41 (27.3%) | **0 (0.0%)** |
| connection refused(1212) | 41/41 | 0 |
| p50/p95/p99(ms) | 314.5/649.8/682.9 | **1606.1/1979.2/2013.6** |
| Hikari active/pending max | 8/0 | 10/14 |
| MySQL 1205/1213 delta | 0/0 | 0/0 |
| InnoDB row lock waits delta | 108 | 149 |
| lockHold avg/p95(n) | 6.8/15.4ms (n=109) | 7.6/11.3ms (n=150, 전량 correlate) |
| correctness | 위반 없음 | 위반 없음 |

acceptCount 100→300 단독 변경만으로 connection refused가 **41건 → 0건으로 소멸**했다.
`lockHold`의 상관 성공 건수(n)가 109→150(=total 전량)으로 늘어난 것도 "이번엔 150건 전부가
DB의 Auction row lock 시도까지 도달했다"는 걸 직접 뒷받침한다. §5(burst→spread)와 이번
§6(acceptCount 단독 변경)은 서로 다른 변수를 바꾼 독립된 실험인데 같은 결론(accept/backlog
계층이 주요 원인)에 도달했다.

부작용: p50이 314.5ms→1606.1ms로, business rejection이 68.0%→98.7%로 늘었다 — 이전엔 TCP
단에서 즉사하던 요청들이 이제 전부 DB까지 도달해 같은 Auction row를 놓고 순차 경합하면서
그 대기시간이 응답시간에 그대로 반영된 것으로 해석한다. 같은 row에 150개가 몰리는 워크로드
자체의 처리량 한계가 없어진 게 아니라, 실패의 형태가 connection refused에서 latency 증가/
business rejection 증가로 바뀐 것이다.

**한계**: 이 프로젝트의 Tomcat은 `server.tomcat.mbeanregistry.enabled=false`(기본값)라 JMX로
살아있는 acceptCount 값을 직접 재조회(read-back)할 수 없었다 — `SERVER_TOMCAT_ACCEPT_COUNT=300`
환경변수를 설정하고 재기동해 실행했다는 절차만 기록하며, Tomcat 런타임에 실제로 반영된
수치를 재조회로 재확인하지는 못했다.

## 7. Correctness

이번 실험(baseline 20/50/100/200/150 VU + burst/spread/acceptCount 진단) 전 구간에서
`HotAuctionInvariantCheck`(PRICE_MISMATCH, WINNER_MISMATCH, LOST_UPDATE, PRICE_REVERSAL,
BID_AFTER_END_AT, IDEMPOTENCY_DUPLICATE_OR_MISSING) 위반이 **0건**이었다(20/50/100 VU의
근거 수준에 대해서는 §8 한계 참고). `persistedBidCount == completedIdempotencyClaims`도
모든 run에서 정확히 일치했다 — 멱등성 중복/누락 없음.

## 8. 한계

- **20/50/100 VU는 compact invariant artifact가 남아있지 않다.** 이 세 stage의 run은
  compact invariant artifact 저장 기능이 추가되기 전에 실행됐다 — `HotAuctionInvariantCheck`는
  실행됐고 Gradle test task가 `BUILD SUCCESSFUL`로 끝났으니 `assertThat(violations).isEmpty()`가
  통과했다는 뜻이지만, 이 성공 기록만으로는 현재 다른 run들(150/200 VU)이 갖고 있는
  machine-readable invariant 결과와 같은 수준의 근거로 취급하지 않는다.
- **`vu150-spread-run1`의 lockHold는 N/A다.** run 시간이 길어지면(spread run처럼 wall-clock이
  늘어나면) `events_statements_history_long`에 배경 트래픽(도커 헬스체크 등)까지 누적되어
  원래 쓰던 THREAD_ID별 상관 서브쿼리가 이 run에서 멈췄다 — 안전하게 쿼리를 중단시키고,
  이후 상관 서브쿼리 없이 평평하게 스캔 후 애플리케이션 레벨에서 페어링하도록 고쳤지만
  (같은 크기 테이블에서 0.5초로 확인), 이 run의 원본 데이터는 이미 유실되어 추정하지 않고
  N/A로 남겼다.
- **acceptCount=300 재조회 불가** — §6 한계 문단 참고.
- 150 VU의 burst/spread/acceptCount 진단은 각각 **1회씩만 실행**했다(baseline만 3회 반복).
  반복 간 변동폭(§3에서 12.0~29.3%로 confirmed)을 고려하면 이 진단 run들의 정확한 수치는
  ±변동이 있을 수 있으나, connection refused 유무(41건 vs 0건)처럼 이진적으로 뚜렷한
  결과는 1회로도 재현성 있는 신호로 판단했다.
- 175/200/300 VU 등 더 높은 VU에서의 acceptCount 효과는 실행하지 않았다(범위 밖).

## 9. 최종 결론

100~150 VU 사이에서 시작되는 failure cliff의 정체는 DB 비관적 락 경합이 아니라 **TCP
accept/backlog 계층의 순간적인 포화**였다. 서로 다른 변수를 통제한 두 개의 독립적인
causal test(burst 모양 분산 / acceptCount 단독 증가)가 모두 이 결론을 뒷받침했고, 둘 다
정확히 같은 실패 신호(`connection refused`, 41/41)를 완전히 제거했다. 다만 accept queue를
늘리는 것은 "실패를 없애는 것"이 아니라 "실패를 latency/business rejection으로 이전하는
것"에 가깝다 — 같은 Auction row에 대한 순차 경합이라는 근본 제약은 그대로다.
production에 `server.tomcat.accept-count`를 실제로 올릴지, 그 latency trade-off를 감수할
가치가 있는지는 이 실험 결과만으로 결정하지 않는다 — 별도 논의가 필요하다.
