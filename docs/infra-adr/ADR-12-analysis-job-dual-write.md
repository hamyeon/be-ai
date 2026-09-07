# ADR-12 DB Commit과 SQS Publish 사이 Dual-Write, Outbox 대신 조건부 UPDATE+재발행으로 완화

**Decision**: `ProductAnalysisJob`의 상태 저장(MySQL)과 SQS 발행을 하나의 원자적 트랜잭션으로 묶지 않는다. 대신 DB `UNIQUE(user_id, idempotency_key)`, 상태 기반 조건부 UPDATE, 매 응답마다의 실제 DB 상태 재조회, `PUBLISH_FAILED` 운영 재발행(`republishFailed`)으로 dual-write 위험을 완화한다. Transactional Outbox는 이번 스프린트에 도입하지 않는다.

**Problem**: "PENDING 저장 → SQS publish → 조건부 UPDATE" 흐름에서 DB 커밋과 SQS 발행은 서로 다른 리소스(MySQL, SQS)에 대한 별개의 동작이라 하나의 트랜잭션으로 묶을 수 없다(2PC 없음). 다음 세 가지 실패 지점이 있다.

1. DB Commit 이후, publish 호출 이전에 프로세스가 죽으면 job이 `PENDING`에 영구히 머무를 수 있다 - 발행된 적도 없고 실패로 기록되지도 않는다.
2. SQS `SendMessage`가 서버 쪽에서는 성공했지만 클라이언트가 응답을 받기 전에 타임아웃/네트워크 오류가 나면, 메시지는 실제로 큐에 있는데 Producer는 실패로 오인해 `PUBLISH_FAILED`를 기록할 수 있다(응답유실 race).
3. SQS Standard 큐는 at-least-once 전달만 보장하므로 같은 메시지가 Worker에게 두 번 이상 전달될 수 있다.

**Alternatives**:
1. Transactional Outbox(같은 DB 트랜잭션에 outbox row를 함께 커밋하고, 별도 relay가 SQS로 전달) — 1번(PENDING 유실)을 구조적으로 없앤다. 다만 relay 프로세스, polling/CDC 인프라, relay 자체의 중복 전달 처리(outbox도 at-least-once)를 새로 만들어야 해 스프린트 기간·복잡도 대비 이득이 크지 않다.
2. 2PC/XA 트랜잭션 — MySQL과 SQS 양쪽이 표준 XA를 지원하지 않아 사실상 불가능.
3. DB UNIQUE + 조건부 UPDATE + 상태 재조회 + 운영 재발행(선택) — 완벽한 원자성은 아니지만 멱등키 UNIQUE로 중복 요청을 흡수하고, 조건부 UPDATE로 상태 역행을 막고, `PUBLISH_FAILED`를 사람이 재발행할 수 있게 해 실제로 관측되는 문제(1·2번)를 완화한다.

**Choice**: 3번.

**Reason**: 이번 스프린트의 검증 목표는 SQS 비동기·멱등성·경쟁조건·확장이지 exactly-once 발행 보장 자체가 아니다. Outbox는 그 자체로 별도 검증이 필요한 컴포넌트(relay 안정성, 재시도, dedup)라 스프린트 범위를 벗어난다. 조건부 UPDATE + 재조회 조합은 이미 이번 트랙(Day2)에서 구현·검증했고, 남은 위험은 운영 재발행(현재는 수동, Day4 이후 자동화 검토)으로 눈에 보이게 다룰 수 있다.

**Trade-off**: `PENDING`에 멈춘 job은 자동으로 복구되지 않는다 - 지금은 `PUBLISH_FAILED`만 재발행 대상이고, `PENDING` 정체는 관측(모니터링/수동 조회)에 의존한다. SQS Standard의 중복 전달은 이번 트랙에서 막지 않는다 - Worker가 멱등하게 처리해야 한다.

**Current Mitigation**: `UNIQUE(user_id, idempotency_key)`로 동시 중복 요청이 DB row 1건으로 수렴한다(`ProductAnalysisJobClaimService`). 모든 상태 전이가 조건부 UPDATE(`status IN (...)` WHERE절)라 Producer가 이미 `PROCESSING`/`COMPLETED`로 넘어간 job을 덮어쓰지 못한다(`ProductAnalysisJobRepository`). 모든 API 응답은 조건부 UPDATE 직후 실제 DB 상태를 재조회해서 반환하므로, 낙관적으로 가정한 상태가 아니라 실제 상태가 노출된다(`ProductAnalysisJobService.submit/republishFailed`). `PUBLISH_FAILED → QUEUED` 재발행 골격(`republishFailed`, 관리자 API는 아직 없음)으로 2번 실패를 사람이 복구할 수 있다.

**Residual Risk**: DB Commit 직후~publish 호출 직전 크래시로 인한 `PENDING` 정체는 지금 자동 감지·복구 수단이 없다(alerting/재발행 스케줄러 없음). SQS 중복 전달에 대한 Worker 측 멱등 처리(선점/fencing)가 아직 없다 - 같은 job이 두 Worker에서 동시에 `PROCESSING`으로 넘어가는 것을 지금은 막지 못한다.

**Future**: 더 강한 전달 보장(발행 유실 자체를 구조적으로 없앰)이 필요해지면 Transactional Outbox가 다음 대안이다. Day4에서 Worker 선점(lease)과 fencing token을 추가해 중복 전달·stale 재선점을 다룬다.

**Boundary Note**: `ProductAnalysisJob`(SQS 비동기 발행/선점 orchestration record)은 기존 `analyze.domain.ProductAnalysisSession`(Redis Streams 기반 Vision→Pricing 사용자 플로우 세션)과 이름은 비슷하지만 생명주기와 상태 전이 방식이 근본적으로 다르다(전자는 조건부 UPDATE, 후자는 엔티티 메서드+예외). 따라서 하나로 합치지 않고 별도 엔티티/테이블(`product_analysis_jobs`)로 분리했다 - 기존 Vision/Pricing 경로(Redis Streams, `AnalysisTaskProducer`/`Consumer`)는 이번 트랙에서 전혀 수정하지 않았다.
