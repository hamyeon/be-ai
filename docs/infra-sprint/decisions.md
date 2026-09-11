# 스프린트 고정 결정 (2026-09-07)

## Schema Freeze
- 스프린트 중 DB 스키마는 Day 4까지 확정된 것 외 변경하지 않는다.
- - `analysis_result`는 #93 머지 시점의 결과 DTO 기준으로 컬럼을 확정한다. 이후 AI 팀 변경은 스프린트 중 반영하지 않는다.

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

## #93 의존성
- #93 머지 완료. baseline SHA: `04b0d234b8b57e338c3bf16c27cbcfe9d5789e20` (main, 2026-09-07)
  - Day 3 4단계에서 checkout/fetch/pull/merge/rebase 없이 로컬에 이미 있던 ref로 재확인.
    `main`과 `origin/main`이 모두 이 SHA를 가리키고, 커밋 시각은 2026-09-04 19:38:16(한국
    표준시)로 2026-09-07 23:59:59 KST 이전이며 main의 최신 커밋이다(그 뒤로 main에 새 커밋
    없음). 확인 명령:
    `git log main --before="2026-09-07 23:59:59 +0900" -1 --pretty=format:'%H %ad %s' --date=format-local:'%Y-%m-%d %H:%M:%S %Z'`
- Real/Fake Processor 모두 이 SHA의 DTO를 사용한다.
- AI 쪽 후속 변경은 스프린트 중 main을 다시 당기지 않고, Day 15 이후 별도 머지한다.
- Fake Processor는 여전히 대량 성능 측정용으로 사용. **`RealAnalysisProcessor`는 Day 3에서
  SKIPPED로 확정**했다 — VisionResult → PricingRequest 사이에 사용자 확인 단계가 껴 있어
  최소 adapter 변환만으로 연결할 수 없기 때문이다(사유·경계는 `ADR-17` "Day 3 Update" 참고).
  Day 3의 "Real 5~10건 측정"과 Day 13의 "Real 10~20건 측정"은 adapter가 없어 수행하지 않는다.

## Fake sleep / AI timeout 근거 (Day 3)
Real adapter가 SKIPPED이므로 실측 대신 다음 우선순위로 근거를 정했다.
1. 기존 Redis 경로의 (이번 세션에서 직접 조회한) 측정 로그 — **미확인**: 이 샌드박스에 그
   시점의 운영 `AiCallLog` 데이터가 없어 조회할 수 없었다.
2. 기존 문서에 기록된 Vision latency — **사용**: `docs/adr.md` §7의 실측 표(Redis Streams
   경로, `AiCallLog` 실측치). 실루엣 4,440ms + 라벨 4,046ms + 컨디션 2,678ms = 11,164ms
   (≈11.2초, Vision 3단계 합산).
3. 둘 다 없을 때의 임시 가정 — 이번에는 2번 근거가 있어 쓰지 않았다.

결론:
- `docs/infra-sprint/contracts.md`의 기존 초기값(AI timeout 60초, 정상 Worker 최대 처리시간
  70초, docker stop timeout 80~90초, SQS Visibility Timeout ≥90초)은 실측(≈11.2초)보다 5배
  이상 여유가 있어 그대로 유지한다 — 숫자를 바꾸지 않는다. `Visibility Timeout > 정상 처리
  최대시간`, `docker stop timeout > 정상 처리 최대시간` 관계도 그대로 유지된다.
- `FakeAnalysisProcessor`의 `analysis.processor.fake.delay-ms` 기본값 0(정확성 테스트용)은
  바꾸지 않는다. 부하/성능 측정을 할 때는 이 근거로 11000(ms) 근처를 권장값으로 설정에 주입한다
  - 코드 기본값이 아니라 설정으로만 분리해서 쓴다.