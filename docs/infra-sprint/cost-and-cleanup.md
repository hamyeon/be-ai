# Cost and Cleanup — Day 15

작성 시점(기준 시각, 이 문서의 모든 비용 수치가 "확정"이 아니라 이 시점 스냅샷임을 의미):
**2026-09-22 07:12~10:35 KST 사이 조회 값** (Budget `LastUpdatedTime=2026-09-22T07:12:56+09:00`,
Cost Explorer 조회는 `artifacts/day15/day15-2-20260922T103508Z/`, 폴더명 타임스탬프
`103508Z`=UTC 10:35:08 → KST 19:35:08 기준).

> **이 문서는 "최종 확정 비용"을 주장하지 않는다.** AWS Cost Explorer/Budgets 데이터는
> 반영 지연(보통 최대 24시간)이 있고, 실제 청구서(Bills)는 별도로 재확인이 필요하다(§12).

## 1. 실험 기간

커밋 로그 기준 인프라 스프린트 작업 시작 2026-09-11(`9d1a710 test: add hot auction load test
harness`) ~ Day15 작업 2026-09-22. AWS 자원 생성은 이보다 늦은 시점(IAM Role
`CreateDate=2026-09-14T16:14:44Z` 등)부터 시작된 것으로 보인다 — 정확한 최초 자원 생성
타임스탬프 전수 조사는 이번 세션에서 하지 않았다(미검증).

## 2. Pricing Calculator 예상액

저장소 전체(`docs/`, `artifacts/`, 프로젝트 루트)와 evidence 디렉터리를 다시 검색했다
(`pricing calculator`, `가격 계산기`, 파일명에 `pricing`/`calculator` 포함, PDF/JSON/캡처
전체) — **AWS Pricing Calculator의 견적 원본·캡처·PDF·JSON·금액 그 무엇도 저장된 evidence가
없다.** (`pricing`이 포함된 다른 매치는 전부 `docs/pricing-agent.md`, 백엔드
`product/pricing` 패키지 등 이 프로젝트의 AI 가격 산정 도메인 코드이며 AWS 비용 견적과는
무관하다.)

기록:
- **비용 목표**: 약 10만 원(계획 단계에서 제시된 목표치 — 이번 세션 evidence로 재검증한
  수치가 아니라 계획 문서상의 목표로만 기록한다)
- **하드 가드레일**: 세전 $85 — 이 값은 실제로 `Budget "infra-sprint-85"`의
  `BudgetLimit=$85.0`로 AWS 쪽에 구현돼 있음을 §4에서 직접 확인했다(가드레일 자체는
  evidence 있음).
- **Pricing Calculator 최종 산출물**: 보존된 evidence 없음.
- **따라서 "Pricing Calculator 예상액 대비 실제 비용"의 정밀 비교는 불가능하다** — 비교
  대상인 사전 견적 자체가 없기 때문이다.
- **대신 사용 가능한 비교**: Budget 가드레일($85) vs 실제 gross 비용(§3, ≈$28.04) — 실제
  사용량 기준 비용은 가드레일의 약 33%에 그쳤고, Budget 알림 임계값(50%/85% 초과 알림, §4)도
  발동하지 않았다.
- **이 항목은 계획 단계 증거 누락으로 기록하며, 비용 통제나 정산 자체가 실패했다는 뜻은
  아니다** — 가드레일(Budget)이 실제로 설정돼 있었고 실제 비용이 가드레일을 크게 밑돌았다는
  사실은 별도로 확인됐다.

## 3. Account-wide Gross Usage (Cost Explorer, 계정/전체 Region, 2026-09-03~09-23)

출처: `artifacts/day15/day15-2-20260922T103508Z/cost-gross-by-service.json` (`Estimated: true`)

| 서비스 | UnblendedCost (USD) |
|---|---|
| Amazon Relational Database Service | 12.2956264802 |
| Amazon Elastic Compute Cloud - Compute | 8.3882355648 |
| Amazon Virtual Private Cloud | 2.727466675 |
| Amazon ElastiCache | 2.04 |
| Amazon Elastic Load Balancing | 1.6749434083 |
| EC2 - Other | 0.8823477026 |
| Amazon Simple Storage Service | 0.0221234396 |
| Amazon EC2 Container Registry (ECR) | 0.0051875407 |
| AWS CloudShell | 0.0000002718 |
| AWS Glue / AWS KMS / SNS / SQS / CloudWatch | 0 |
| **합계(수동 합산, Python으로 재검산)** | **≈ 28.04 USD** |

## 4. Budget Actual (`infra-sprint-85`)

출처: `artifacts/day15/day15-2-20260922T103508Z/budget-infra-sprint-85.json`

- `ActualSpend = 12.29 USD`, `ForecastedSpend = 12.10 USD`
- `LastUpdatedTime = 2026-09-22T07:12:56+09:00`
- **`FilterExpression`이 정확히 `REGION=ap-northeast-2 AND RECORD_TYPE=Usage`로 설정돼 있음**
  (원본 JSON 그대로 인용):
  ```json
  "FilterExpression": {
    "And": [
      {"Dimensions": {"Key": "REGION", "Values": ["ap-northeast-2"]}},
      {"Dimensions": {"Key": "RECORD_TYPE", "Values": ["Usage"]}}
    ]
  }
  ```
- **`Metrics: ["UnblendedCost"]`** — Budget이 사용하는 비용 지표는 UnblendedCost다(크레딧을
  차감하지 않은 총액 기준).

### Budget과 동일 필터로 재조회한 서비스별 breakdown

출처: `artifacts/day15/day15-2-20260922T103508Z/cost-budget-scope-by-service.json`
(REGION=ap-northeast-2 AND RECORD_TYPE=Usage로 필터링됨)

| 서비스 | UnblendedCost (USD) |
|---|---|
| Amazon Relational Database Service | 3.7732931666 |
| Amazon Elastic Compute Cloud - Compute | 3.781902228 |
| Amazon Elastic Load Balancing | 1.6749434083 |
| Amazon ElastiCache | 2.04 |
| Amazon Virtual Private Cloud | 0.502466675 |
| EC2 - Other | 0.4903452634 |
| Amazon Simple Storage Service | 0.0220584354 |
| Amazon EC2 Container Registry (ECR) | 0.0051875407 |
| **합계(수동 합산)** | **≈ 12.290 USD (Budget ActualSpend와 일치)** |

## 5. Credits 사용액 및 남은 크레딧

출처: `artifacts/day15/day15-2-20260922T103508Z/cost-credits-by-service.json` (계정 전체,
2026-09-03~09-23, 음수=크레딧 적용액)

| 서비스 | Credit(USD, 음수) |
|---|---|
| Amazon Relational Database Service | -12.2956266317 |
| Amazon Elastic Compute Cloud - Compute | -8.3882355648 |
| Amazon Virtual Private Cloud | -2.727466675 |
| Amazon ElastiCache | -2.04 |
| Amazon Elastic Load Balancing | -1.6749434084 |
| EC2 - Other | -0.8435469943 |
| AWS Data Transfer | -0.0390980853 |
| Amazon Simple Storage Service | -0.0218262766 |
| Amazon EC2 Container Registry (ECR) | -0.005187516 |
| **합계(수동 합산, Python으로 재검산)** | **≈ -28.04 USD** |

**Net total** (`artifacts/day15/day15-2-20260922T103508Z/cost-net-total.json`, 계정 전체):
`Amount = -0.0000000691 USD` — **사실상 0**(반올림 잡음 수준). 즉 이 기간 계정 전체 gross
비용(≈$28.04)이 크레딧(≈-$28.04)으로 거의 정확히 상쇄됐다.

### Credits/Bills 콘솔 캡처 텍스트화 (2026-09-22 기준, 사용자가 콘솔에서 직접 확인한 값)

출처: `artifacts/day15/screenshots/04a-credits-remaining.png`,
`artifacts/day15/screenshots/04b-september-estimated-bill.png`

| 항목 | 값 | 구분 |
|---|---|---|
| Total amount remaining | **$81.17** | **실제 잔액**(현재 시점 남은 크레딧) |
| Total amount used | **$78.83** | **실제 사용된 크레딧**(현재 시점까지 소진액) |
| Total estimated amount remaining | **$51.43** | **예상 잔액**(향후 예상 사용 반영 후 추정치) |
| Total estimated amount used | **$108.57** | **예상 사용액**(향후 예상 사용 반영 후 추정치) |
| September 2026 estimated grand total | **USD 0.00** | 2026-09-22 시점의 **9월 예상 결제액**(확정 청구액 아님) |
| Bill status | **Pending** | 아직 확정되지 않음 |

**주의(혼동 방지)**:
- "실제(actual)"와 "예상(estimated)" 값은 서로 다른 지표다. remaining($81.17)/used($78.83)은
  **현재까지 실제로 집계된 크레딧 잔액·사용액**이고, estimated remaining($51.43)/estimated
  used($108.57)는 **향후 사용 패턴을 반영한 예측치**다 — 네 값을 하나로 섞어 말하지 않는다.
  (참고: estimated used $108.57가 actual used $78.83보다 큰 것은 "앞으로 더 쓸 것으로
  예측"되기 때문으로 해석되나, 예측 모델의 정확한 산정 방식은 이번 세션에서 확인하지
  않았다 — 미검증.)
- "사용 비용(usage cost)"과 "사용된 크레딧(credit used)"도 다른 개념이다. §3/§4의 gross/
  budget 수치는 **AWS 리소스 사용에 따른 청구 비용**이고, 이 표의 "amount used/remaining"은
  **그 비용을 상쇄하는 데 쓰인 프로모션/무료 크레딧 잔액**이다.
- **`September 2026 estimated grand total = USD 0.00`은 확정 청구액이 아니라 2026-09-22
  시점의 예상 결제액이다.** AWS 비용 반영에는 지연이 있어(§12), Day15 cleanup으로 인한
  비용 변동이나 크레딧 재계산이 아직 전부 반영되지 않았을 수 있다 — **2026-09-24 이후
  재확인이 필요**하다.
- 이 크레딧/Bills 수치(§5 표)는 §3(Account-wide gross $28.04)·§4(Budget Actual $12.29)와
  **서로 다른 범위·목적의 지표**다: §3/§4는 "AWS 리소스를 얼마나 썼는가"(사용량 기반 비용),
  §5 표는 "그 비용을 상쇄할 크레딧이 얼마나 남았는가·청구서 관점에서 얼마가 찍히는가"이다.
  세 지표가 서로 다른 숫자로 보이는 것은 오류가 아니라 서로 다른 질문에 답하기 때문이다.

## 6. 숫자가 서로 다른 이유

이 문서에 등장하는 세 가지 총액이 서로 다른 것은 **오류가 아니라 서로 다른 필터/시점을
쓰기 때문**이다:

| 수치 | 범위 | 값 |
|---|---|---|
| Account-wide gross(§3) | 계정 전체, 모든 Region, 2026-09-03~09-23 | ≈ $28.04 |
| Budget-scope(§4) | `REGION=ap-northeast-2 AND RECORD_TYPE=Usage`만, 같은 기간 | ≈ $12.29 (Budget ActualSpend와 정확히 일치) |
| Net total(§5) | 계정 전체, gross + credits | ≈ $0.00 |
| Credits remaining/used(§5 표) | 계정 전체, 2026-09-22 콘솔 조회 시점 | 실제 $81.17 남음/$78.83 사용, 예상 $51.43 남음/$108.57 사용 |
| 9월 예상 청구액(§5 표) | ap-northeast-2 기준으로 추정(Budget과 동일 범위로 추정되나 명시적 필터 확인은 안 됨 — 미검증) | $0.00(2026-09-22 시점 예상, 확정 아님) |

Budget-scope 합계($12.29)가 Budget의 ActualSpend($12.29)와 소수점 단위까지 일치하는 것을
직접 확인했다 — 즉 **Budget이 실제로 REGION+RECORD_TYPE 필터를 적용해 계산하고 있음을
수치로 재현 확인**했다. Account-wide gross($28.04)가 더 큰 이유(다른 Region 사용 흔적 유무
등 세부 원인)는 이번 세션에서 서비스별 Region 단위까지 쪼개 확인하지 않아 **미검증**이다.
9월 예상 청구액($0.00)이 gross($28.04)/Budget($12.29) 어느 쪽과도 다른 것은, 이 값이
**크레딧 상쇄 후 실제 청구될 것으로 예상되는 금액**이기 때문이다 — gross/Budget은 크레딧을
차감하지 않은 "사용량 기준" 값이고, $0.00은 크레딧까지 반영한 "청구서 기준" 예상값이다.

## 7. 서비스별 비용 (요약, §3/§4 표 참고)

Region 필터 기준(§4, Budget과 일치하는 관점)으로 보면 비용 순위는 **RDS ≈ EC2 Compute >
ElastiCache > ELB > VPC ≈ EC2-Other > S3 > ECR**이다. RDS와 EC2 Compute가 지배적이며,
ElastiCache/ELB는 Day14에 삭제된 자원인데도 기간 전체(09-03~09-23) 누적 비용에는 이미
발생한 만큼 그대로 반영돼 있다.

## 8. 야간 중지 및 빠른 삭제 전략

- **야간 중지**: `artifacts/day14/37-night-shutdown-20260921T152409Z.txt`에 따르면 API
  A/Worker A 컨테이너를 graceful 종료한 뒤 해당 EC2 인스턴스를 `running→stopping`, RDS도
  stop 요청(`DAY14_NIGHT_SHUTDOWN_PASS`) — 실험을 안 쓰는 시간대에 컴퓨트 과금을 줄이는
  전략을 실제로 실행했다.
- **빠른 삭제**: Day15에 Terraform tf-dev는 apply 직후 곧바로 change-plan 실험 → destroy까지
  완료했고(§ day15-final-report.md §4), 기존 `autique-exp` 환경도 precheck 통과 직후
  단계적으로 즉시 삭제를 진행했다 — 두 경우 모두 "쓰지 않는 자원을 오래 세워두지 않는다"는
  같은 원칙을 따랐다.

## 9. ALB/ElastiCache 존재 기간

- ALB(Internal ALB, `autique-exp-alb-sg`로 보호됨)와 ElastiCache(Redis,
  `autique-experiment-redis.csw9o8.ng.0001.apn2.cache.amazonaws.com`)는 실험 초기 단계에
  생성되어(정확한 최초 생성 타임스탬프는 이번 세션에서 재조회하지 않음 — 미검증), Day14
  cleanup에서 삭제됐다(`artifacts/day14/35-day14-resource-cleanup-20260921T151216Z.txt`,
  `artifacts/day14/36-target-group-cleanup-20260921T152153Z.txt`).
- Day15 최종 cleanup에서 ALB/Target Group/ElastiCache **재확인 결과 0건**임을 다시 확인했다
  (`artifacts/day15/final-cleanup-verification.txt`, `autique-exp-cleanup-precheck.txt` §1/§9).
- §3 gross usage 표의 ELB($1.67)·ElastiCache($2.04) 비용은 이미 삭제된 이후에도 기간(09-03~
  09-23) 누적치로 계속 집계된 것으로, 현재 시점에 추가로 과금되고 있는 것이 아니다.

## 10. 최종 cleanup 결과

상세는 `day15-final-report.md` §5, `artifacts/day15/final-cleanup-verification.txt`,
`artifacts/day15/autique-exp-cleanup-post-verification.txt` 참고. 요약:

### 삭제 자원
RDS(`autique-experiment-mysql`+자동백업5개), S3(`autique-exp-<AWS_ACCOUNT_ID>-ap-northeast-2`,
808/808 객체 검증백업 후), SNS(`infra-alerts`), IAM(role 5개+profile 3개), SSM(33개),
CloudWatch(alarm 6개+dashboard 1개+log group 2개), SQS(main+DLQ), ECR(`autique-exp-app`,
이미지 8개), EC2 3대(+root volume 3개 자동삭제), VPC(`autique-exp-vpc` 전체 — endpoint/IGW/
SG 6개/subnet 4개/route table).

### 보존 자원
Budget 3개(`infra-sprint-85`/`infra-sprint-daily-8`/`account-monthly-110`), GitHub OIDC
provider, AWS service-linked role 6개, 기본 VPC(`172.31.0.0/16`), 무관한 S3 버킷 2개
(`aca-used-goods-images-2026`, `vintic-mvp-bucket-123`).

## 11. 전체 Region orphan 검사 결과

17개 활성(opt-in-not-required) Region 전부 스캔, `autique-exp`/`Project=auction-infra`
태그·이름 자원이 ap-northeast-2 외 어디에도 없음을 확인. 나머지 17개 Region은 계정이
opt-in한 적이 없어 자원을 가질 수 없는 상태라 스캔에서 제외(사유 명시). 상세:
`artifacts/day15/final-cleanup-execution.txt` "Phase 8" 섹션.

## 12. 비용 반영 지연과 후속 Bills 재확인 필요성

- Cost Explorer/Budgets 데이터는 최대 약 24시간 지연될 수 있다 — 이 문서의 모든 수치는
  **2026-09-22 07:12~10:35 KST 사이 조회 시점 스냅샷**이며, Day15 최종 cleanup(같은 날 더
  늦은 시각에 진행)으로 인한 비용 감소분은 이 스냅샷에 아직 반영되지 않았을 가능성이 높다.
- §5 크레딧/Bills 표는 사용자가 2026-09-22 콘솔에서 직접 확인한 값으로 텍스트화 완료했다
  (Total amount remaining $81.17, used $78.83, estimated remaining $51.43, estimated used
  $108.57, 9월 예상 결제액 $0.00, Bill status Pending). 단 이 값들 모두 **같은 시점 스냅샷**이며
  최종 확정치가 아니다.
- **후속 재확인 필요 시점**: cleanup 완료 후 최소 24~48시간 경과한 시점(예: **2026-09-24
  이후**)에 Cost Explorer/Budget/Bills를 다시 조회해, 삭제한 자원들의 비용이 실제로 더 이상
  발생하지 않는지, 크레딧 잔액·9월 예상 결제액이 이번 스냅샷과 비교해 어떻게 바뀌었는지,
  그리고 Bill status가 `Pending`에서 확정(finalized) 상태로 바뀌었는지 확인해야 한다.
