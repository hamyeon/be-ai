# be-ai

## Concurrency Experiments

수동 입찰(`Auction`) read-modify-write 경쟁을 no-lock/pessimistic-lock 등 동일 조건에서
비교하기 위한 실험. 프로토콜/환경/raw data/요약은 `docs/experiments/concurrency/`에 분리해
관리한다.

- No-lock correctness 본 실험(#34): 동일 frozen workload로 20회 반복 수행.
- 결과: [docs/experiments/concurrency/summary.md](docs/experiments/concurrency/summary.md) 참고.
- 상세 프로토콜: [docs/experiments/concurrency/protocol.md](docs/experiments/concurrency/protocol.md)
- 실행 환경: [docs/experiments/concurrency/environment.md](docs/experiments/concurrency/environment.md)
- Raw data: [docs/experiments/concurrency/raw/no-lock-correctness.csv](docs/experiments/concurrency/raw/no-lock-correctness.csv)