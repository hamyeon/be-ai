# 스프린트 고정 결정 (2026-09-07)

## Schema Freeze
- 스프린트 중 DB 스키마는 Day 4까지 확정된 것 외 변경하지 않는다.
- - `analysis_result`는 #86 머지 시점의 결과 DTO 기준으로 컬럼을 확정한다. 이후 AI 팀 변경은 스프린트 중 반영하지 않는다.

## Worker
- Worker concurrency 초기값 = 1. Day 13 실험에서만 변경.
- Processor는 계산만 하고 DB에 쓰지 않는다. 결과 저장 + worker_id 조건부 COMPLETED는 Worker 서비스 한 트랜잭션.

## Redis
- 용도: 경매 상세 조회 캐시만. 정합성·락·큐에 사용하지 않는다.
- connect timeout 200ms, command timeout 200ms. 실패 시 DB fallback, 5xx 금지.

## Health check
- liveness: 프로세스 생존만 (`/actuator/health/liveness`)
- readiness: DB 연결 포함 (`/actuator/health/readiness`). ALB target group은 readiness만 본다.

## 동기 Endpoint
- `POST /api/analyses/sync`는 `experiment` profile에서만 활성. prod-api profile에서는 등록되지 않는다.

## #86 의존성
- #86 머지 완료. baseline SHA: `04b0d234b8b57e338c3bf16c27cbcfe9d5789e20` (main, 2026-09-07)
- Real/Fake Processor 모두 이 SHA의 DTO를 사용한다.
- AI 쪽 후속 변경은 스프린트 중 main을 다시 당기지 않고, Day 15 이후 별도 머지한다.
- Fake Processor는 여전히 대량 성능 측정용으로 사용. Real은 Day 3 latency 측정과 Day 13 소량 측정에만 사용.