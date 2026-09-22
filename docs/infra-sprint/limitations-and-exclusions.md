# 한계와 제외 범위

이 문서는 Day11~15 인프라 스프린트 전체에 걸쳐 반복적으로 언급된 한계·제외 범위를 한곳에
모은다. 각 항목의 상세 근거는 원래 등장한 문서를 링크한다 — 중복 서술을 최소화한다.

## 1. Producer가 DB commit 후 publish 전에 종료되어 남는 stale PENDING 자동 복구 미구현

`docs/infra-adr/ADR-12-analysis-job-dual-write.md`의 Trade-off: "`PENDING`에 멈춘 job은
자동으로 복구되지 않는다 — 지금은 `PUBLISH_FAILED`만 재발행 대상이고, `PENDING` 정체는
관측(모니터링/수동 조회)에 의존한다." 이번 스프린트에서 이 자동 복구 로직을 구현하지
않았다.

## 2. DB FAILED와 DLQ 이동의 비원자성

Day14 B2 실험에서 실측: DB `FAILED` 관찰(+200s)과 DLQ 도착(+297s) 사이 **97초 간극**
(`artifacts/day14/32-b2-ai-timeout-dlq-20260921T140923Z.txt`,
`docs/infra-sprint/experiment-results.md` §5). 두 이벤트는 서로 다른 시스템(MySQL commit,
SQS 자체 redrive)에서 별도로 일어나며 단일 트랜잭션으로 묶여 있지 않다.

## 3. Redis vs SQS 비교가 큐 제품 단독 동일 조건 비교가 아님

`docs/infra-sprint/queue-adoption-decision.md` 서두에 명시: SQS PoC 트랙에는 조건부 claim,
`worker_id` fencing, 재시도/DLQ, 오류 분류 같은 **애플리케이션 레벨 개선**이 이미 구현되어
함께 검증됐다. Redis Streams 쪽은 이런 개선이 없는 원래 구조 그대로다 — "SQS라는 큐 제품이
Redis라는 큐 제품보다 우월하다"는 결론으로 읽지 않는다.

## 4. 실제 AI 가격 결과 품질은 평가 범위가 아님

`docs/infra-adr/ADR-17-analysis-processor-boundary.md`: `RealAnalysisProcessor`는 Day3에서
**SKIPPED**로 확정(VisionResult → PricingRequest 사이 사용자 확인 단계가 있어 최소 adapter
변환만으로 연결 불가). 이번 스프린트 전체가 `FakeAnalysisProcessor`(결정적 지연/오류 주입)로만
측정됐다 — 실제 OpenAI 등 AI 응답의 정확도·품질은 어디에서도 측정하지 않았다.

## 5. HTTPS/domain/Route53/ACM 제외

`docs/infra-adr/ADR-16-internal-alb-http.md`: 실험 환경 진입점은 Internal ALB + HTTP 80만
사용하고 도메인 구매, Route53 Hosted Zone, ACM 인증서, HTTPS(443)를 만들지 않았다. Residual
Risk로 "VPC 내부 트래픽 비암호화, 실제 사용자 데이터가 들어가면 부적합"이 명시돼 있다.

## 6. Terraform remote backend 제외

`terraform/tf-dev/versions.tf`가 `backend "local"`을 명시적으로 사용한다 — S3+DynamoDB 같은
remote state backend는 이번 스프린트에서 도입하지 않았다. `terraform.tfstate`는 로컬에만
존재했고 git 추적에서 제외했다(`.gitignore`).

## 7. Budget/Cost Explorer 데이터 반영 지연

`docs/infra-sprint/cost-and-cleanup.md` §13: Cost Explorer/Budgets 데이터는 최대 약 24~48시간
지연될 수 있어, 이 문서가 캡처한 비용 수치는 "최종 확정 비용"이 아니라 조회 시점(2026-09-22)
스냅샷이다. Day15 cleanup으로 인한 비용 감소분이 아직 반영되지 않았을 가능성이 있어
**2026-09-24 이후 Bills 재확인이 필요**하다(미완료).

## 8. CloudWatch 알람 사각지대 개선안은 발견했지만 cleanup 전에 재실험하지 않음

Day14 B1 실험에서 API A 중지 중 `HealthyHostCount` 하락과 5xx 41건 발생에도 6개 알람이
전부 상태 변화 없이 미탐지로 확인됐다(`docs/infra-sprint/experiment-results.md` §13). 이
사각지대(알람 임계값/평가 기간 재설계 필요)를 이번 스프린트에서 식별만 했고, **CloudWatch
자원 자체가 Day15 cleanup으로 이미 삭제됐기 때문에 개선된 알람 설정으로 재실험하지
않았다** — 개선안 검증은 향후 별도 환경에서 다시 진행해야 한다.

## 9. 그 외 확인된 한계 (부록)

- **Worker kill -9 복구 110.6초**(DB/CloudWatch 교차검증, 재정정됨 — 원래 문서의 802초는
  완료 감지 실패 폴링 스크립트의 버그값으로 확인됨)는 단발성 관찰(재실행 없음) — 반복
  측정으로 전형값인지 이상치인지 확인하지 않았다(`experiment-results.md` §1).
- **B1 "약 30초" 영향 시간**은 경로별 6.2~30.4초 폭을 가장 긴 값으로 근사한 것 — 단일
  숫자로 뭉뚱그리지 않는다(`experiment-results.md` §12).
- **DB 동시성 실험(No-lock/Pessimistic/Optimistic)**은 로컬 단일 인스턴스·frozen workload
  결과이며 운영 환경 race 발생 확률로 일반화하지 않는다(`docs/experiments/concurrency/summary.md`
  §Limitations).
- **Optimistic Lock + Retry**는 Idempotency claim과 attempt 커밋이 분리된 트랜잭션이라 crash
  window가 이론상 존재 — production은 여전히 Pessimistic Lock을 사용한다(동상).
- **hikari_active_max/hikari_pending_max**는 Day13 exp2 CloudWatch 수집에서 값이 비어 있어
  (`None`) DB 커넥션 풀 경합을 직접 확인하지 못했다(`experiment-results.md` §9).
- **ALB/ElastiCache 정확한 생성 시각(시·분 단위)**은 API 응답 기반이 아니라 실험 파일
  타임스탬프로 근사했다 — 미검증(`cost-and-cleanup.md` §8).
