# ADR-04 조건부 Claim, workerId Fencing, ApproximateReceiveCount 기반 재시도/DLQ

**Status**: Accepted

**Decision**: `product_analysis_jobs`의 SQS 경로 전용 상태 전이(`PENDING`/`QUEUED`/`PUBLISH_FAILED` → `PROCESSING` → `COMPLETED`/`FAILED`)를 단일 조건부 UPDATE(`claimForProcessing`)로 원자적으로 선점하고, 완료·실패 모두 `status=PROCESSING AND worker_id=:workerId` fencing을 건다. 완료는 `analysis_result` INSERT와 fenced COMPLETED UPDATE를 하나의 트랜잭션(`ProductAnalysisJobFinalizationService`)으로 묶고, Commit 성공 후에만 `DeleteMessage`를 호출한다. `analysis_result.analysis_id UNIQUE`를 마지막 방어선으로 둔다.

**Problem**: SQS Standard 큐는 중복 전달을 허용하고, Worker는 처리 중 언제든 죽을 수 있다. Visibility Timeout이 만료되면 이전 Worker(아직 살아있을 수도, 이미 죽었을 수도 있음)와 새 Worker가 동시에 같은 `analysisId`를 처리할 수 있다. SQS 자체(at-least-once 전달, Visibility Timeout)만으로는 "정확히 한 건의 결과 Commit"을 보장할 수 없다 - 큐 계층의 전달 보장과 애플리케이션 상태의 정확성은 서로 다른 문제다.

**Alternatives**:
1. Worker가 조회 후 상태를 저장(`SELECT` → `if` → `UPDATE`) - Java 레벨의 조건 판단과 DB WRITE 사이에 창이 생겨 두 Worker가 동시에 조회하면 둘 다 조건을 통과할 수 있다. Day2 이후 이 방식은 쓰지 않는다.
2. `ChangeMessageVisibility`로 heartbeat를 보내 lease를 연장 - Worker가 살아있는 동안 stale 판정을 미루는 별도 백그라운드 스레드/스케줄이 필요하고, 이번 스프린트 범위(Day4)에서 다루지 않기로 이미 결정했다(범위 밖).
3. 조건부 UPDATE(claim) + workerId fencing(완료/실패) + 결과 UNIQUE(선택) - Java 레벨 판단 없이 WHERE절 하나로 동시성 제어 지점을 DB에 위임한다. 별도 백그라운드 프로세스 없이 stale 재선점만으로 crash 복구가 가능하다.

**Choice**: 3번.

**Reason**: 이번 스프린트가 검증하려는 것은 "Worker가 죽거나 메시지가 중복돼도 최종 결과는 1건"이라는 정확성이지, SQS 자체의 전달 보장을 새로 만드는 것이 아니다. 조건부 UPDATE는 이미 Day2(발행 상태)에서 검증된 패턴이라 같은 원리를 PROCESSING/COMPLETED/FAILED에도 그대로 확장할 수 있고, heartbeat 없이도 "적당한 시간 안에 응답이 없으면 다른 Worker가 이어받는다"는 단순한 모델로 Day4 범위를 벗어나지 않는다.

**계약(claim/fencing 규칙)**:

```sql
UPDATE product_analysis_jobs
SET status = 'PROCESSING',
    processing_started_at = UTC_TIMESTAMP(6),
    worker_id = :workerId,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :id
  AND (
       status IN ('PENDING', 'QUEUED', 'PUBLISH_FAILED')
       OR (
           status = 'PROCESSING'
           AND (
                processing_started_at IS NULL
                OR TIMESTAMPDIFF(MICROSECOND, processing_started_at, UTC_TIMESTAMP(6)) > :staleAfterMicros
           )
       )
  )
```

- `PENDING`/`QUEUED`/`PUBLISH_FAILED` 또는 stale `PROCESSING`만 원자적으로 claim한다(`ProductAnalysisJobRepository.claimForProcessing`).
- claim 시 `worker_id`(`WorkerRuntimeIdentity.workerId()` 재사용 - 별도 DB 전용 ID 체계를 만들지 않았다), `processing_started_at`을 함께 기록한다.
- 완료(`completeIfOwned`)와 실패(`failIfOwned`) 모두 `status=PROCESSING AND worker_id=:workerId` fencing을 적용한다 - stale 재선점으로 lease를 잃은 옛 Worker가 뒤늦게 완료/실패를 보고해도 0건이 반영돼 막힌다.
- 완료 시 fenced COMPLETED UPDATE와 `analysis_result` INSERT를 `ProductAnalysisJobFinalizationService.complete()` 하나의 `@Transactional`로 묶는다(Handler와 분리된 별도 Spring Service 프록시 - self-invocation 문제를 피하기 위해 `ProductAnalysisJobClaimService`와 같은 패턴을 재사용했다). INSERT 실패(예: UNIQUE 위반, DB 오류)는 예외로 전파돼 트랜잭션 전체가 rollback된다 - 방금 반영된 COMPLETED도 함께 취소된다(`ProductAnalysisJobFinalizationServiceMySqlIT`로 실제 MySQL에서 검증).
- 트랜잭션 Commit 성공 후에만 `DeleteMessage`를 호출한다(`SqsAnalysisJobHandler.Outcome.DELETE`만 Poller가 삭제를 수행).
- `analysis_result.analysis_id`에 DB UNIQUE 제약을 걸어 마지막 중복 방어선으로 쓴다.
- stale 기록·판정을 모두 MySQL `UTC_TIMESTAMP(6)` 한 시계로 통일한다. processing_started_at을 기록하는 Worker와 그 값을 stale로 판정하는 Worker는 서로 다른 JVM/호스트일 수 있어, 애플리케이션이 계산한 절대시각을 주고받으면 두 JVM의 시계·timezone 설정 차이가 그대로 판정 오류가 된다 - 실제로 이 문제를 Testcontainers MySQL(UTC 세션)과 JVM 기본 timezone(Asia/Seoul)이 다른 환경에서 재현했다(방금 선점된 PROCESSING이 9시간 "오래된" stale로 잘못 판정됨). 그래서 `claimForProcessing`은 절대시각이 아니라 "몇 microsecond보다 오래됐는가"라는 상대 기간(`staleAfterMicros`)만 받고, 기록·비교를 모두 MySQL 서버가 자체 UTC 시계로 한 번에 수행한다. 이 메서드만 HQL이 아니라 native query를 쓴다(`UTC_TIMESTAMP()`/`TIMESTAMPDIFF()`를 HQL로 이식성 있게 표현할 수 없음, 프로젝트가 MySQL 8.4로 고정돼 있어 native query 허용).
- 기존 `processing_started_at IS NULL`인 `PROCESSING`(Day4 이전, 이 컬럼이 없던 시절에 생성됐을 row)은 즉시 stale로 취급해 재선점 가능하게 한다.

**NULL 호환 정책의 전제**: 이 정책은 구버전 Worker가 모두 종료된 격리 배포를 전제로 한다. 구버전 Worker가 동시에 실행되는 rolling deployment에서는, 정상 처리 중인 `processing_started_at IS NULL` lease를 새 Worker가 즉시 재선점해버릴 위험이 있다. 이번 범위에는 그런 배포 전략에 대한 별도 처리(예: 배포 순서 강제, 마이그레이션 배리어)를 두지 않았다.

**정확성 보장의 정확한 표현**:

```text
보장:
- analysisId당 최종 Commit된 result 최대 1건
- lease를 잃은 Worker의 늦은 완료/실패 차단(fencing)
- result INSERT와 COMPLETED 상태 전이의 원자성(한 트랜잭션)

보장하지 않음:
- SQS exactly-once delivery(SQS Standard는 at-least-once)
- Processor exactly-once execution(같은 analysisId가 여러 Worker에서 중복 실행될 수 있음 - claim에서 진 쪽만 실행되지 않을 뿐, 진 claim 자체는 여러 번 시도될 수 있다)
- DB 상태 변경과 SQS 삭제/DLQ 이동의 원자성(서로 다른 시스템의 별개 이벤트)
```

"정확히 한 번 처리"라고 뭉뚱그려 표현하지 않는다 - **중복 실행(claim 경쟁, retryable 재시도)은 가능하지만 최종 결과 Commit은 한 건**이라는 것이 이 설계가 실제로 보장하는 정확성이다.

**선점 실패 규칙**(`claimForProcessing`이 0건을 반영했을 때, `SqsAnalysisJobHandler.handleClaimFailure`):

```text
COMPLETED / FAILED
  → terminal duplicate → DELETE

PROCESSING
  → 다른 Worker 처리 중 또는 아직 stale이 아님 → RETAIN

PENDING / QUEUED / PUBLISH_FAILED
  → 조회와 UPDATE 사이 race(경고 로그) → RETAIN

job 없음
  → 데이터 불일치(경고 로그) → RETAIN
```

Day3의 "job 없음이면 DELETE" 규칙은 Day4에서 이 표로 완전히 교체됐다 - job이 없는 것은 정상 흐름에서 나타나지 않아야 할 데이터 불일치이므로, 함부로 메시지를 지우지 않는다.

**retry/DLQ 규칙**(`SqsAnalysisJobHandler.handleTransientFailure`):

```text
retryable + ApproximateReceiveCount < maxReceiveCount
  → PROCESSING 유지, DELETE 안 함(재시도를 위해 메시지 유지)

retryable + ApproximateReceiveCount >= maxReceiveCount
  → failIfOwned로 fenced FAILED Commit
  → DELETE 안 함
  → SQS Redrive Policy에 DLQ 이동을 위임(Worker가 직접 DLQ로 보내지 않음)
```

- 애플리케이션의 `analysis.worker.max-receive-count`와 Main Queue Redrive Policy의 `maxReceiveCount`는 반드시 같아야 한다(다르면 애플리케이션이 FAILED로 확정한 시점과 SQS가 실제로 DLQ로 옮기는 시점이 어긋난다).
- `ApproximateReceiveCount`는 Processor 실제 실행 횟수가 **아니다**. 선점 실패(claim 0건으로 RETAIN한 delivery), 아직 stale이 아닌 PROCESSING 재노출, 소비 시도 자체도 이 count를 증가시킬 수 있다.
- `ApproximateReceiveCount`는 SQS가 제공하는 근사(approximate) 속성이며, DB와 공유되는 원자적 카운터가 아니다.
- 값이 누락되거나 파싱에 실패하면(`ReceivedQueueMessage.approximateReceiveCount() == null`) 재시도 소진 여부를 판단할 수 없으므로 보수적으로 RETAIN하고 `failIfOwned`를 호출하지 않는다.
- 영구 오류(S3 `NoSuchKeyException`, `AnalysisPermanentFailureException`)는 이 표와 다른 경로다 - `failIfOwned` Commit 성공 시 **DELETE**한다(재시도 자체가 의미 없는 확정적 오류라 DLQ 이동을 기다릴 이유가 없다). retryable 소진과 영구 오류를 같은 `failIfOwned` 호출로 처리하지만, DELETE/RETAIN 판단은 Handler가 호출 맥락(어느 예외로 왔는지)에 따라 다르게 내린다.
- `ChangeMessageVisibility` 기반 heartbeat/backoff는 이번 범위 밖이다 - lease 연장을 시도하지 않고, "적당한 시간 안에 응답이 없으면 다른 Worker가 이어받는다"는 단순한 모델만 쓴다.

**DB와 DLQ의 비원자성**:

```text
DB FAILED Commit과 SQS DLQ 이동은 서로 다른 시스템의 별개 이벤트다.
두 사건은 동시에 Commit되지 않으며, 이번 스프린트에서는 최종적으로 두 값(analysisId)이
서로 대응하는지만 관찰했다 - "동시에" 또는 "원자적으로" 일어난다고 표현하지 않는다.
```

Crash 범위별 복구 가능성:

```text
maxReceiveCount 이전 crash
  → 메시지가 Visibility Timeout 이후 Main Queue에 재노출됨
  → 재노출된 delivery가 stale 재선점을 트리거
  → 자동 복구 가능(Day4 3단계에서 실제 검증)

최종 receive(ApproximateReceiveCount == maxReceiveCount) 중 crash
  → 그 delivery는 DLQ로 이동할 수 있음(Redrive Policy가 마지막 receive를 이미 소진된 것으로 셈)
  → 그러나 DB에는 fenced FAILED Commit이 없었으므로 이전 worker_id의 PROCESSING으로 남을 수 있음
  → Main Queue에 더 이상 그 analysisId의 메시지가 없으므로(DLQ로 이미 이동) stale 재선점을
    트리거할 delivery 자체가 없다 - 이 PROCESSING은 자동으로 복구되지 않는다
  → DLQ 기반 운영 reconciliation이 필요하다(이번 범위에서 구현하지 않음)
```

**Reconciliation(구현하지 않음, 필요성과 절차 후보만 기록)**: DLQ에 쌓인 메시지의 `analysisId`와 DB 상태를 대조하는 운영 절차가 필요하다.
- 후보 1: DLQ의 `analysisId`가 DB에서 여전히 `PROCESSING`(비정상적으로 오래됨)이면 운영자가 수동으로 `FAILED`로 정리.
- 후보 2: 원인이 일시적(예: 배포 중 재시작)이면 수정 후 Main Queue로 재발행.
- 후보 3: DB가 이미 `COMPLETED`/`FAILED`(다른 delivery가 먼저 끝낸 경우)면 DLQ 메시지만 정리.
- 자동화(Scheduler, DLQ polling reconciler)는 이번 범위 밖이다.

**Trade-off**: heartbeat가 없으므로 stale threshold(80초)가 지나기 전까지는 실제로 죽은 Worker의 job을 아무도 재선점하지 않는다 - 복구는 "빠름"이 아니라 "설정된 시간 안에 확실함"을 목표로 한다. `ApproximateReceiveCount`가 근사치라 재시도 횟수 판단에 약간의 오차가 있을 수 있다(선점 실패도 count를 올리므로 실제 Processor 실행 횟수보다 크게 나올 수 있음) - 그래도 `maxReceiveCount`를 절대 초과하지 않는 안전한 방향의 오차다.

**Current Mitigation**: Day4 3단계(`SqsAnalysisJobRecoveryLocalStackIT`)에서 LocalStack SQS(+DLQ/Redrive)·S3와 Testcontainers MySQL로 다음을 실제로 검증했다.
- 같은 `analysisId` 메시지 2개(latch로 경쟁 구간 보장) → 최초 claim은 정확히 한 Worker만 성공, 최종 `analysis_result` 1건, 남은 중복은 terminal 확인 후 DELETE.
- 첫 5xx → 실제 재노출(`ApproximateReceiveCount` 1→2 증가 확인) → stale 재선점 → 성공.
- 계속 5xx → `ApproximateReceiveCount`가 `maxReceiveCount`에 도달한 시점에 fenced FAILED Commit(삭제 안 함) → 실제 SQS Redrive Policy로 DLQ 이동 → DLQ body의 `analysisId`가 DB `FAILED` job과 최종 대응.
- Visibility Timeout 초과 → B가 stale 재선점(affected=1) → A의 늦은 `completeIfOwned`는 affected=0(LEASE_LOST, `ProductAnalysisJobFinalizationService` WARN 로그로 analysisId/workerId 직접 확인) → A는 RETAIN, B의 결과를 덮어쓰지 않음.
- LEASE_LOST를 겪은 동일 Poller 스레드가 이후 다른 `analysisId` 메시지를 정상 claim·완료(새 Poller를 만들지 않고 같은 스레드 인스턴스로 확인).
- 별도 claimant child process(`ClaimAndCrashMain`, Spring/S3/SQS를 부팅하지 않는 최소 harness)가 DB claim을 실제로 Commit한 뒤 `Process.destroyForcibly()`로 강제 종료되고, 실제 Worker B가 stale 재선점·완료하는 복구 경로를 검증했다. **전체 `experiment-worker` Spring Boot 프로세스와 S3/SQS 연결을 포함한 완전한 Worker E2E kill -9는 검증하지 않았다** - 프로덕션 `S3Config`가 LocalStack endpoint override를 지원하지 않아(실제 AWS 엔드포인트 고정) 전체 프로세스를 LocalStack에 붙이는 것이 불가능했고, 이 범위를 여는 `S3Config` 변경은 Day4 범위 밖이라 시도하지 않았다.

**Residual Risk**:
- 최종 receive 중 crash는 DLQ reconciliation 없이는 자동 복구되지 않는다(위 "Crash 범위별 복구 가능성" 참고).
- LocalStack 3.5의 DLQ 이동 평가 시점(관찰상 Main Queue에 대한 다음 receive 시도 시점에 평가되는 것으로 보임)이 실제 AWS SQS와 다를 수 있다 - 이번 스프린트의 관찰을 AWS 타이밍으로 일반화하지 않는다. 실제 AWS 복구 시간 측정은 Day14 범위다.
- 전체 Worker 프로세스(experiment-worker Spring Boot + 실제 S3/SQS 연결) 장애 주입은 이번 테스트에서 검증하지 않았다 - claimant 프로세스만 최소 harness로 검증했다.
- `ddl-auto:update`는 현재 실험 환경 방식이며, 운영 migration 전략(Flyway/Liquibase 등)은 별도 과제다.
- `AnalysisResult.rawResult`는 Day4 검증용 임시 payload(`AnalysisProcessor`의 `AnalysisPayload.rawResult()`를 그대로 저장)이며 최종 AI 결과 스키마가 아니다 - `#86`/`analysis_result` 최종 컬럼은 스프린트 중 별도로 확정한다(ADR-17 참고).
- `ClaimAndCrashMain`은 `claimForProcessing`의 SQL을 문자열로 복제하고 있다 - `ProductAnalysisJobRepository.claimForProcessing`의 쿼리를 바꿀 때는 이 테스트 지원 클래스도 함께 갱신해야 한다는 유지보수 위험이 있다. 이 중복을 없애기 위한 프로덕션 리팩터링(예: SQL을 공유 상수로 추출)은 이번 범위에서 하지 않았다.
- 구버전 Worker가 살아있는 rolling deployment에서의 NULL lease 재선점 위험(위 "NULL 호환 정책의 전제" 참고).

**Future**: 조건부 선점과 fencing 원리는 큐 구현과 무관한 DB 상태 모델 원리다 - 기존 Redis Streams 경로(Consumer Group 기반 at-least-once 전달)에도 같은 claim/finalization 경계를 적용할 수 있지만, 이번 Day4 범위에서는 SQS Worker 경로에만 구현했고 Redis Consumer 비즈니스 코드는 손대지 않았다(`docs/infra-sprint/redis-streams-audit.md` 참고). DLQ reconciliation 자동화, `ChangeMessageVisibility` 기반 heartbeat, 전체 Worker 프로세스 kill 검증, 실제 AWS 복구 시간 측정은 Day5 이후(또는 Day14)로 남긴다.

**Boundary Note**: `product_analysis_jobs`(SQS 비동기 발행/선점/fencing orchestration record)와 `analysis_result`(Day4 임시 결과 저장)만 이 ADR의 대상이다. 기존 `analyze.domain.ProductAnalysisSession`(Redis Streams 기반 Vision→Pricing 사용자 플로우 세션)과 그 쪽 비즈니스 처리 코드는 이번 ADR과 Day4 구현 어디에서도 변경하지 않았다(ADR-12의 Boundary Note와 동일한 경계를 유지한다).
