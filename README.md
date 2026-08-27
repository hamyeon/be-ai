# be-ai

## Concurrency Experiments

수동 입찰(`Auction`) read-modify-write 경쟁을 no-lock/pessimistic-lock 등 동일 조건에서
비교하기 위한 실험. 프로토콜/환경/raw data/요약은 `docs/experiments/concurrency/`에 분리해
관리한다.

- No-lock (#34): 동일 frozen workload 20회 중 3회 post-state invariant violation 관찰.
- Pessimistic Lock (#35): 동일 frozen workload(독립변수는 `PESSIMISTIC_WRITE` 적용 여부
  하나만) 20회 중 0회 관찰.
- 결과 요약: [docs/experiments/concurrency/summary.md](docs/experiments/concurrency/summary.md)
- 상세 프로토콜: [docs/experiments/concurrency/protocol.md](docs/experiments/concurrency/protocol.md)
- 실행 환경: [docs/experiments/concurrency/environment.md](docs/experiments/concurrency/environment.md)
- Raw data: [no-lock-correctness.csv](docs/experiments/concurrency/raw/no-lock-correctness.csv), [pessimistic-correctness.csv](docs/experiments/concurrency/raw/pessimistic-correctness.csv)