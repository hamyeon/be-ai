# Redis Streams 소비 구조 장애 복구 감사 (Read-only Audit)

이 문서는 기존 AI 분석 경로(Vision 단계, `POST /api/products/analyze`)의 Redis Streams
소비 구조를 코드 변경 없이 감사(audit)해서, 장애 복구 관점의 갭을 정리한 것이다.
대상 코드는 전혀 수정하지 않았다.

## 1. 대상 파일과 역할

| 파일 | 역할 |
|---|---|
| `backend/src/main/java/com/vintic/backend/analyze/queue/AnalysisTaskProducer.java` | `AnalysisTaskMessage`를 JSON 직렬화해 Redis Stream에 `XADD`. Stream 키는 `AnalysisStreamProperties`에서 가져옴. |
| `backend/src/main/java/com/vintic/backend/analyze/queue/AnalysisTaskConsumer.java` | `StreamListener<String, MapRecord<String,String,String>>` 구현체. 메시지 파싱 → 세션 상태 확인/전이(`startVisionProcessing`) → `VisionAnalysisService` 호출 → 결과/실패를 MySQL에 저장 → 저장 성공 시에만 `XACK`. |
| `backend/src/main/java/com/vintic/backend/analyze/queue/RedisStreamConsumerConfig.java` | `@PostConstruct`에서 Consumer Group 생성(`XGROUP CREATE`, 이미 있으면 무시) 후 `StreamMessageListenerContainer`를 만들어 `AnalysisTaskConsumer`를 구독시킴(`XREADGROUP` 폴링, 2초 간격). `@PreDestroy`에서 컨테이너 정지. |
| `backend/src/main/java/com/vintic/backend/analyze/queue/AnalysisStreamProperties.java` | `analysis.stream.*` 설정 바인딩 (`key`, `group`, `consumer-prefix`). |
| `backend/src/main/java/com/vintic/backend/analyze/queue/AnalysisTaskMessage.java` | Stream payload DTO (`analysisId`, `imageUrls`). |
| `backend/src/main/java/com/vintic/backend/analyze/domain/ProductAnalysisSession.java` | Vision→Pricing 세션 상태 엔티티. `startVisionProcessing()`이 `QUEUED`가 아니면 예외를 던져 중복 처리를 막는 가드 역할. |
| `backend/src/main/java/com/vintic/backend/analyze/service/ProductAnalyzeService.java` | 이미지 검증 → S3 업로드 → `AnalysisTaskProducer.enqueue()` 호출까지 담당(Producer 쪽 오케스트레이터). |
| `backend/src/main/java/com/vintic/backend/analyze/service/AnalysisFailureRecorder.java` | 실패 상태 기록 전용, `REQUIRES_NEW`로 별도 트랜잭션 분리. |
| `backend/src/main/resources/application.yml` (51~56행) | `analysis.stream.key/group/consumer-prefix` 기본값. |
| `docs/ai-async-analysis.md` | 이 파이프라인을 도입한 원 설계 문서. ACK 정책과 "이번 이슈 범위 밖" 섹션에 알려진 갭이 이미 일부 기술되어 있음. |

별도 개념으로 `backend/src/main/java/com/vintic/backend/analyze/job/ProductAnalysisJob.java`와
`ProductAnalysisJobClaimService` 등이 있는데, 이는 `infra/experiment` 브랜치의 **SQS 기반**
파이프라인이며 Redis Streams와 무관하다(`docs/infra-adr/ADR-12-analysis-job-dual-write.md` 참고).
혼동하지 않도록 이번 감사 대상에서 제외했다.

Redis Streams 명령어 중 실제로 코드에서 호출되는 것은 `XADD`(Producer), `XGROUP CREATE`,
`XREADGROUP`(컨테이너가 내부적으로 수행), `XACK`뿐이다. **`XPENDING`/`XCLAIM`/`XAUTOCLAIM`은
프로덕션 코드 어디에도 없다** — 테스트 코드(`AnalysisTaskProducerRedisIntegrationTest`)에서
`opsForStream().pending(...)`으로 ack 전 상태를 "확인"만 할 뿐, 회수(reclaim) 로직은 없다.

## 2. 현재 소비 흐름 요약

```
enqueue(XADD) → Consumer Group(ai-analysis-workers)이 XREADGROUP으로 수신
  → session.startVisionProcessing() 저장 성공?
      실패(QUEUED 아님, 중복) → XACK 하고 버림
      실패(DB 저장 오류)      → ACK 안 함, PEL(Pending Entries List)에 남음
      성공                    → VisionAnalysisService 호출
  → Vision 실패 → DB에 실패 기록 성공 시에만 XACK
  → Vision 성공 → DB에 결과 저장 성공 시에만 XACK
```

Consumer 이름은 인스턴스 기동마다 `{consumer-prefix}-{UUID}`로 새로 생성됨
(`RedisStreamConsumerConfig.java:46`). 컨테이너 구독은 `ReadOffset.lastConsumed()`로
등록되는데, 이는 신규 Consumer 이름 기준으로 `>` (아직 어떤 consumer에게도 전달 안 된
새 메시지)만 읽는다는 뜻이다.

## 3. 장애 복구 갭

### 갭 1 — Pending 메시지를 아무도 회수하지 않음 (가장 구조적인 갭)
- Consumer가 크래시하거나 앱이 재시작되면, 그 인스턴스가 갖고 있던 미처리(PEL) 메시지는
  **죽은 UUID Consumer 이름 아래 그대로 남는다.**
- 재시작한 새 인스턴스는 매번 **새로운 UUID Consumer 이름**을 쓰므로(`RedisStreamConsumerConfig.java:46`),
  `XREADGROUP ... STREAMS key >` 방식으로는 예전 Consumer 이름 소유의 PEL 항목을 절대 자동으로
  넘겨받지 못한다.
- `XCLAIM`/`XAUTOCLAIM`을 호출하는 코드가 어디에도 없으므로, 사람이 `redis-cli`로 수동 개입하지
  않는 한 그 메시지는 **영구히 미처리 상태**로 남는다.
- `docs/ai-async-analysis.md`의 "이번 이슈 범위 밖" 섹션에 이미 "Pending 메시지 회수(claim) 없음"으로
  명시돼 있어 알려진 이슈이지만, 실제 영향(Consumer 이름이 매번 바뀌어 자동 회수 경로 자체가
  구조적으로 없다는 점)은 문서에 구체적으로 적혀 있지 않다.

### 갭 2 — VISION_PROCESSING에서 멈춘 세션이 재전달 시 조용히 폐기됨 (문서화된 known hole)
- `AnalysisTaskConsumer.java:51-64`: `startVisionProcessing()` 저장에는 성공했지만 그 직후,
  `VisionAnalysisService.analyze()` 호출 전에 프로세스가 죽으면 세션은 `VISION_PROCESSING`에
  멈춘다.
- 이 메시지가 (수동 XCLAIM 등으로) 재전달되면, `startVisionProcessing()`은 상태가 `QUEUED`가
  아니라는 이유로 `InvalidAnalysisStatusException`을 던지고, Consumer는 이를 "이미 처리된 중복"으로
  오인해 그대로 `XACK`하고 버린다(`AnalysisTaskConsumer.java:54-60`).
- 결과: Vision이 **한 번도 실행되지 않았는데도** 메시지가 사라지고, 세션은 `VISION_PROCESSING`
  상태로 영구히 멈춘 채 실패 기록도 없다. 사용자 입장에서는 폴링해도 상태가 절대 바뀌지 않는다.
- `docs/ai-async-analysis.md:158-162`에 "알려진 구멍"으로 이미 기록돼 있음 — 신규 발견 아님.

### 갭 3 — DLQ 부재로 손상 메시지가 PEL을 무기한 오염시킴
- `parseMessage()`가 실패(JSON 손상 등)하면 `null`을 반환하고 `onMessage`는 그냥 `return`한다
  (`AnalysisTaskConsumer.java:38-42`). ACK를 하지 않으므로 이 메시지는 PEL에 영원히 남는다.
- 격리(DLQ)나 재시도 횟수 상한이 없어서, 파싱 자체가 불가능한 메시지도 정상 pending 메시지와
  구분 없이 같은 PEL에 계속 쌓인다. `XPENDING`으로 조회해도 정상 지연 메시지와 영구 불량 메시지를
  구분할 방법이 코드 레벨에는 없다(사람이 payload를 직접 봐야 함).
- 이 역시 `docs/ai-async-analysis.md`의 "DLQ" 항목에 범위 밖으로 명시돼 있음.

### 갭 4 — 자동 재시도 없음, 실패는 상태로만 남음
- Vision 실패, DB 저장 실패 등은 전부 `*_FAILED` 상태로 종결될 뿐 재시도 스케줄러가 없다
  (`docs/ai-async-analysis.md:152`). 일시적 오류(OpenAI 429/5xx 등)와 영구적 오류(잘못된 이미지 등)를
  구분하지 않고 동일하게 처리한다 — `ADR-17`의 `AnalysisProcessor` 계약에서 언급하는
  "일시적 오류 vs 영구 오류 구분"이 이 Redis Streams 경로에는 적용돼 있지 않다(그 ADR은 별도
  SQS 실험 트랙 대상).

### 갭 5 — PEL 깊이에 대한 모니터링/알림 없음
- `management.health.redis.enabled=true`(`application.yml:175-176`)는 Redis **연결 상태**만 확인할
  뿐, Consumer Group의 pending 개수나 idle time을 노출하지 않는다.
- 코드베이스 전체에서 `@Scheduled` 기반의 PEL 점검/보고 로직이나 커스텀 `ErrorHandler` 빈이
  없음을 확인함(grep 결과 없음). 즉 메시지가 PEL에 쌓여도 운영자가 능동적으로 `redis-cli
  XPENDING`을 조회하지 않는 한 알아챌 방법이 없다.

### 갭 6 — 단일 인스턴스·인프로세스 Consumer로 인한 가용성 종속
- 현재 Consumer는 별도 Worker 프로세스가 아니라 API 서버와 같은 Spring 애플리케이션 안에서
  뜬다(`RedisStreamConsumerConfig.java` 주석, `docs/ai-async-analysis.md:21`). 컨테이너 구독도
  `container.receive(...)` 1회 호출뿐이라 사실상 인스턴스당 Consumer 1개다.
- 인스턴스를 여러 개 띄우면 서로 다른 Consumer 이름으로 같은 그룹에 붙어 메시지를 나눠 갖는 것
  자체는 되지만(Consumer 이름에 UUID를 써서 충돌 방지), 갭 1과 결합하면 인스턴스 하나가
  죽었을 때 그 인스턴스가 들고 있던 메시지는 다른 살아있는 인스턴스가 자동으로 넘겨받지 못한다.

### 갭 7 — `findById` 실패 경로가 ACK 정책 문서에 명시돼 있지 않음 (경미, 기능적 버그 아님)
- `AnalysisTaskConsumer.java:44`의 `sessionRepository.findById(...)` 호출은 다른 DB 접근과 달리
  `try/catch`로 감싸여 있지 않다. DB 커넥션 문제 등으로 여기서 예외가 나면 `onMessage()` 밖으로
  그대로 전파된다.
- `RedisStreamConsumerConfig`에는 커스텀 `ErrorHandler`가 등록돼 있지 않으므로, Spring Data
  Redis의 기본 `ErrorHandler`(로깅 후 계속 진행)에 의존하게 된다. 결과적으로 메시지는 ACK되지
  않고 PEL에 남으므로 동작 자체는 다른 "DB 저장 실패" 케이스와 동일하지만, `docs/ai-async-analysis.md`의
  "ACK 정책" 절 4개 항목에는 이 경로가 명시적으로 나열돼 있지 않다 — 프레임워크 기본 동작에
  암묵적으로 의존하고 있다는 점에서 문서화 갭이다.

## 4. 요약

| 갭 | 심각도 | 이미 문서화됨? |
|---|---|---|
| 1. Consumer 이름이 매번 바뀌어 PEL 자동 회수 불가 | 높음 | 부분적 (`docs/ai-async-analysis.md`에 "claim 없음"만 언급, 원인은 미기술) |
| 2. VISION_PROCESSING에서 멈춘 세션이 재전달 시 조용히 폐기 | 높음 | 예 (`ai-async-analysis.md:158-162`) |
| 3. DLQ 부재로 손상 메시지가 PEL 오염 | 중간 | 예 |
| 4. 일시적/영구 오류 미구분, 자동 재시도 없음 | 중간 | 예 |
| 5. PEL 깊이 모니터링/알림 없음 | 중간 | 아니오 (신규) |
| 6. 단일 인프로세스 Consumer, 인스턴스 장애 시 자동 인계 없음 | 중간 | 부분적 (구조는 문서화, 장애 영향은 미기술) |
| 7. `findById` 예외 경로가 ACK 정책 문서에 누락 | 낮음 | 아니오 (신규, 기능적 결함 아님) |

가장 우선적으로 다뤄야 할 것은 **갭 1(Consumer 이름 회전으로 인한 회수 불가)**과
**갭 2(VISION_PROCESSING 정체 세션의 조용한 폐기)**의 조합이다 — 갭 2를 고치려면 결국
"QUEUED가 아닌데 완료/실패도 아닌 세션"을 재처리할 방법이 필요한데, 그 재처리 자체가
갭 1(claim 로직 부재) 때문에 불가능한 상태다.
