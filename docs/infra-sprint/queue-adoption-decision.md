# Queue Adoption Decision — Redis Streams vs SQS PoC

작성 시점: 2026-09-22 (Day 15-2, Day15 Terraform·최종 cleanup 완료 후)
상태: **Accepted** — 최종 결정: Redis Streams 유지 + 복구·관측 보완

이 문서는 다음 기존 자료를 통합·요약한다:
- `docs/infra-sprint/redis-streams-audit.md` — 코드 레벨 a~j 항목별 감사
- `docs/redis-streams-recovery-audit.md` — 장애 복구 관점 갭 1~7 재정리
- `docs/infra-sprint/day15-decision-criteria.md` — Day13 측정 **전**에 미리 고정한 판단 기준
- `docs/infra-adr/ADR-04-conditional-claim-fencing-receive-count.md`, `ADR-12-analysis-job-dual-write.md`, `ADR-17-analysis-processor-boundary.md`

---

## 1. 기존 Redis Streams 감사 요약

`docs/infra-sprint/redis-streams-audit.md`의 a~j 코드 감사 결과:

| 항목 | 판정 |
|---|---|
| a. ACK 시점(완료 후) | 있음 |
| b. PEL 재선점(XCLAIM 등) | **없음** |
| c. 중복 처리 방어 | 부분적 (조회 후 저장, `@Version` 없음) |
| d. Lease/Fencing | **없음** |
| e. 실패 분류(일시적/영구) | **없음** |
| f. 재시도 한도/DLQ | **없음** |
| g. Graceful shutdown | **없음** (소스 분석 + 실측으로 확인: `docker stop` 412ms만에 반환, sleep 8초 끝나기 전 종료, DB에 `VISION_PROCESSING` 고아 세션 남음, XPENDING=1) |
| h. 상태모델 | 조회 후 저장 (조건부 UPDATE 아님) |
| i. 결과+상태 단일 트랜잭션 | 있음 |
| j. 다중 Consumer 안전성 | 부분적 (신규 메시지 분배는 안전, PEL 인계는 불가) |

## 2. 현재 구조(Redis Streams)의 장점

- **a**: 처리가 완료된 뒤에만 ACK하므로, 처리 도중 죽으면 메시지가 PEL에 남아 "유실"은 되지 않는다.
- **i**: 분석 결과 저장과 상태 갱신이 하나의 트랜잭션으로 묶여 있어 "결과는 있는데 상태는 이전 상태" 같은 불일치가 생기지 않는다.
- **j (부분)**: 여러 Consumer가 동시에 떠 있어도 아직 아무도 읽지 않은 새 메시지의 분배 자체는 안전하다 — 문제는 오직 "이미 누가 가져갔는데 그 Consumer가 죽은" 케이스(PEL 인계)에서만 발생한다.
- 이미 프로덕션에서 AI Vision 분석 경로(`POST /api/products/analyze`)로 운영 중이며, Redis는 이미 경매 상세 조회 캐시로도 쓰이고 있어 별도 인프라 추가가 필요 없다.

## 3. 발견된 복구·관측 gap (갭 1~7)

`docs/redis-streams-recovery-audit.md` 요약:

| 갭 | 제목 | 심각도 | 기존 문서화 여부 |
|---|---|---|---|
| 1 | Consumer 이름이 매번 바뀌어 PEL 자동 회수 불가 | 높음 | 부분적 |
| 2 | `VISION_PROCESSING`에서 멈춘 세션이 재전달 시 조용히 폐기 | 높음 | 예 |
| 3 | DLQ 부재로 손상 메시지가 PEL 오염 | 중간 | 예 |
| 4 | 일시적/영구 오류 미구분, 자동 재시도 없음 | 중간 | 예 |
| 5 | PEL 깊이 모니터링/알림 없음 | 중간 | 아니오(신규) |
| 6 | 단일 인프로세스 Consumer, 장애 시 자동 인계 없음 | 중간 | 부분적 |
| 7 | `findById` 예외 경로가 ACK 정책 문서에 누락 | 낮음 | 아니오(신규, 기능 결함 아님) |

원 감사 결론: "가장 우선적으로 다뤄야 할 것은 **갭 1**과 **갭 2**의 조합이다 — 갭 2를 고치려면 결국 stale 메시지를 재처리해야 하는데, 그 재처리 자체가 갭 1(claim 로직 부재) 때문에 불가능한 상태다."

### Day14 baseline 재현 (갭 1·2 실측 확인)

`artifacts/day14/21-redis-baseline-crash-20260921T114948Z.txt`:
- Consumer `worker-da4e5249-...`가 세션을 `VISION_PROCESSING` 중 UTC 11:51:14에 kill -9로 강제 종료.
- 새 Consumer `worker-9f9486c8-...`가 뜨지만 죽은 Consumer 소유 PEL 엔트리는 그대로 남음.
- 이후 **600초(10분) 동안** 2분 간격으로 관찰 — XPENDING idle 시간이 27,741ms → 160,200ms → 279,595ms → 399,682ms → 520,219ms → 639,631ms로 **계속 증가만 하고 자동 재선점(XCLAIM/XAUTOCLAIM)은 단 한 번도 발생하지 않음**.
- 수동 `XCLAIM`만으로는 핸들러가 재처리하지 않았고, 동일 payload로 **재-`XADD`까지 해야** 비로소 처리가 재개됨 (`21-...txt:134-140`).
- 최종 상태(`22-redis-baseline-final-20260921T120616Z.txt:2-6`): 세션은 수동 개입 후에야 `VISION_PROCESSING`을 벗어남 — **완전 자동 복구가 아니라 사람이 직접 개입해야 하는 상태**였다.

## 4. Redis Streams vs SQS PoC 비교표

> **주의**: 아래 비교는 "Redis Streams 제품 vs SQS 제품"의 동일 조건 단독 비교가 **아니다**. SQS 쪽 PoC(Day14 Worker 경로)에는 조건부 claim(선점), workerId fencing, maxReceiveCount 기반 재시도/DLQ, 실패 분류 같은 **애플리케이션 레벨 개선**이 함께 들어가 있다. Redis Streams 쪽에 같은 개선을 넣으면 결과가 달라질 수 있다. 따라서 이 표는 "SQS가 원래 더 우월하다"가 아니라 "지금 각각 실제로 어떤 상태인가"를 보여주는 것이다.

| 항목 | Redis Streams (현재, 개선 전) | SQS PoC (Day14, fencing/DLQ 포함) | evidence |
|---|---|---|---|
| Crash 후 자동 복구 | **없음** — 600초 관찰 동안 미복구, 수동 XCLAIM+재-XADD 필요 | 있음 — `maxReceiveCount` 이전 kill -9 테스트에서 **110.6초**(DB/CloudWatch 교차검증) 후 COMPLETED로 자동 복구 | `artifacts/day14/21-redis-baseline-crash-*.txt`, `artifacts/day14/11-worker-failures-*.txt` (DB 표 + WORKER 로그 섹션), `experiment-results.md` §1 |
| Graceful shutdown | 없음 — `docker stop` 412ms 만에 반환, 고아 세션 발생 | 있음 — SIGTERM 후 8초/25초 내 정상 완료 (재실험 포함 2회 모두 COMPLETED) | redis-streams-audit.md, `artifacts/day14/11-...txt:69-88`, `artifacts/day14/12-worker-failures-redo-20260921T113728Z.txt:21-39` |
| 중복 메시지 | 부분적 방어(조회 후 저장) | idempotent 처리 확인 — 중복 투입에도 결과 1건만 저장 (`results=1`) | `artifacts/day14/11-...txt:94-109`, `12-worker-failures-redo-...txt:44-79` |
| 재시도 소진 시 처리 | DLQ 없음(갭3) | `receiveCount=3/3` 소진 후 `FAILED`+DLQ 이동 확인. 단 **DB FAILED(+200s)와 DLQ 도착(+297s) 사이 97초 간극 존재 — 원자적이지 않음** | `artifacts/day14/32-b2-ai-timeout-dlq-20260921T140923Z.txt:20-61` |
| 알람 커버리지 | PEL 깊이 알림 없음(갭5) | `autique-exp-dlq-visible`, `autique-exp-sqs-oldest-age` 알람 존재·정상 동작 확인 | `artifacts/day14/13-poison-message-dlq-*.txt` |
| 실제 AI 프로세서로 검증됨? | 프로덕션에서 실제 운영 중(단, 이번 세션에서 실측 트래픽 규모는 미검증) | **아니오** — `RealAnalysisProcessor`는 ADR-17 "Day 3 Update"에서 명시적으로 **SKIPPED** 확정, 이번 스프린트 전체가 `FakeAnalysisProcessor`로만 측정됨 | `docs/infra-adr/ADR-17-analysis-processor-boundary.md` |

## 5. 미리 정한 판단 기준 (Day13 측정 **전** 고정, `day15-decision-criteria.md` 원문 인용)


**1단계 — SQS PoC 정합성 게이트** (하나라도 실패하면 SQS 도입을 제안하지 않는다):
| 게이트 조건 | 결과 | evidence |
|---|---|---|
| 중복/lease 상실 시 결과 1건(fencing) | PASS | `artifacts/day14/11-...txt:94-109`, `12-worker-failures-redo-...txt` |
| maxReceiveCount 이전 kill -9 후 자동복구 | PASS — 110.6초(DB/CloudWatch 교차검증) | `artifacts/day14/11-...txt`, `experiment-results.md` §1 |
| SIGTERM 시 정상 완료 | PASS | `artifacts/day14/11-...txt:69-88`, redo 파일 |
| 재시도 소진 시 DB FAILED+DLQ | PASS (단, 원자적이지 않음 — 97초 간극) | `artifacts/day14/32-...txt` |
| 복구시간이 Visibility Timeout+정상처리시간 이내 | **PASS** — Worker가 요청마다 명시하는 실효 VT는 90초(Queue 기본값 120초와는 별개, `SqsAnalysisJobPoller.java` + 테스트 2건으로 확정) + 정상 처리시간 70초(`contracts.md` timeout 체인) = 예산 약 160초. 실측 **110.6초**는 예산 이내(약 69%) | `SqsAnalysisJobPoller.java:50,102`, `SqsAnalysisJobPollerTest`, `WorkerTimeoutDefaultsTest`, `docs/infra-sprint/contracts.md`, `experiment-results.md` §1 |


**2단계 — 기존 Redis 경로 갭 재확인 (Day14 baseline)**: "Worker crash 후 N분 관찰 동안 자동 복구되지 않음" → **확인됨** (§3 참고, 600초간 미복구).

**3단계 — 변경 범위 비교표**: "SQS 도입의 변경 범위가 Redis 보완보다 **크면 2번**(Redis 유지+보완)을 제안한다. 비슷하거나 작으면 1번을 제안한다."
- SQS PoC는 `RealAnalysisProcessor`를 붙이지 않은 채(ADR-17, SKIPPED) `FakeAnalysisProcessor`로만 검증됐다 — 프로덕션 전환 시 **실제 AI 연동을 처음부터 새로 검증**해야 한다.
- ADR-04 Future 섹션 원문: "기존 Redis Streams 경로... 에도 같은 claim/finalization 경계를 적용할 수 있지만, 이번 Day4 범위에서는 SQS Worker 경로에만 구현했고 **Redis Consumer 비즈니스 코드는 손대지 않았다**." → SQS 경로는 프로덕션 Redis Consumer와 별개로 새로 만들어진 시스템이다.
- 이 두 사실을 근거로, SQS로 완전히 전환하려면 Producer 전환, API 계약 재검증, 실제 AI 프로세서 통합 재검증, 전체 회귀/E2E 재실행이 필요하다 — **Redis Streams에 §7의 보완안을 추가하는 것보다 변경 범위가 크다**고 판단한다.

> 참고: "조건부 선점·fencing·예외 분류·graceful shutdown은 **어느 큐를 쓰든 필요**하므로 비교에서 제외한다"(원문) — 즉 이 개선들은 Redis Streams에 추가하든 SQS를 도입하든 어차피 구현해야 하는 항목이라, 큐 선택 자체의 근거로 쓰지 않는다.

**Day13 결과의 위치**: "Redis Streams와 SQS의 성능 비교가 아니므로 **큐 선택의 판정 기준으로 쓰지 않는다.** 용량 계획(Worker 수, 병목 지점)의 근거로만 사용한다." (원문) — Day13 수치는 이 문서의 결정에 반영하지 않았다. 상세는 `experiment-results.md` 참고.

## 6. 세 가지 선택지 비교

| 선택지 | 내용 | 판정 |
|---|---|---|
| ① SQS Worker 도입 | 프로덕션 Redis Streams 소비 경로를 SQS 기반으로 완전 교체 | 기각 — 3단계 변경범위 비교에서 Redis 보완보다 큼(§5) |
| ② Redis Streams 유지 + 보완 | XAUTOCLAIM 재선점, 조건부 UPDATE/`@Version`, 예외 분류, 실패 Stream, XPENDING 관측 추가 | **채택** |
| ③ 현재 구조 유지 (갭 문서화만) | 아무것도 고치지 않고 갭만 기록 | 기각 — 갭 1·2(자동 복구 불가)가 이미 실측으로 확인된 상태에서 방치는 무책임 |

## 7. 최종 결정: **Redis Streams 유지 + 복구·관측 보완**

SQS로 전면 전환하지 않고, 현재 프로덕션 Redis Streams 소비 경로에 아래 보완을 추가한다.

## 8. 구체적인 보완안

1. **인스턴스별 고유 Consumer 이름** — 현재 Consumer 이름이 매 재기동마다 바뀌어 PEL을 스스로 회수할 방법이 없다(갭1). 인스턴스 ID 등 고정 식별자를 Consumer 이름에 포함시켜, 재기동 후에도 자신이 이전에 소유했던 PEL을 인지할 수 있게 한다.
2. **XPENDING/XAUTOCLAIM 기반 stale PEL 재선점** — 주기적으로 XPENDING으로 idle 시간이 임계값을 넘은 엔트리를 찾아 XAUTOCLAIM으로 재선점한다. Day14 baseline에서 확인된 "600초 방치" 상태를 자동으로 끊는다.
3. **조건부 UPDATE 또는 `@Version`** — 현재 "조회 후 저장"(h)이라 경쟁 조건에 취약하다. SQS 경로(ADR-04)가 이미 쓰는 조건부 UPDATE 패턴이나 JPA `@Version` 낙관적 락을 도입한다.
4. **일시적/영구 오류 분류** — 지금은 모든 실패를 동일하게 처리한다(갭4). OpenAI 429/5xx 같은 일시적 오류와 잘못된 이미지 같은 영구 오류를 구분해 재시도 정책을 분기한다.
5. **실패 전용 Stream** — DLQ가 없어 손상 메시지가 PEL을 오염시킨다(갭3). 재시도 한도를 넘긴 메시지를 별도 실패 Stream으로 옮긴다.
6. **XPENDING/실패 메트릭·알람** — PEL 깊이 모니터링이 없다(갭5). XPENDING 길이, 실패 Stream 적재량을 CloudWatch(또는 동등 도구) 메트릭으로 발행하고 알람을 건다.
7. **Graceful shutdown** — 현재 `docker stop`이 412ms 만에 반환되고 sleep 8초를 기다리지 않는다(갭g). SIGTERM 핸들러가 진행 중인 메시지 처리를 완료할 때까지(또는 타임아웃까지) 기다리도록 수정한다.

## 9. SQS로 즉시 전환하지 않는 이유

1. **1단계 정합성 게이트는 5개 전부 PASS했다**(재검증 후 정정 — kill-9 복구시간은 110.6초로 예산 약 160초 이내, §5 참고) — 하지만 게이트 PASS가 "SQS로 전환해야 한다"를 의미하지는 않는다. 이 문서의 결정은 게이트 실패가 아니라 **3단계 변경 범위 비교**(아래 2번)에 근거한다. 다만 DB FAILED와 DLQ 이동은 여전히 원자적이지 않다(97초 간극) — 이 부분은 게이트를 통과했어도 남는 구조적 리스크다.
2. **변경 범위가 더 크다** — 프로덕션 경로 자체를 교체해야 하고, `RealAnalysisProcessor`가 한 번도 붙어본 적이 없어(ADR-17 SKIPPED) 실제 AI 연동 리스크가 그대로 남아있다.
3. **Day13 성능 비교는 애초에 큐 선택 근거가 아니다** — 판단 기준 문서에 사전에 명시된 규칙이다.
4. **비교 자체가 공정한 단독 비교가 아니다** — SQS PoC에는 이번 스프린트에서 새로 만든 fencing/DLQ/오류분류가 포함돼 있고, 같은 걸 Redis Streams에 넣으면 격차가 줄어들 가능성이 높다.

## 10. 담당자에게 전달할 짧은 제안문

> Day 15 인프라 스프린트 결론: 프로덕션 AI 분석 경로(Redis Streams)를 SQS로 교체하지 않습니다. 대신 이번 스프린트에서 실측으로 확인된 두 가지 핵심 결함 — (1) Worker crash 후 자동 복구가 전혀 안 됨(600초 관찰, 수동 개입 필요), (2) 재시도/오류분류/DLQ 부재 — 를 보완하는 작업을 다음 스프린트에 넣어주세요. SQS PoC로 검증한 조건부 선점(fencing)·재시도·DLQ 패턴을 그대로 Redis Streams용으로 옮겨 적용하면 됩니다. 상세 보완 항목 7개는 `docs/infra-sprint/queue-adoption-decision.md` §8 참고. SQS를 다시 검토할 조건: 위 보완을 다 넣었는데도 운영 부담이 크거나, 실제 AI 프로세서를 SQS 경로에 붙여 재검증했을 때 정합성 게이트를 다시 통과하는 경우.

## 11. 도입(보완) 시 필요한 추가 회귀/E2E 검증

- 보완안 3(조건부 UPDATE/`@Version`) 적용 후: 동시 입찰류 concurrency 테스트와 동일한 패턴으로 Redis Streams 동시 처리 경쟁 조건 재현 테스트 필요.
- 보완안 2(XAUTOCLAIM) 적용 후: Day14 baseline과 동일한 kill -9 시나리오를 재실행해 복구 시간이 실제로 줄었는지 확인 필요 (현재 600초 관찰 동안 0% 자동복구 → 목표: N분 내 자동 재선점).
- 보완안 7(graceful shutdown) 적용 후: `docker stop` 응답 시간과 DB 고아 세션 발생 여부 재측정 필요.
- 전체 보완 완료 후: `docs/infra-sprint/redis-streams-audit.md`의 a~j 표를 다시 채워 몇 개 항목이 "있음"으로 바뀌었는지 재감사 필요.
- 이번 스프린트에서 다룬 적 없는 항목: 실제 트래픽 규모에서의 Redis Streams 부하 테스트 (Day13은 SQS 경로만 측정했음 — §5 참고).

## 12. 미검증 사항과 rollback 고려

**미검증**:
- 보완안 적용 후 실제 복구 시간 개선폭 (아직 구현되지 않았으므로 측정 불가).
- 프로덕션 트래픽 규모에서 Redis Streams 현재 처리량/지연 (Day13은 SQS 경로만 측정).
- Real AI 프로세서를 SQS 경로에 붙였을 때의 정합성 (ADR-17에 의해 이번 스프린트 범위 밖).

**rollback 고려**: 이 결정은 "SQS로 전환하지 않는다"는 결정이므로 프로덕션 경로 자체에 rollback 리스크가 없다(현재 운영 중인 Redis Streams 경로를 그대로 둔다). 보완안(§8) 적용 시에는 각 항목을 독립적으로 배포/롤백 가능하도록 작은 단위로 나눠 적용할 것을 권장한다 — 특히 보완안 3(조건부 UPDATE)은 스키마/쿼리 변경을 동반하므로 별도 배포 단위로 분리하고, 실패 시 이전 "조회 후 저장" 로직으로 즉시 되돌릴 수 있게 feature flag 없이도 git revert만으로 롤백 가능한 형태로 구현할 것을 권장한다.
