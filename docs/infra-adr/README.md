# Infra ADR 인덱스

작성 시각: 2026-09-22. `docs/infra-adr/`에 실제로 존재하는 파일만 표에 넣었다 — 내용을
새로 창작하지 않았다. 번호는 ADR-01~ADR-19 범위를 전수 확인했다.

## ADR 01~19 존재 여부 및 상태

| 번호 | 제목 | 상태 | 핵심 결정 |
|---|---|---|---|
| ADR-01 | — | **없음** | — |
| ADR-02 | — | **없음** | — |
| ADR-03 | — | **없음** | — |
| ADR-04 | 조건부 Claim, workerId Fencing, ApproximateReceiveCount 기반 재시도/DLQ | Accepted(명시됨) | SQS 경로(`ProductAnalysisJob`) 상태 전이를 단일 조건부 UPDATE(`claimForProcessing`)로 원자적 선점, `worker_id` fencing, 결과 UNIQUE를 최종 방어선으로 사용 |
| ADR-05 | — | **없음** | — |
| ADR-06 | — | **없음** | — |
| ADR-07 | — | **없음** | — |
| ADR-08 | — | **없음** | — |
| ADR-09 | — | **없음** | — |
| ADR-10 | Scheduler 중복 실행과 경매 종료 vs 입찰 정합성 | 상태 필드 없음(Decision+Current Mitigation로 구현 확정 판단) | 분산 락(ShedLock) 대신 DB row lock(`FOR UPDATE`) + 재검증으로 다중 인스턴스 Scheduler 정합성 보장. `AuctionEndOutcome` 반환값으로 실제 종료 건수 노출 |
| ADR-11 | **예약됨(TODO, 아래 참고)** | 미작성 | — |
| ADR-12 | DB Commit과 SQS Publish 사이 Dual-Write, Outbox 대신 조건부 UPDATE+재발행으로 완화 | 상태 필드 없음(Decision 구체적) | Transactional Outbox 미도입. DB UNIQUE + 조건부 UPDATE + 상태 재조회 + `PUBLISH_FAILED` 수동 재발행으로 dual-write 위험 완화 |
| ADR-13 | — | **없음** | — |
| ADR-14 | — | **없음** | — |
| ADR-15 | — | **없음** | — |
| ADR-16 | Internal ALB + HTTP, 공개 HTTPS 미적용 | 상태 필드 없음(Decision 구체적) | 실험 환경 진입점을 Internal ALB + HTTP 80으로 제한, 도메인/Route53/ACM 미도입 |
| ADR-17 | AnalysisProcessor로 AI 파트 의존성 격리 | 상태 필드 없음(Decision 구체적, "Day 3 Update"로 갱신 이력 있음) | AI/가격 계산을 `AnalysisProcessor` 인터페이스 뒤로 격리. `RealAnalysisProcessor`는 Day3에서 **SKIPPED** 확정(어댑터 미구현) |
| ADR-18 | — | **없음** | — |
| ADR-19 | 애플리케이션·JVM·DB의 시각 기준 불일치 | 명시적으로 "이번 스프린트는 현상 유지" | Clock(KST)/JVM(UTC)/DB(UTC) 불일치를 이번 스프린트에서는 수정하지 않고 현상만 기록. 운영 전 정식 결정 필요 |

**상태 필드 관련 메모**: ADR-04만 `**Status**: Accepted`를 명시적으로 갖고 있다. ADR-10/12/16/17/19는
별도 Status 필드 없이 `## Decision` 섹션에 확정된 결정문과(대부분) `## Current Mitigation`/
실측 데이터가 있어 사실상 Accepted로 보이지만, 문서 자체에 그렇게 쓰여 있지는 않다 — 원문을
임의로 "Accepted"라고 새로 적어넣지 않았다.

## 누락 ADR TODO 목록

### ADR-11 — 가장 시급 (번호가 이미 예약되어 있었음)

- **제목(예약된 표현 그대로)**: "VPC/Security Group/SQS/ECR을 Terraform으로 코드화"
- **근거**: `docs/day7-aws-cli-runbook.md:8-9, 271-272`에 "infra sprint v3 / ADR-11(Day15)에서
  VPC/Security Group/SQS/ECR을 Terraform으로 옮기고 `terraform apply`/`terraform destroy`를
  실제로 검증할 예정"이라고 **번호까지 명시적으로 예약**돼 있다.
- **필요한 결정**: local state vs remote state, 기존 수동 자원과의 경계(import 여부), 어떤
  자원까지 Terraform 범위에 포함할지(SG/SQS/ECR만인지 VPC 전체인지).
- **근거 evidence**: 이 계획은 Day15에 **실행은 완료됐다**(`docs/infra-sprint/day15-final-report.md`
  §4, `artifacts/day15/terraform-*.txt` 전체) — 다만 그 결정을 정식 ADR 문서로 남기는 작업만
  누락된 상태다. **이 문서 작성 세션에서도 ADR-11 파일 자체는 새로 만들지 않았다** — 결정
  근거(evidence)는 있지만 ADR 형식의 승인 문서화가 없다는 사실만 TODO로 남긴다(규칙: "ADR
  내용을 근거 없이 새로 만들어 채우지 않는다").

### ADR-01, 02, 03, 05~09, 13, 14, 15, 18 — 번호만 비어있음, 제목/내용 불명

이 11개 번호에 대해서는 이 세션에서 그 내용을 암시하는 어떤 근거(다른 문서의 인용, 커밋
메시지, 코드 주석)도 찾지 못했다. 임의로 제목을 지어내지 않는다 — **"무엇을 다뤄야 하는지
이 세션에서 확인 불가"**로만 남긴다. 번호 자체가 예약돼 있었는지(ADR-11처럼) 아니면 애초에
쓰이지 않은 결번인지도 불명이다.

## 최종 결정과의 충돌 점검

기존 ADR 6개(04, 10, 12, 16, 17, 19)를 Day15 최종 결정(`docs/infra-sprint/queue-adoption-decision.md`,
`docs/infra-sprint/day15-final-report.md`)과 대조한 결과 **충돌 없음**:

- ADR-04(조건부 claim/fencing)는 `queue-adoption-decision.md`의 보완안 3(조건부 UPDATE)이
  그대로 인용·재사용하는 근거다 — 상충이 아니라 연장선.
- ADR-12(dual-write 완화)는 Redis Streams 갭 c(조회 후 저장)의 보완 방향과 원리가 같다 —
  상충 없음.
- ADR-17(`RealAnalysisProcessor` SKIPPED)은 큐 도입 결정 문서의 "SQS PoC가 실제 AI 프로세서로
  검증되지 않았다"는 한계 서술과 정확히 일치한다.
- ADR-10(Scheduler 정합성), ADR-16(Internal ALB), ADR-19(시간대)는 큐 선택과 무관한 별도
  주제라 충돌 여지 자체가 없다.
