# be-ai

## Concurrency Experiments

수동 입찰(`Auction`) read-modify-write 경쟁을 no-lock/pessimistic-lock 등 동일 조건에서
비교하기 위한 실험. 프로토콜/환경/raw data/요약은 `docs/experiments/concurrency/`에 분리해
관리한다.

**Concurrency correctness** (delay로 race window를 확대한 통제 실험)
- No-lock (#34): 동일 frozen workload 20회 중 3회 post-state invariant violation 관찰.
- Pessimistic Lock (#35): 동일 frozen workload(독립변수는 `PESSIMISTIC_WRITE` 적용 여부
  하나만) 20회 중 0회 관찰.

**Concurrency performance** (delay 없이 측정한 별도 실험, #36-A)
- No-lock: overall median 27.3ms / p95 38.4ms, attempt throughput 253.8/s.
- Pessimistic Lock: overall median 60.2ms / p95 111.7ms, attempt throughput 82.6/s.
- outcome mix가 달라(No-lock은 DB 예외 위주, Pessimistic은 business rejection 위주) 이
  차이를 "Pessimistic Lock의 순수 오버헤드"로 단정하지 않는다 — 상세는 summary 참고.

- 결과 요약: [docs/experiments/concurrency/summary.md](docs/experiments/concurrency/summary.md)
- 상세 프로토콜: [docs/experiments/concurrency/protocol.md](docs/experiments/concurrency/protocol.md)
- 실행 환경: [docs/experiments/concurrency/environment.md](docs/experiments/concurrency/environment.md)
- Raw data: [no-lock-correctness.csv](docs/experiments/concurrency/raw/no-lock-correctness.csv), [pessimistic-correctness.csv](docs/experiments/concurrency/raw/pessimistic-correctness.csv), [no-lock-performance.csv](docs/experiments/concurrency/raw/no-lock-performance.csv), [pessimistic-performance.csv](docs/experiments/concurrency/raw/pessimistic-performance.csv)

## Auction API Contract

```text
Auction API contract frozen (#36-B)
- canonical contract 확정: docs/auction-api-spec-final.md
- 현재 구현과의 gap은 별도 audit 문서로 관리
- 미구현 endpoint(17/20) 및 deferred implementation gap(정렬 검증/닉네임 마스킹/
  오류 코드 매핑/40909 처리) 존재 — "계약 확정"과 "구현 완료"는 다른 것으로 취급한다
```

- canonical source of truth: [docs/auction-api-spec-final.md](docs/auction-api-spec-final.md)
- freeze 상태 / endpoint별 gap / deferred 항목: [docs/api/auction-api-contract-gap.md](docs/api/auction-api-contract-gap.md)
- 짧은 포인터 문서: [docs/api/README.md](docs/api/README.md)