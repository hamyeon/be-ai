# Day 15 도입 여부 결정 기준 (Day 13 측정 전 기록)

기록 시각: 2026-09-20 (Day 13 측정 시작 전, 이 파일의 커밋 시각이 기준)
원칙: 결과를 본 뒤 이 기준을 바꾸지 않는다. 바꿔야 한다면 변경 사유와 시각을 아래에 추가로 남긴다.

## 선택지
1. SQS Worker 도입 제안
2. Redis Streams 유지 + 보완 제안 (XAUTOCLAIM 재선점, 조건부 UPDATE/@Version, 예외 분류, 실패 Stream, XPENDING 관측)
3. 현재 구조 유지 (갭 문서화만)

## 판정 순서

### 1단계: SQS PoC 정합성 게이트 (Day 4, 11, 12, 14 결과)
아래 중 하나라도 실패하면 1번을 제안하지 않는다.
- [ ] 중복 메시지·lease 상실 상황에서 결과 1건 (fencing)
- [ ] maxReceiveCount 이전 Worker kill -9 후 자동 복구
- [ ] SIGTERM 시 in-flight 작업 정상 완료, stale 재처리 없음
- [ ] 재시도 소진 시 DB FAILED + DLQ 최종 대응
- [ ] 복구에 걸린 시간이 Visibility Timeout + 정상 처리시간 이내

### 2단계: 기존 Redis 경로 갭 재확인 (Day 14 baseline)
- [ ] Worker crash 후 N분 관찰 동안 자동 복구되지 않음
- 확인되지 않으면(자동 복구됨) 감사 결론을 재검토하고 3번을 우선 검토한다.

### 3단계: 변경 범위 비교 (1단계 통과 시)
| 항목 | SQS 도입 | Redis 보완 |
|---|---|---|
| 기존 API 계약 유지 방법 | | |
| Redis Producer 중단 시점 / 이중 발행 방지 | | 해당 없음 |
| Session / Job 원본 결정 | | 해당 없음 |
| Flyway 변경 | | |
| 환경별 설정·인프라 추가 | | |
| 기존 PEL 메시지 처리 | | |
| 회귀·E2E 테스트 범위 | | |
| 담당자 리뷰 범위 | | |

- SQS 도입의 변경 범위가 Redis 보완보다 **크면 2번**을 제안한다.
- 비슷하거나 작으면 1번을 제안한다.
- 조건부 선점·fencing·예외 분류·graceful shutdown은 **어느 큐를 쓰든 필요**하므로 비교에서 제외한다.

## Day 13 성능 결과의 역할
Day 13은 **같은 PoC 안의** 동기 vs 비동기, API 1대 vs 2대, 요청량 vs 큐 대기를 비교한다.
Redis Streams와 SQS의 성능 비교가 아니므로 **큐 선택의 판정 기준으로 쓰지 않는다.**
용량 계획(Worker 수, 병목 지점)의 근거로만 사용한다.