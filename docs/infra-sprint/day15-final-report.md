# Day 15 최종 보고서 — Terraform PoC + autique-exp 실험 환경 정리 + 큐 도입 결정

작성 시각: 2026-09-22 (Day 15-2, 모든 작업 완료 후)

## 1. Day 15 목표

1. Terraform으로 별도 저비용 `tf-dev` 환경(VPC/subnet/route table/SG/SQS/ECR)을 생성·검증하고,
   기존 수동 `autique-exp` 환경은 건드리지 않는다.
2. 기존 실험 자원(`autique-exp`)의 최종 cleanup을 진행하되, 삭제 전 정확한 대상을 재확인하고
   삭제 후 잔존물이 없는지 전체 Region을 스캔한다.
3. Day 4/11~14 SQS PoC와 Redis Streams 감사 결과를 근거로 **큐 도입 여부를 최종 결정**한다.
4. 비용을 다각도(Pricing Calculator 예상치, Cost Explorer gross usage, Budget Actual, credits)로
   캡처하고 사실 관계를 정리한다.

## 2. 실제 수행 순서 — 원래 계획과 다르게 진행됨

> **원래 계획**(`docs/infra-sprint/day15-decision-criteria.md` 작성 시점 전제)에는 기존 실험
> 자원을 먼저 삭제한 뒤 Terraform을 실행하는 순서가 암묵적으로 전제돼 있었다. **실제로는
> 반대 순서로 진행했다** — 이 사실을 숨기지 않고 그대로 기록한다.

실제 순서:

1. **Terraform tf-dev 코드 작성 → `fmt`/`validate`/`plan` → `apply`(8개 자원 생성) → 변경 plan
   실험(`visibility_timeout_seconds` 30→60) → 원복 → `plan -destroy` → 저장된 destroy plan
   적용 → 전체 삭제 확인.** (`artifacts/day15/terraform-*.txt` 전체)
2. **그다음** 기존 `autique-exp` 수동 환경의 읽기 전용 precheck → 사용자 승인 → 단계적 cleanup
   (RDS/S3/SNS/IAM/SSM → CloudWatch/SQS/ECR/EC2/RDS 네트워크 잔존물/VPC 전체 해체) →
   전체 17개 활성 Region 잔존 스캔.

**tf-dev가 `autique-exp`를 참조·변경하지 않았다는 검증 결과**: tf-dev VPC CIDR
`10.30.0.0/16`은 `autique-exp-vpc`의 `10.20.0.0/16`, 기본 VPC의 `172.31.0.0/16`과 겹치지 않게
사전 확인 후 선택했다(`artifacts/day15/terraform-plan.txt`의 plan에 `data` 소스로 기존 VPC를
조회하는 코드 없음 — `terraform/tf-dev/*.tf` 전체가 신규 리소스만 선언). tf-dev는 별도 local
state(`terraform/tf-dev/terraform.tfstate`, git 추적 제외)를 사용했고, `autique-exp` 관련 리소스
ID는 tf-dev state에 단 하나도 등장하지 않는다(`artifacts/day15/tf-dev-resource-verification.txt`
로 8개 자원 전부 신규 ID임을 AWS API로 직접 재확인). tf-dev destroy 후에도
`artifacts/day15/terraform-destroy-verification.txt`에서 기존 `autique-exp-vpc(10.20.0.0/16)`
CIDR/State가 불변임을 재확인했다.

이 순서 자체가 결과에 미친 영향: tf-dev 검증을 먼저 마쳐 Terraform 코드/state/파이프라인이
독립적으로 동작함을 먼저 증명한 뒤, 더 위험도가 높은(실제 프로덕션 유사 자원인)
`autique-exp` 삭제를 진행했다 — 순서를 바꾼 것이 오히려 "낯선 도구(Terraform)로 먼저
연습한 뒤 실물을 지운다"는 방향이라 리스크 관점에서는 원래 계획보다 보수적이었다.

## 3. Evidence export

Day 15 시작 시점 스냅샷: `artifacts/day15/evidence-20260922T083413Z/`(22개 파일 + `SHA256SUMS.txt`).
SHA256SUMS 재검증 **22/22 OK, 실패 0**(`git.md`가 아니라 이 보고서 작성 시점에 재실행,
`docs/infra-sprint/cost-and-cleanup.md` §5 참고). `logs/api.json.gz`(6,576,722줄),
`logs/worker.json.gz`(24,190줄) 둘 다 `gzip -t` 통과, 정상 압축 해제 확인.

비용 재조회 스냅샷: `artifacts/day15/day15-2-20260922T103508Z/`(Budget 3개, Cost Explorer
gross/credits/net, identity, evidence-audit). 상세는 `docs/infra-sprint/cost-and-cleanup.md`.

## 4. Terraform (tf-dev) 요약

| 단계 | 결과 | evidence |
|---|---|---|
| `fmt` | 자동 정렬 1건(공백) | `artifacts/day15/terraform-plan.txt` 생성 직전 |
| `validate` | Success | 동일 세션 로그 |
| `plan`(최초) | 8 to add, 0 change, 0 destroy | `artifacts/day15/terraform-plan.txt` |
| 보안 baseline 추가 후 재plan | 8 to add(scan_on_push=true, sqs_managed_sse_enabled=true 반영) | `artifacts/day15/terraform-plan.txt`(갱신본) |
| `apply` | **Apply complete! Resources: 8 added, 0 changed, 0 destroyed** | `artifacts/day15/terraform-apply.txt` |
| 변경 plan 실험(`visibility_timeout_seconds` 30→60) | 0 to add, **1 to change**, 0 to destroy, 대상 `aws_sqs_queue.main` 단 하나 | `artifacts/day15/terraform-change-plan.txt` |
| 원복 후 재plan | **No changes** | `artifacts/day15/terraform-change-reverted-plan.txt` |
| `plan -destroy` | 0 to add, 0 to change, **8 to destroy**(state의 tf-dev 8개뿐) | `artifacts/day15/terraform-destroy-plan.txt` |
| `apply`(저장된 destroy plan) | **Apply complete! Resources: 0 added, 0 changed, 8 destroyed** | `artifacts/day15/terraform-destroy.txt` |
| destroy 후 `state list` | 비어 있음(0개) | `artifacts/day15/terraform-destroy-verification.txt` |
| destroy 후 `plan`(코드는 그대로 존재) | **8 to add**(정상 — §5 참고, "No changes"가 아님) | 동일 파일 |

**중요 참고사항 한 줄**: destroy 후 `.tf` 코드가 그대로 남아 있으므로 plan이 "No changes"가
아니라 "8 to add"로 나오는 것은 정상이다 — state/AWS는 비어 있고 코드만 남아 재생성 대상을
정확히 보고한 것이지, destroy가 실패했거나 불완전했다는 뜻이 아니다.

생성했던 8개 자원(전부 검증 후 삭제 완료, 현재 AWS에 없음): `aws_vpc`(10.30.0.0/16),
`aws_subnet`, `aws_route_table`+association, `aws_security_group`, `aws_sqs_queue`(main+DLQ,
`sqs_managed_sse_enabled=true`), `aws_ecr_repository`(`scan_on_push=true`).

## 5. 기존 `autique-exp` 최종 cleanup

읽기 전용 precheck(`artifacts/day15/autique-exp-cleanup-precheck.txt`) → 사용자가 RDS
final-snapshot 미생성, S3 808개 객체 백업 후 삭제, SNS/IAM 5개 role/SSM 실험 파라미터 삭제를
결정 → 삭제 실행(`artifacts/day15/cleanup-execution-log.txt`) → 이어서 CloudWatch/SQS/ECR/EC2/
RDS 네트워크 잔존물/VPC 전체 해체(`artifacts/day15/final-cleanup-execution.txt`).

### 삭제된 자원

- RDS `autique-experiment-mysql`(`--skip-final-snapshot`) — 자동 백업 5개는 별도 삭제 명령 없이
  AWS가 지연된 비동기 정리로 스스로 제거(`artifacts/day15/rds-retained-backup-delete.txt`).
- S3 `autique-exp-<AWS_ACCOUNT_ID>-ap-northeast-2` — **808/808 객체**를
  `artifacts/day15/private-backup/`(git 추적 제외)에 검증 백업(원격 개수/바이트/SHA256/실패 0건
  전부 일치, `artifacts/day15/s3-backup-verification.json`) 후 버킷 삭제.
- SNS `infra-alerts`, IAM 5개 role(`AutiqueExperimentApi/Worker/Load/DeployRole`,
  `GitHubDeployRole`) + instance profile 3개, SSM 파라미터 33개.
- CloudWatch alarm 6개 + dashboard 1개 + log group 2개(`/app/api`, `/app/worker`).
- SQS `autique-exp-analysis-job-queue` + DLQ, ECR `autique-exp-app`(이미지 8개, evidence export
  digest와 완전 일치 확인 후 force delete).
- EC2 3대(API A/Worker A/Load) terminate — root volume 3개 `DeleteOnTermination=true`로 자동 삭제.
- `autique-exp-vpc(10.20.0.0/16)` 전체: VPC endpoint → IGW → SG 간 상호 참조 규칙 4건 revoke →
  SG 6개 → subnet 4개 → route table → **VPC 삭제까지 DependencyViolation 0건으로 완료**.

### 보존된 자원

Budget 3개(`infra-sprint-85`/`infra-sprint-daily-8`/`account-monthly-110`), GitHub OIDC provider,
AWS service-linked role 6개, 기본 VPC(`172.31.0.0/16`), 무관한 S3 버킷 2개
(`aca-used-goods-images-2026`, `vintic-mvp-bucket-123`), 로컬 `evidence-*`/`private-backup`
전체, Terraform 코드/증거.

## 6. 전체 Region orphan 검사

17개 활성(opt-in-not-required) Region 전부 스캔: `autique-exp`/`Project=auction-infra` 태그·이름
자원이 **ap-northeast-2 외 어디에도 없음**을 확인(`resourcegroupstaggingapi` + 서비스별 이름
필터 병행). 나머지 17개 Region은 계정이 opt-in한 적 없어 구조적으로 자원을 가질 수 없어
스캔 대상에서 제외(사유 명시, `artifacts/day15/final-cleanup-execution.txt`).

ap-northeast-2 전체 0건 검증(EC2 terminated 3/3, SQS 0, ECR 0, CloudWatch alarm/dashboard/
log group 0, RDS/subnet group/backup/snapshot 0, VPC NotFound, VPC 하위 subnet/SG/route
table/ENI/endpoint 0, ALB/TG/NAT/ElastiCache 0) — `artifacts/day15/final-cleanup-verification.txt`.

## 7. 큐 도입 여부 결정

**결정: Redis Streams 유지 + 복구·관측 보완.** 상세 근거, 비교표, 보완안 7개, 담당자
제안문은 `docs/infra-sprint/queue-adoption-decision.md` 전체 참고 — 이 문서에서 반복하지
않는다. 핵심만 요약하면, SQS PoC는 정합성 게이트 5개(fencing/SIGTERM/재시도+DLQ/kill-9
복구시간 포함)를 전부 PASS했다(kill -9 복구 재검증 결과 **110.6초**로 예산 약 160초 이내 —
Worker가 요청마다 명시하는 실효 Visibility Timeout은 90초(Queue 기본값 120초와는 별개,
`SqsAnalysisJobPoller.java` 코드+테스트로 확정)+정상 처리시간 70초, §8-4,
`experiment-results.md` §1 참고). 그럼에도 지금 SQS로 전환하지 않는 이유는 게이트 실패가 아니라 (1) DB FAILED와
DLQ 이동이 원자적이지 않고(97초 간극), (2) `RealAnalysisProcessor`가 한 번도 붙어본 적이
없어(ADR-17 SKIPPED) 프로덕션 전환 시 변경 범위가 Redis 보완보다 크며, (3) SQS PoC와 Redis
Streams가 애초에 "큐 제품만의 동일 조건 비교"가 아니라는 점(SQS 쪽에만 fencing/재시도/
오류분류가 이미 구현돼 있음) 때문이다.

## 8. 핵심 수치 (evidence 기준, 최소 5개)

1. Terraform apply: **8 to add / 0 change / 0 destroy** → destroy: **0/0/8**
   (`artifacts/day15/terraform-apply.txt`, `terraform-destroy.txt`)
2. S3 백업 검증: **808/808 객체, 37,947/37,947 bytes, 실패 0건**
   (`artifacts/day15/s3-backup-verification.json`)
3. Evidence SHA256SUMS: **22/22 OK**(`docs/infra-sprint/cost-and-cleanup.md` §5 재검증 포함)
4. Worker kill -9 복구: **110.6초** (DB `updated_at`과 CloudWatch 완료 로그 교차검증, 오차
   84ms — `artifacts/day14/11-worker-failures-20260921T105107Z.txt`, `experiment-results.md`
   §1. 같은 파일의 `RECOVERY_SECONDS=802`는 완료 감지에 실패한 폴링 스크립트의 버그값으로
   재검증되어 채택하지 않는다 — 최초 정정 시 "802초"를 원본으로 잘못 채택했던 것을 바로잡음)
5. Redis Streams crash 후 자동 복구: **600초 관찰 동안 0회**(idle 27,741ms → 639,631ms까지
   단조 증가, `artifacts/day14/21-redis-baseline-crash-20260921T114948Z.txt`)
6. API A 중지(B1): **2,973건 중 46건 실패**, Redis 차단(B3): **355건 중 44건 실패**(추천 API만,
   DB 직접 경로는 0건) — `artifacts/day14/31-b1-...txt`, `33-b3-...txt`

## 9. 완료 조건 체크

**PASS**:
- [x] Terraform apply/change-plan/원복/destroy 전 단계 evidence 보존, tf-dev가 기존
      `autique-exp`를 참조·변경하지 않음 (§2, §4)
- [x] `autique-exp` 전체 cleanup(precheck·사용자 승인·삭제 후 검증 3단계 로그 보존) (§5)
- [x] 전체 17개 활성 Region orphan 스캔 완료, 잔존물 0건 (§6)
- [x] 큐 도입 여부 결정 및 근거 문서화(`queue-adoption-decision.md`)
- [x] 문서 조립 완료(A~J 10개 산출물)
- [x] 원본 evidence 보존(모든 수치가 파일 경로:줄번호로 추적 가능)
- [x] Credits/Bills 캡처 수치 텍스트화 완료 — `cost-and-cleanup.md` §5(실제 remaining
      $81.17/used $78.83, 예상 remaining $51.43/used $108.57, 9월 예상 결제액 $0.00,
      Bill status Pending)
- [x] Worker kill -9 복구시간 재검증 — **110.6초로 확정**(DB `updated_at`과 CloudWatch
      완료 로그 교차검증, 오차 84ms). 이전 버전의 "802초"는 완료 감지에 실패한 폴링
      스크립트의 버그값으로 확인되어 폐기(`experiment-results.md` §1)
- [x] Day13 queue-wait 그래프 PNG 생성 완료 (`images/day13-queue-wait.png`, PIL 기반, N=10/50/100 3회 평균)

**PENDING**:
- [ ] 2026-09-24 이후 Cost Explorer/Budget/Bills 재확인 — 비용 반영 지연으로 이번 스냅샷
      (2026-09-22)이 Day15 cleanup 이후 상태를 완전히 반영하지 못했을 수 있음
      (`cost-and-cleanup.md` §12)
- [ ] Pricing Calculator 사전 견적 원본 — 계획 단계 증거 누락으로 확인, 실제 대비 정밀
      비교 불가(비용 통제 실패를 의미하지 않음, `cost-and-cleanup.md` §2)

## 10. 관련 문서 · evidence 링크

- 큐 도입 결정: [`queue-adoption-decision.md`](./queue-adoption-decision.md)
- 실험 결과 전체: [`experiment-results.md`](./experiment-results.md)
- 비용/cleanup 상세: [`cost-and-cleanup.md`](./cost-and-cleanup.md)
- 아키텍처/흐름도: [`architecture-and-flows.md`](./architecture-and-flows.md)
- SG/IAM 최종 상태: [`sg-iam-matrix.md`](./sg-iam-matrix.md)
- ADR 인덱스: [`../infra-adr/README.md`](../infra-adr/README.md)
- 한계와 제외 범위: [`limitations-and-exclusions.md`](./limitations-and-exclusions.md)
- evidence 인덱스(Day11~15): [`../../artifacts/day15/README.md`](../../artifacts/day15/README.md)
- Terraform 코드: [`../../terraform/tf-dev/`](../../terraform/tf-dev/)
