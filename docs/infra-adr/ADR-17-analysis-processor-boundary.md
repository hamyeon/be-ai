# ADR-17 AnalysisProcessor로 AI 파트 의존성 격리

**Decision**: AI·가격 계산 로직을 `AnalysisProcessor` 인터페이스 뒤로 격리한다. 대량 성능 측정은 `FakeAnalysisProcessor`, 실제 latency 측정은 `RealAnalysisProcessor`로 수행하며, 두 구현 모두 스프린트 시작 시점의 develop SHA에 고정한다.

**Problem**: AI 파트가 계산 로직과 DTO를 계속 수정하고 있다. 스프린트의 핵심 검증(SQS 비동기, 멱등성, 선점·fencing, Crash 복구, 확장)은 계산 결과 내용과 무관한데, 계산 코드가 도중에 바뀌면 성능 수치 변화가 인프라 때문인지 계산 로직 때문인지 구분할 수 없다.

**Alternatives**:
1. Worker 서비스가 AI 코드를 직접 호출 — 계산 로직 변경이 Worker 코드에 바로 전파되고, 장애 주입이 어렵다.
2. 스프린트 중 main을 계속 동기화 — 측정 조건이 흔들린다.
3. 인터페이스로 격리 + SHA 고정 + Fake/Real 두 구현 (선택).

**Choice**: 3번.

**Reason**: Fake는 처리시간·오류를 결정적으로 주입할 수 있어 장애 테스트와 반복 측정에 적합하다. Real은 실제 AI latency와 비용을 소량으로 측정하는 데만 쓴다. 두 구현이 같은 인터페이스를 쓰므로 sync/async 비교 실험도 동일 조건이 된다.

**계약**:
- `AnalysisPayload process(AnalysisInput input)` — 순수 계산. DB 저장·상태 변경·SQS 삭제 금지.
- 결과 INSERT와 `worker_id` 조건부 COMPLETED 전이는 호출자(Worker 서비스)가 한 트랜잭션에서 수행.
- 일시적 오류(AI timeout, 429, 5xx)는 재시도 대상 예외, 영구 오류(잘못된 objectKey, 미지원 이미지)는 FAILED 대상 예외로 구분해 던진다.
- Fake는 sleep(ms)·성공·일시 오류·영구 오류를 설정으로 주입할 수 있다.
- `analysis_result` 컬럼은 baseline SHA 시점의 결과 DTO 기준으로 확정하고 스프린트 중 변경하지 않는다.

**Trade-off**: Fake로 측정한 성능 수치는 실제 AI latency를 반영하지 않는다. 스프린트 종료 시점의 AI 코드는 baseline보다 뒤처져 있다.

**Current Mitigation**: Day 3에 Real로 5~10건 latency를 측정해 Fake sleep 초깃값과 timeout을 정한다. Day 13에 Real 10~20건을 별도 기록해 Fake 결과 옆에 나란히 둔다. baseline SHA는 `docs/infra-sprint/decisions.md`에 기록.

**Residual Risk**: AI 팀 후속 변경으로 DTO가 바뀌면 스프린트 후 adapter와 `analysis_result` 스키마 1회 수정이 필요하다.

**Future**: Day 15 이후 main 최신을 `infra/experiment`에 머지. DTO 변경이 있으면 `RealAnalysisProcessor` adapter와 result 스키마만 수정하고 Worker 서비스는 손대지 않는다.
