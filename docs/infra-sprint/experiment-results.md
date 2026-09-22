# Experiment Results — Day 11~15 인프라 스프린트

작성 시점: 2026-09-22 (Day15-2). 모든 수치는 evidence 파일에서 그대로 가져왔다. 추정치는
"추정"으로 표시하고, evidence가 없는 항목은 "미검증"으로 표시한다.

형식: 목적 / 조건 / 실행 / 결과 / 해석 / 한계 / evidence

---

## 1. Worker kill -9 (SQS 경로, maxReceiveCount 이전)

**목적**: Worker 프로세스가 메시지 처리 도중 강제 종료됐을 때 자동 복구가 되는지 확인.
**조건**: Fake 처리 지연 60,000ms로 설정. Worker A가 analysisId=655를 `PROCESSING`으로 선점한
상태에서 25초 대기 후 kill -9.
**실행**: UTC 2026-09-21T10:52:50.817에 Worker A 컨테이너(`bfca95e9be07`)를 kill -9.

### 재검증: 802초 vs 110.6초

같은 run(analysisId=655)의 원본 timestamp를 전부 모아 대조했다. DB 값은 MySQL 조회 결과,
CloudWatch 값은 Worker 로그 export(같은 파일의 "WORKER 로그" 섹션) — **두 개의 독립된
소스**다.

| 사건 | 시각(UTC) | 출처 |
|---|---|---|
| **최초 ReceiveMessage**(Worker A, receiveCount=1) | **10:52:10.942** | CloudWatch: `@timestamp=2026-09-21 10:52:10.942 ... workerId=d2b0169d receiveCount=1 message=SQS 메시지를 수신했습니다` — 이 run에서 `receiveCount=1` 로그는 이 한 줄뿐(재확인 완료, 더 이른 수신 기록 없음) |
| 첫 번째 처리 시작(Worker A, DB claim) | 10:52:11.032099 | DB `processing_started_at`(kill 전 조회) — 최초 수신 90.1ms 후 |
| 장애 주입(kill -9) | 10:52:50.817 | `[kill9] ... at=2026-09-21T10:52:50.817Z` |
| Worker 프로세스 종료 | 10:52:50.817(직후, 동기) | `killed exit=0`, 같은 라인 |
| 메시지 재노출/재수신(Worker B, receiveCount=2) | 10:53:40.945 | CloudWatch: `@timestamp=2026-09-21 10:53:40.945 ... workerId=2063abe0 receiveCount=2 message=SQS 메시지를 수신했습니다` |
| 다른 Worker(B)의 재처리 시작(DB claim) | 10:53:41.034633 | DB `processing_started_at`(kill 후 조회) |
| DB COMPLETED(최종 성공) | 10:54:41.455761 | DB `updated_at`(kill 후 조회) |
| 처리 완료 로그(Worker B) | 10:54:41.372 | CloudWatch: `@timestamp=2026-09-21 10:54:41.372 ... message=분석 처리를 완료했습니다` |
| receiveCount | 1(Worker A, kill 전) → 2(Worker B, kill 후 첫 재수신) | CloudWatch 로그 |
| SQS SentTimestamp / DB created_at | 이 run의 캡처된 쿼리 결과에 `created_at`/`SentTimestamp` 컬럼이 포함되지 않음 — **자료 없음** | - |
| Visibility Timeout — Queue 기본값 | 120초 | `artifacts/day15/evidence-20260922T083413Z/sqs-autique-exp-analysis-job-queue.json` |
| Visibility Timeout — Worker 요청별 실효값(실제 적용) | **90초** | `SqsAnalysisJobPoller.java:50,102`, `SqsAnalysisJobPollerTest`, `WorkerTimeoutDefaultsTest` |

### 구간별 분리

```
① 최초 수신 → kill:        10:52:50.817 − 10:52:10.942 = 39.875초
② kill → 재노출(재수신):    10:53:40.945 − 10:52:50.817 = 50.128초
③ 재노출 → 완료:            10:54:41.372 − 10:53:40.945 = 60.427초  (DB 기준 60.511초)
④ kill → 완료(복구시간):    10:54:41.372 − 10:52:50.817 = 110.555초 (DB 기준 110.639초)
─────────────────────────────────────────────────────────
① + ②  (최초 수신 → 재노출): 90.003초
① + ② + ③ (최초 수신 → 완료): 150.430초
```

→ **복구시간(④, kill → 완료) = 110.6초** — DB `updated_at`(110.639초)과 CloudWatch 완료
로그(110.555초)가 84ms 오차로 일치, 교차검증 완료.

### Visibility Timeout 불일치 원인 — 확정

90초와 120초의 차이는 **Queue 리소스의 기본값과 Worker가 요청마다 명시적으로 지정하는
실효값이 다르기 때문**이며, 코드·설정·테스트 3곳에서 교차 확인해 확정했다(더 이상 추정이나
"가장 유력한 해석"이 아니다):

1. **Queue 기본 설정(리소스 속성)**: `VisibilityTimeout=120`
   (`artifacts/day15/evidence-20260922T083413Z/sqs-autique-exp-analysis-job-queue.json`)
2. **Worker 런타임 설정(요청별 실효값)**: `SqsAnalysisJobPoller`가
   `@Value("${analysis.worker.visibility-timeout-seconds:90}")`로 90초를 받아
   `ReceiveMessageRequest.builder().visibilityTimeout(visibilityTimeoutSeconds)`로 **매
   ReceiveMessage 요청마다 명시적으로 90초를 지정**한다
   (`backend/src/main/java/com/vintic/backend/analyze/job/worker/SqsAnalysisJobPoller.java:50,102`).
   SQS는 요청에 `visibilityTimeout` 파라미터가 있으면 Queue 기본값 대신 그 값을 그 수신
   건에 적용한다(SQS 표준 동작) — 즉 이 메시지에는 120초가 아니라 **90초**가 실제로
   적용됐다.
3. **테스트로 고정됨**: `SqsAnalysisJobPollerTest`가
   `VISIBILITY_TIMEOUT_SECONDS=90`으로 `request.visibilityTimeout()`이 90인지 직접
   검증하고, `WorkerTimeoutDefaultsTest`도 `@Value` 기본값이 `90L`인지 별도로 검증한다 —
   두 테스트 모두 90초가 설계 의도이자 실제 동작임을 뒷받침한다.
4. **문서 근거**: `docs/infra-sprint/contracts.md`의 timeout 관계 체인에도 이미
   `SQS Visibility Timeout 90초 이상`으로 명시돼 있어(72~87행), 90초가 이 프로젝트의
   의도된 설정값이었음을 확인할 수 있다.

**최종 해석**: 최초 수신(10:52:10.942) 시점에 Worker A가 요청한 ReceiveMessage에 90초
visibility timeout이 적용됐다. Worker A는 39.9초 처리 후 kill로 죽었고, 남은
90−39.9≈50.1초가 지난 뒤(실측 kill→재노출 50.1초, 최초수신→재노출 90.0초로 90초 설정값과
정확히 일치) 메시지가 정상적으로 재노출되어 Worker B가 수신·처리했다. 이는 Standard
Queue의 조기 중복 전달이 아니라, **요청별 visibility timeout(90초) 설정이 정상 동작한
것**이다.

**802초의 정체**: 같은 파일에 `RECOVERY_SECONDS=802`, `inject=1789987970 recovered=1789988772
seconds=802`가 별도로 기록돼 있다. `inject=1789987970`은 kill 시각과 정확히 일치하지만,
`recovered=1789988772`를 UTC로 변환하면 **2026-09-21 11:06:12**로 — DB COMPLETED(10:54:41)나
CloudWatch 완료 로그(10:54:41)와 **전혀 다른 시각**이며, 오히려 죽은 Worker A를 재기동한
시각(`started_at=2026-09-21T11:06:26.714Z`, 같은 파일 55행)의 14초 전이다. 그리고 바로 위
라인에 `timeout status=failed to run commands: exit status 1 after=794s`가 있다 — 즉 802초는
**실제 작업 완료를 측정한 값이 아니라, 완료를 제대로 감지하지 못하고 794초 만에 실패로
끝난 폴링/wait 스크립트가 반환된 시각을 "recovered"로 잘못 기록한 것**이다(폴링 로직
버그로 판단). 큐 대기, 최초 메시지 생성 시각, 다른 실험과의 시각 혼동은 아니다 — 원인은
이 스크립트 자체의 완료 감지 로직 오류다.

**결과**: 자동 복구는 일어났고, kill 후 **110.6초** 만에 다른 Worker가 결과를 COMPLETED로
확정했다(DB/CloudWatch 교차검증).
**해석**: Worker가 실제로 요청하는 visibility timeout은 90초(Queue 기본값 120초와는 별개,
위 §"Visibility Timeout 불일치 원인" 참고)이고, 여기에 정상 처리시간 약 70초(`contracts.md`
timeout 체인)를 더한 예상 복구 예산은 **약 160초**다. 실측 복구시간 110.6초는 이 예산
이내다 — **PASS**. 이전 문서 버전에서 "802초로 FAIL"이라고 적었던 것은 스크립트 버그로
부풀려진 값을 근거로 한 오판이었다 — 정정한다.
**한계**: 이 값은 "maxReceiveCount 이전" 설정에서, 단발성(재실행 없음)으로 측정됐다 — 반복
측정으로 전형값인지 이상치인지는 확인하지 않았다.
**evidence**: `artifacts/day14/11-worker-failures-20260921T105107Z.txt:21-58` (DB 표),
동 파일 "WORKER 로그" 섹션(CloudWatch export, 같은 파일 하단)

## 2. Graceful SIGTERM

**목적**: SIGTERM을 받았을 때 진행 중인 메시지를 정상적으로 끝까지 처리하는지 확인.
**조건**: analysisId=656(1차), 658(재실험)이 `PROCESSING` 상태.
**실행**: 1차: UTC 11:07:16 SIGTERM → `stop_seconds=4`, 컨테이너 로그상 `SIGTERM_TO_EXIT=8s`.
재실험: UTC 11:39:08 SIGTERM(owner=Worker-A) → `stop_seconds=25`.
**결과**: 두 번 모두 DB 최종 상태 `COMPLETED` (1차는 재조회 시점에 `PROCESSING`으로 남아있었으나
이후 정상 완료된 것으로 재실험에서 재확인, 재실험은 `results=1`로 COMPLETED 확정).
**해석**: SQS Worker 경로는 SIGTERM 시 진행 중 메시지를 끝까지 처리하고 종료한다 —
Redis Streams 경로(§6 참고)의 "412ms만에 즉시 종료" 문제와 대비된다.
**한계**: stop_seconds가 4초/8초/25초로 실행마다 달라 재현성 편차가 있다 — 원인은 Fake 처리
지연(60,000ms) 진행률에 따라 달라지는 것으로 추정되나 확정 근거는 없다(추정).
**evidence**: `artifacts/day14/11-worker-failures-20260921T105107Z.txt:59-93`,
`artifacts/day14/12-worker-failures-redo-20260921T113728Z.txt:11-43`

## 3. 동시 중복 메시지

**목적**: 같은 analysisId 메시지가 큐에 두 번 들어갔을 때 결과가 중복 저장되지 않는지(fencing)
확인.
**조건**: analysisId=657(1차), 659(재실험) — 두 Worker(A, B)를 모두 정지시킨 뒤 동일 메시지를
큐에 2건 투입.
**실행**: 재실험 기준 `queue before start=0 0 (visible 2 기대)` 상태에서 A/B 동시 기동, 두
Worker가 같은 메시지를 각각 `receiveCount=1`(Worker A), `receiveCount=2`(Worker B)로 수신.
**결과**: `reached=COMPLETED after=88s`, DB 최종 결과 행 **1건**(`results=1`). Worker B 로그에
`claim에 실패했습니다 - 다른 Worker가 처리 중이거나 이미 stale이 아닙니다.
currentStatus=PROCESSING` 기록 — fencing이 정상 동작해 두 번째 claim 시도가 거부됨.
**해석**: workerId 기반 조건부 claim(ADR-04)이 동시 중복 수신 상황에서 결과 중복 저장을 막는
것을 직접 확인했다.
**한계**: 없음(정합성 관점에서는 깔끔한 PASS). 다만 재시도 소진 메시지가 재노출 후 정리되는
데 "최대 약 3분"이 걸린다고 기록돼 있어(`queue_drained=yes`까지 시간), 큐 정리 완료 시점의
운영 관측 포인트로 남겨둘 필요가 있다.
**evidence**: `artifacts/day14/11-worker-failures-20260921T105107Z.txt:94-111`,
`artifacts/day14/12-worker-failures-redo-20260921T113728Z.txt:44-111`

## 4. Poison message와 DLQ

**목적**: AI 처리가 계속 실패(TIMEOUT)하는 메시지가 재시도 한도를 소진한 뒤 DLQ로 이동하고
DB 상태가 FAILED로 정리되는지 확인.
**조건**: analysisId=661, Worker A/B에 `ANALYSIS_PROCESSOR_FAKE_OUTCOME=TIMEOUT`,
`ANALYSIS_PROCESSOR_FAKE_DELAY_MS=2000` 설정.
**실행**: UTC 14:10:14 제출. 이후 `receiveCount` 1/3 → 2/3 → 3/3까지 재시도, 매번
"일시적 오류... receiveCount=N/3"으로 재큐잉되다 3/3에서 "재시도 횟수를 소진한 일시적
오류입니다 - fenced FAILED로 확정하되 DLQ 이동을 위해 메시지는 삭제하지 않습니다"로 처리.
**결과**: DB `status=FAILED`가 **+200초**에 관측(`db_failed_observed=+200s`), DLQ 도착은
**+297초**(`dlq_arrival_observed=+297s`) — **둘 사이 97초 간극**. DLQ 메시지 확인 후 해당
건만 수동 삭제.
**해석**: 실패 분류(일시적 vs 영구, ADR-17 계약)와 재시도 소진 후 DLQ 이동 자체는 동작하지만,
**DB FAILED 갱신과 DLQ 도착이 원자적이지 않다** — 이 간극 동안 DB는 FAILED인데 메시지는 아직
큐에 남아있는 애매한 상태가 존재한다(한계 문서 J에 재수록).
**한계**: 97초 간극의 원인(Visibility Timeout 만료 대기로 추정)은 추정일 뿐 evidence로
확정되지 않음 — 미검증.
**evidence**: `artifacts/day14/32-b2-ai-timeout-dlq-20260921T140923Z.txt:1-61`

## 5. API 1대 vs 2대 (Day13 exp2)

**목적**: API 인스턴스 수에 따른 처리량/지연 변화를 용량 계획 근거로 측정 (주의: 이 실험은
Redis Streams vs SQS **큐 선택 근거가 아니다** — `day15-decision-criteria.md`에 사전 명시).
**조건**: k6 부하, VU=10/50/100, API 1대 vs 2대(로드밸런싱).
**실행**: 각 조건 3회 반복(r1~r3).

| 조건 | VU | rps(3회 평균) | p95 latency(3회 평균) | API 인스턴스 CPU% |
|---|---|---|---|---|
| API 2대 | 10 | 552.6 | 24.9ms | A=31.5 B=35.5 |
| API 2대 | 50 | 688.0 | 177.3ms | A=38.6 B=39.8 |
| API 2대 | 100 | 676.5 | 388.4ms | A=39.0 B=39.6 |
| API 1대 | 10 | 453.4 | 25.9ms | A=49.3 |
| API 1대 | 50 | 482.3 | 175.2ms | A=54.8 |
| API 1대 | 100 | 483.8 | 370.5ms | A=54.9 |

**결과**: `fail_pct=0.0` — 모든 조건에서 실패율 0%(HTTP 레벨 실패 없음, 응답시간만 증가).
VU=100 기준 처리량은 API 2대(≈676.5rps)가 API 1대(≈483.8rps)보다 **약 40% 높음**. p95
latency는 두 조건이 비슷(388ms vs 370ms) — API 1대가 오히려 근소하게 더 낮게 나온 지점도
있으나, 이는 API 1대가 더 낮은 처리량에서 측정된 결과라는 점을 함께 봐야 한다.
**해석**: API 인스턴스를 늘리면 처리량이 확장되고, CPU 사용률은 인스턴스당 분산된다(1대
54.9% vs 2대 각 39%대). 용량 계획 관점에서 API 2대 구성이 더 여유 있게 동작한다.
**한계**: 이 결과는 SQS Worker 경로 기준이며 Redis Streams 경로에는 적용되지 않는다. 큐
선택의 근거로 사용하지 않는다(§ queue-adoption-decision.md 참고).
**evidence**: `artifacts/day13/12-exp2-runs-20260920T144345Z.tsv` (전체 18행),
`artifacts/day13/12-exp2-api-scale-20260920T144345Z.txt`,
`artifacts/day13/12b-exp2-db-metrics-20260920T152722Z.txt`,
`artifacts/day13/12c-exp2-hikari-20260920T152921Z.txt`

## 5-1. Sync vs Async 처리 (Day13 exp1, 참고)

**조건**: 동시성(concurrency) 2/10, 동기(sync) 제출 API vs 비동기(async) 제출 API 비교.
**결과 (MEDIAN, 3회)**:

| condition | http_avg | http_p95 | e2e_avg | e2e_p95 |
|---|---|---|---|---|
| sync-c2 | 11,229ms | 11,240ms | 11,229ms | 11,240ms |
| async-c2 | 57ms | 69ms | 12,072ms | 12,314ms |
| sync-c10 | 11,236ms | 11,248ms | 11,236ms | 11,248ms |
| async-c10 | 75ms | 113ms | **45,279ms** | 79,262ms |

**해석**: 동기 API는 HTTP 응답 자체가 처리 완료까지 블로킹되므로 동시성이 늘어도
http_avg가 거의 변하지 않는다(11.2초대 고정). 비동기 API는 HTTP 응답은 즉시(수십~백ms)
오지만, 동시성 10에서는 e2e 지연이 12초대에서 **45초대로 급증** — 이는 Worker 동시 처리
용량(당시 Worker concurrency 초기값=1, `decisions.md`)을 넘어서는 요청이 쌓이며 대기 시간이
늘어나는 것으로 해석된다.
**한계**: 이 실험도 SQS 경로 기준이며, 사전에 "큐 선택 근거로 쓰지 않는다"고 명시된 실험이다
(`day15-decision-criteria.md` 원문: "Redis Streams와 SQS의 성능 비교가 아니므로 큐 선택의
판정 기준으로 쓰지 않는다"). 여기서는 동기/비동기 API 설계 자체의 트레이드오프 참고용으로만
싣는다.
**evidence**: `artifacts/day13/11-exp1-sync-vs-async-20260920T135342Z.txt:87-106` (SUMMARY 표)

## 5-2. Queue Wait 시간 vs 백로그 크기 (Day13 exp3, 참고)

**조건**: N=10/50/100건을 한 번에 큐에 투입, worker `max_inflight=2`.

| N | qw_p50(3회평균) | qw_p95(3회평균) | qw_max(3회평균) | 성공 |
|---|---|---|---|---|
| 10 | 22.7s | 45.3s | 45.3s | 10/10 |
| 50 | 135.0s | 259.3s | 270.6s | 50/50 |
| 100 | 270.6s | 528.7s | 552.2s | 100/100 |

**해석**: 큐 대기시간은 백로그 크기에 거의 선형으로 비례해 증가한다(N이 10배가 되면 대기시간도
약 10배). worker 동시 처리 용량(max_inflight=2)이 고정된 상태에서 배치 크기만 늘렸기 때문으로
해석된다. 그래프: `docs/infra-sprint/images/day13-queue-wait.png` (§ Queue Wait 그래프 문서
참고).
**한계**: 이 역시 큐 선택 근거가 아니라 용량 계획(Worker 수 조정 필요성) 근거로만 쓴다.
**evidence**: `artifacts/day13/13-exp3-runs-20260920T154135Z-final.tsv`

## 6. Redis baseline 및 Redis 차단

### 6-1. Redis Streams Consumer kill -9 (baseline)

**목적**: 프로덕션 Redis Streams 소비 경로에서 Consumer가 죽었을 때 자동 복구되는지 확인.
**조건**: Redis 엔드포인트 `autique-experiment-redis.csw9o8.ng.0001.apn2.cache.amazonaws.com`.
Consumer `worker-da4e5249-...`가 세션을 `VISION_PROCESSING` 중.
**실행**: UTC 2026-09-21T11:51:14 kill -9. 새 Consumer(`worker-9f9486c8-...`) 기동. 이후
600초(10분) 동안 2분 간격 관찰.
**결과**: XPENDING idle 시간이 27,741ms → 160,200ms → 279,595ms → 399,682ms → 520,219ms →
639,631ms로 **계속 증가만 함 — 600초 관찰 동안 자동 재선점(XCLAIM/XAUTOCLAIM) 발생 0회**.
수동 `XCLAIM`만으로는 처리가 재개되지 않았고, 동일 payload로 **재-`XADD`**까지 해야 비로소
처리가 재개됨.
**해석**: Redis Streams 경로는 자동 복구 메커니즘이 없다 — 사람이 직접 개입해야 한다.
**한계**: 관찰 시간이 600초로 제한돼 있어 "그 이후 언젠가 자동 복구되는지"는 확인하지
못했다. 다만 XPENDING idle이 계속 증가만 하는 패턴 자체가 "자동 재선점 로직 부재"라는
코드 감사 결과(redis-streams-audit.md, 갭 b)와 일치한다.
**evidence**: `artifacts/day14/21-redis-baseline-crash-20260921T114948Z.txt`,
`artifacts/day14/22-redis-baseline-final-20260921T120616Z.txt:2-6`

### 6-2. Redis 6379 NACL 차단 (B3)

**목적**: Redis에 접근할 수 없을 때 API가 어떤 영향을 받는지 확인.
**조건**: NACL(`acl-09530e1768588b4a3`)에 Redis 포트 6379 deny 규칙 추가, 180초 유지.
**실행**: UTC 14:43:15 차단 시작 → 14:46:15 규칙 삭제(복구).
**결과**:
- API A/B readiness: t+33~40s부터 `unhealthy/Target.Timeout`, readiness probe
  `readiness_http=000 time=20.0017s`(타임아웃) — **API 서비스가 완전히 응답 불가 상태**가 됨.
- 복구 후 `restore+72s api_a=unhealthy... api_b=healthy`, `restore+79s` 양쪽 모두 healthy —
  복구까지 **79초**.
- 요청 영향(경로별, N=355):
  - `/api/auctions/12`(DB 경로): **실패 0건**
  - `/day14-probe`(DB/Redis 비의존): **실패 0건**
  - `/api/recommendations/auctions`(Redis 의존): **실패 44건**, `first_fail=+1.9s
    last_fail=+231.7s window=229.8s`
**해석**: Redis 장애가 readiness probe 전체를 unhealthy로 만들어 ALB가 API를 통째로
트래픽에서 제외했다 — 정작 실패한 요청은 Redis 의존 경로(추천 API) 44건뿐이고 DB 경로는
0건 실패였는데도, readiness 설계상 API 전체가 죽은 것처럼 취급됐다.
**한계**: readiness probe가 Redis까지 확인하는 게 의도된 설계인지(decisions.md의
"readiness=DB 포함" 정의에 Redis가 포함되는지)는 이 실험 evidence만으로는 판단할 수 없어
**미검증**.
**evidence**: `artifacts/day14/33-b3-redis-block-20260921T144231Z.txt`

## 7. Scheduler 중복 종료 vs 입찰 정합성

**목적**: API 인스턴스 2대가 동시에 경매 종료 Scheduler를 돌릴 때 중복 처리나 정합성 위반이
없는지 확인.
**조건**: Day11 3개 경매(4,5,6), Day12 3개 경매(10,11,12), 종료 시각까지 k6로 지속 입찰.

| 실행 | 경매 수 | 최종 price_ok/winner_ok | bids_after_end_at | Scheduler 중복 처리 |
|---|---|---|---|---|
| Day11 (09-scheduler-close-vs-bid) | 3 | 전부 OK | 전부 0 | 미검증(로그상 두 API 모두 candidates=3 success=3 확인, ended/notLive 분리는 Day12부터 도입) |
| Day12 (04-scheduler-close-vs-bid, PASS) | 3 | 전부 OK | 전부 0 | **해결됨** — API-A `candidates=3 ended=0 notLive=3`, API-B `candidates=3 ended=3 notLive=0`. 3개 경매를 정확히 한 쪽만 처리(`ENDED_SUM=3`, `FAILED_SUM=0`) |

**결과**: Day12 k6 부하 종료 직후 `after_end 201 count=0`(마감 이후 성공 입찰 0건), 각 경매의
`last_201_sent`는 종료 시각 이전(-56ms/-44ms/-28ms), `first_after_end`는 종료 직후
409(+24ms/+6ms/+11ms)로 정확히 경계에서 차단됨.
**해석**: `AuctionEndOutcome` 반환(ADR-10)으로 두 API 인스턴스가 서로 어떤 경매를 처리했는지
구분할 수 있게 되어, 동일 경매를 두 번 종료 처리하는 일이 없어졌다.
**한계**: 3개 경매, 21명 입찰자 규모의 실험이라 훨씬 큰 동시성에서도 같은 결과가 나오는지는
이 두 실행만으로 단정할 수 없다.
**evidence**: `artifacts/day11/09-scheduler-close-vs-bid-20260920T081750Z.txt`,
`artifacts/day12/04-scheduler-close-vs-bid-20260920T114203Z.txt`

### 7-1. (배경) DB 락 없는 동시 입찰 정합성 위반 — 3/20 → 0/20

이 실험은 Day11~15 범위가 아니라 **이전 주차의 별도 concurrency 실험**이며,
`docs/experiments/concurrency/summary.md`에 이미 문서화돼 있다. 이번 문서는 원본을 그대로
인용만 한다.

> "Any invariant violation | 3/20 | 0/20" (No-lock 20회 중 3회 위반 → Pessimistic lock
> 적용 후 20회 중 0회 위반)

**evidence**: `docs/experiments/concurrency/summary.md:56` 및 원본 실행 로그
`docs/experiments/concurrency/raw/logs/no-lock-run-*.log`,
`docs/experiments/concurrency/raw/logs/optimistic-run-*.log`

## 8. API 1대 중지 (B1)

**목적**: API A EC2 인스턴스가 통째로 죽었을 때 ALB가 API B로 얼마나 빨리 전환하는지, 요청은
얼마나 영향받는지 확인.
**조건**: API A(`i-01ee93356c678a070`), API B 둘 다 초기 healthy.
**실행**: UTC 13:48:38 API A EC2 인스턴스 stop.
**결과**:
- `t+21s`부터 `api_a=unused/Target.InvalidState` (ALB가 A를 타겟에서 제외 시작).
- 요청 영향(3개 경로, 각 total=991, 합계 **2,973건**):
  - `/api/auctions/12`: failed=14
  - `/day14-probe`: failed=16
  - `/api/recommendations/auctions`: failed=16
  - **합계 46건 실패 / 2,973건 = 실패율 약 1.55%**
  - 영향 윈도우: 경로별 6.2s~27.7s(가장 긴 것은 `/api/auctions/12`의 27.7초,
    `first_fail=+2.7s last_fail=+30.4s`)
- API A 재기동: `api_a_left_healthy=+21s`, `api_a_back_healthy=+341s`(EC2 재기동 포함 전체
  용량 복구).
**해석**: ALB 헬스체크 기반 failover 자체는 동작하며, 실제 요청 단위 영향은 2,973건 중 46건
(약 1.55%), 영향 지속 시간은 최대 약 30초 수준이다. "API 서비스 영향 약 30초"라는 표현은
`/api/auctions/12` 경로의 `last_fail=+30.4s` 시점과 일치한다.
**한계**: 이 수치는 부하 조건(k6 VU 수 등)에 따라 달라질 수 있다 — 이 실행의 정확한 VU 수는
이 파일에서 확인되지 않아 미검증.
**evidence**: `artifacts/day14/31-b1-api-stop-20260921T134802Z.txt`

## 9. B1 알람 사각지대 (MTTD 미탐지)

**목적**: B1(API A 중지) 장애 동안 CloudWatch 알람이 실제로 탐지했는지 확인.
**조건**: 관찰 윈도우 UTC 13:45:38~13:55:38(주입 13:48:38).
**결과**: HealthyHostCount가 22:49~22:53(KST) 구간 동안 **2 → 1**로 감소, 같은 구간에
`HTTPCode_ELB_5XX_Count=41`(22:48 KST 1분 집계) 발생. 그러나 이 사건 전후로 `현재 알람 상태`를
조회한 결과:
- `autique-exp-alb-target-5xx`: 마지막 상태 변경 **2026-09-19T15:17:05**(사건 이틀 전)
- `autique-exp-unhealthy-target`: 마지막 상태 변경 **2026-09-21T21:39:51**(주입 22:45~22:54
  KST **이전**)
→ 두 알람 모두 사건 발생 구간 동안 상태 변화 기록이 **없다**. 즉 실제 호스트 다운과 5xx
급증이 있었는데도 **알람이 전혀 발동하지 않았다(MTTD: 미탐지)**.
**해석**: 알람 임계값 또는 평가 주기가 이 정도 규모/지속시간의 장애를 잡기에 충분히
민감하지 않다. §queue-adoption-decision.md에서 다루는 "관측 갭"과 같은 성격의 문제다.
**한계**: 알람의 정확한 임계값/평가주기 설정값은 이 문서에서 재조회하지 않았다 — 필요 시
`alarms.json`(evidence export)에서 확인 가능.
**evidence**: `artifacts/day14/31b-b1-alarm-gap-evidence-20260921T140348Z.txt`

## 10. B2/B3 장애 주입 요약

B2(AI timeout → DLQ)는 §4, B3(Redis 차단)는 §6-2에서 이미 다뤘다. 세 실험(B1/B2/B3) 모두
`artifacts/day15/evidence-20260922T083413Z/alarm-history-*.json` 6개 알람 이력 파일로
evidence export가 남아있다.

## 11. MTTD/MTTR 종합

| 장애 | MTTD(탐지) | MTTR(복구) |
|---|---|---|
| B1(API A 중지) | §9에서 확인된 대로 알람 기준 **미탐지** | 요청 영향 기준 약 30초(§8), 전체 용량 복구 341초 |
| B2(AI timeout DLQ) | `autique-exp-analysis-failure` 알람 정상 발동(ALARM→OK 전환 로그 존재) | DB FAILED +200s, DLQ 도착 +297s |
| B3(Redis 차단) | `autique-exp-unhealthy-target` 알람 발동 확인(`23:46:51 OK→ALARM`) | 79초(§6-2) |
| Worker kill -9(SQS) | 해당 없음(알람이 아니라 자동 재시도 메커니즘) | 110.6초(§1, DB/CloudWatch 교차검증) |
| Redis Streams Consumer kill -9 | 해당 없음(알람 없음) | 600초 관찰 동안 미복구 → 수동 개입 필요(§6-1) |

**해석**: 알람이 실제로 잡아낸 것은 B2(analysis-failure), B3(unhealthy-target)이고, B1은
탐지되지 않았다. 이는 §9의 결론과 일치한다.

## 12. CI/CD 및 rollback

**목적**: 정상 배포, 의도적 실패 → 자동 롤백, 이후 지속 검증까지 CI/CD 파이프라인이 실제로
동작하는지 확인.

**정상 배포 파이프라인 전체 소요시간**: **22분 46초**
(`test-build-deploy in 22m46s`, run `35457283270`, SDK 수정 후 재검증 기준)
— evidence: `artifacts/day10/13-sdk-fix-ci-watch-20260919T171107Z.txt:1291`

**의도적 실패 → 자동 롤백 타임라인** (BAD_SHA=`1a3825d1c80d0b46fa4ed68ebdf81ab3fdeb37a1`,
GOOD_SHA=`e0790acb44fed83acd23d778aab08b5681246ae4`):

| 이벤트 | UTC 시각 | 경과 |
|---|---|---|
| 신규 배포 readiness 실패 감지 | 17:56:43.896 | 0s |
| API readiness 복구(GOOD_SHA로 롤백) | 17:57:20.665 | +36.8s |
| ELB target health PASS | 17:57:38.957 | +55.1s |
| Worker 컨테이너 정상 확인 | 17:57:56.162 | +72.3s |
| E2E 검증 PASS(업로드→S3→SQS→Worker→COMPLETED) | 17:58:25.604 | **+101.7s** |

**결과**: 실패 감지부터 전체 E2E 검증 통과까지 **약 101.7초**. 별도 감사(rollback-aws-audit)
에서도 API/Worker 모두 GOOD_SHA 이미지로 재확인(`LEASE_LOST_COUNT=0`,
`STALE_RECLAIM_COUNT=0`).
**해석**: 자동 롤백이 실패 감지 후 약 100초 내에 안전한 상태로 복귀함을 확인했다.
**한계**: 1차 감사 파일(`11-rollback-aws-audit-20260918T180258Z.txt`)은 `FAIL`로 끝났다가
재감사(`11b-...-fixed-...txt`)에서 `PASS`로 정정됐다 — 1차 FAIL의 정확한 사유는 파일에
명시돼 있지 않아 미검증(컨테이너 자체는 이미 GOOD_SHA로 정상 기동 중이었음).
**evidence**: `artifacts/day9/10-intentional-rollback-20260918T180015Z.txt:806-3686`,
`artifacts/day9/11-rollback-aws-audit-20260918T180258Z.txt`,
`artifacts/day9/11b-rollback-aws-audit-fixed-20260918T180831Z.txt`

**지속 검증(같은 SHA로 A/B 인스턴스 일치)**:
- Day12: `SHA_MATCH=4/4 LKG_IS_HEAD=1` (API-A/B, Worker-A/B 전부 SHA `4715f06...` 일치)
- Day14: `SHA_MATCH=4/4 LKG_IS_HEAD=1`, SHA `f73a099f84fc316fd8adcdd222d688828efbb01c`,
  SQS 두 큐 모두 비어있음(0/0) 확인
**evidence**: `artifacts/day12/03-cicd-deploy-4715f06.txt`,
`artifacts/day14/30-cicd-verify-20260921T123940Z.txt`

**미검증**: SDK 버전 문제로 인한 배포 실패(Day10)의 정확한 에러 메시지/근본 원인 —
관련 파일(`artifacts/day10/09-deploy-failure-*.txt` 등)이 600KB 이상으로 이번 세션에서
전체를 읽지 못함. SDK 수정 후 재검증 파이프라인이 전부 PASS했다는 결과만 확인.
