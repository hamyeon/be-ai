# Redis Streams 소비 구조 항목별 감사 (a~j)

대상: `POST /api/products/analyze` Vision 단계의 Redis Streams 파이프라인
(`AnalysisTaskProducer`/`AnalysisTaskConsumer`/`RedisStreamConsumerConfig`/
`ProductAnalysisSession`). 코드는 읽기만 했고 수정하지 않았다. 각 항목은
코드에서 직접 확인된 근거만 적었고, 확인되지 않는 것은 "확인 불가"로 표기했다.

## 2. 항목별 확인

### a. ACK 시점: 처리 완료(DB 커밋) 이후인가?

**있음** — DB 저장 성공 이후에만 XACK.

```java
// AnalysisTaskConsumer.java:93-103 (tryCompleteVision)
session.completeVision(json);
sessionRepository.save(session);
return true;   // save가 예외 없이 끝나야 true

// AnalysisTaskConsumer.java:78-80 (onMessage)
if (tryCompleteVision(session, result)) {
    acknowledge(record);   // true일 때만 XACK
}
```

실패 경로도 동일: `tryRecordVisionFailure()`가 `failureRecorder.recordVisionFailure()`
(내부적으로 `@Transactional(REQUIRES_NEW)` 커밋) 성공 시에만 `true`를 반환하고,
그 반환값을 보고 나서 `acknowledge(record)`를 호출한다(`AnalysisTaskConsumer.java:71-76`).
`sessionRepository.save()`는 Spring Data JPA `SimpleJpaRepository`의 기본
`@Transactional` 경계 안에서 실행되므로, save가 예외 없이 리턴했다는 것은 곧 커밋됐다는
뜻이다. 읽자마자/처리 전에 ACK하는 경로는 없다.

### b. Worker 크래시 시 PEL 재선점(XPENDING/XCLAIM/XAUTOCLAIM)

**없음.**

- 저장소 전체에서 `XPENDING`/`XCLAIM`/`XAUTOCLAIM`을 호출하는 프로덕션 코드가 없다
  (테스트 `AnalysisTaskProducerRedisIntegrationTest.java:90-99`에서
  `redisTemplate.opsForStream().pending(...)`을 호출하지만, 이는 ack 전 상태를
  **검증**만 하는 테스트 코드이고 재선점 로직이 아니다).
- `RedisStreamConsumerConfig.java`에는 `@Scheduled`나 별도 recovery 스레드가 없다.
- idle 기준 시간(claim 조건)을 설정하는 값 자체가 코드/설정 어디에도 없다 — **확인 불가가
  아니라 애초에 존재하지 않음**.
- Consumer 이름이 매 기동마다 `UUID.randomUUID()`로 새로 생성되므로
  (`RedisStreamConsumerConfig.java:46`), 재시작한 인스턴스가 `XREADGROUP ... STREAMS key >`로
  새 메시지만 읽는 한, 예전 Consumer 이름 소유의 PEL 항목을 자동으로 넘겨받을 경로 자체가 없다.

### c. 중복 처리 방어 (결과 row 2건 생성 방지)

**부분적.**

- `ProductAnalysisSession.startVisionProcessing()`이 상태가 `QUEUED`가 아니면 예외를
  던지는 애플리케이션 레벨 가드가 있다.

```java
// ProductAnalysisSession.java:99-106
public void startVisionProcessing() {
    if (status != AnalysisStatus.QUEUED) {
        throw new InvalidAnalysisStatusException(...);
    }
    this.status = AnalysisStatus.VISION_PROCESSING;
}
```

- 이 가드는 **DB 레벨 조건부 UPDATE가 아니라 "조회(`findById`) → 메모리에서 상태 확인 →
  save"** 방식이다. `ProductAnalysisSession`에 `@Version`(낙관적 락) 컬럼이 없고,
  `ProductAnalysisSessionRepository`(`JpaRepository<ProductAnalysisSession, Long>`,
  커스텀 메서드 없음)에도 `SELECT ... FOR UPDATE`나 조건부 `UPDATE ... WHERE status = ...`가
  없다. 즉 같은 세션에 대해 두 요청이 정확히 동시에 `findById`를 실행하면(현재 구조에서는
  Consumer가 인스턴스당 1개라 발생 확률이 낮지만) 둘 다 `QUEUED`를 읽고 둘 다 통과할 수
  있는 race window가 이론적으로 남아있다. 이는 `ProductAnalysisJob`(SQS 트랙)이 조건부
  UPDATE(`ADR-12`)로 막는 것과 다른 방식이다.
- "결과 row가 2건 생기는 것" 자체는 세션이 1 row(단일 엔티티)에 컬럼으로 결과를 저장하는
  구조라 애초에 별도 row가 새로 생기는 구조는 아니다 — 문제가 있다면 "같은 row가 두 번
  업데이트/Vision이 두 번 호출됨"이지 "row 중복 생성"은 아니다.

### d. Lease/Fencing (재선점 후 원래 Worker의 뒤늦은 쓰기 방지)

**없음.**

- `worker_id`/`owner`/`lease` 성격의 컬럼이 `ProductAnalysisSession`에 없다(id, status,
  imageUrls, visionResultJson, confirmedInputJson, pricingResultJson, failureStage,
  failureMessage, startedAt, completedAt이 전부).
- `@Version` 낙관적 락 컬럼도 없다.
- b에서 확인했듯 재선점(XCLAIM) 로직 자체가 없으므로 "재선점된 뒤 원래 Worker가 뒤늦게
  쓰는" 시나리오가 코드상 애초에 설계되어 있지 않다 — 막을 장치도, 그 장치가 필요한 경로도
  없다.

### d-1. Day4 갱신: SQS 트랙에 조건부 claim/fencing이 실제 구현됨(Redis 트랙은 무변경)

**c/d에서 "없음"/"부분적"으로 확인한 것은 여전히 정확하다 — 이 항목은 Redis Streams
경로가 아니라 별도 SQS 트랙(`product_analysis_jobs`)에 생긴 변화를 기록한다.**

Day4에서 `product_analysis_jobs`(SQS 트랙)에 조건부 claim(`claimForProcessing`)과
`worker_id` fencing(`completeIfOwned`/`failIfOwned`), `analysis_result.analysis_id`
UNIQUE를 구현하고 LocalStack 통합 테스트로 검증했다(`ADR-04` 참고). 이 구현이 바로
위 c/d 항목이 가리키는 `ProductAnalysisSession`/Redis Streams Consumer 코드에
적용됐다고 기록하지 않는다 — **`AnalysisTaskConsumer`, `ProductAnalysisSession`,
`RedisStreamConsumerConfig`는 이번 Day4 작업에서 전혀 수정하지 않았다.** c/d가 지적한
갭(조회 후 저장 방식, `worker_id`/lease 컬럼 없음, 재선점 로직 없음)은 이 문서 작성
시점과 동일하게 그대로 남아 있다.

다만 원리 차원에서는 재사용 가능하다:

- 조건부 claim(`WHERE status IN (...) OR (status='PROCESSING' AND stale)`)은 SQS의
  Visibility Timeout이나 `ApproximateReceiveCount` 같은 큐 고유 기능에 의존하지 않는
  **DB 상태 모델 원리**다 - "조회 후 저장"을 "단일 조건부 UPDATE"로 바꾸는 것만으로
  race window를 없앤다.
- `worker_id` fencing과 결과 테이블의 UNIQUE 제약도 큐 구현과 무관한, DB가 보장하는
  **최종 Commit 방어**다 - 어떤 큐(SQS, Redis Streams, 다른 메시지 브로커)를 쓰든 같은
  원리를 적용할 수 있다.
- 이번 Day4의 구현·장애 주입 테스트(중복 delivery, crash, retryable 재시도, DLQ)는
  전부 **SQS Worker 경로**(`SqsAnalysisJobHandler`/`SqsAnalysisJobPoller`)에만
  적용·검증됐다. Redis Streams 경로에 같은 정확성 보장이 생겼다고 간주하지 않는다.
- Redis 경로에 같은 보장을 적용하려면, `ProductAnalysisSession`에 `worker_id`/
  `processing_started_at` 성격의 컬럼을 추가하고, `AnalysisTaskConsumer`가 지금의
  "조회(`findById`) → 메모리에서 상태 확인 → `save()`" 대신 이번 Day4와 같은
  **조건부 claim + fencing + finalization 트랜잭션 경계**를 통과하도록 별도로
  다시 구현해야 한다 - 이는 이번 범위에 포함되지 않았고, 이번 세션에서 시도하지도
  않았다.

### e. 실패 분류 (일시적 오류 vs 영구 오류)

**없음.**

```java
// AnalysisTaskConsumer.java:66-76
try {
    result = visionAnalysisService.analyze(...);
} catch (RuntimeException visionError) {
    if (tryRecordVisionFailure(session.getId(), visionError)) {
        acknowledge(record);
    }
    return;
}
```

`RuntimeException` 단일 catch로 모든 Vision 실패를 동일하게 처리하고, 곧바로
`VISION_FAILED`로 종결한 뒤 ACK한다. 예외 타입(타임아웃/429/5xx vs 잘못된 이미지 등)에
따른 분기가 없다. `ADR-17`은 별도 SQS 트랙(`AnalysisProcessor`)의 계약에서만 "일시적
오류는 재시도 대상, 영구 오류는 FAILED 대상"을 요구하며, 이 Redis Streams 경로에는 그
구분이 적용돼 있지 않다.

### f. 재시도 한도와 DLQ

**없음.**

- 실패 횟수를 세는 컬럼/카운터가 `ProductAnalysisSession`이나 메시지 payload
  (`AnalysisTaskMessage`: `analysisId`, `imageUrls`만 있음) 어디에도 없다.
- DLQ로 옮기는 로직이 없다.
- 정확한 동작: **무한 재시도도 아니고, 유실도 아니다.** ACK되지 않은 메시지는 Stream에
  그대로 남아 PEL에 계속 존재한다(`XDEL`을 호출하는 코드가 없으므로 데이터 자체는 안
  지워짐). 다만 b에서 확인했듯 자동으로 재전달(redeliver)하는 경로가 없으므로, 사람이
  수동으로 `XCLAIM`하지 않는 한 **영구히 대기 상태로 정체**된다 — "재시도"도 "유실"도
  아닌 제3의 상태(무기한 stall)다.

### g. Graceful Shutdown

**부분적 / 일부 확인 불가.**

```java
// RedisStreamConsumerConfig.java:61-66
@PreDestroy
public void stop() {
    if (container != null) {
        container.stop();
    }
}
```

- 이 저장소 코드가 하는 일은 여기까지다: `@PreDestroy`에서 `container.stop()`을
  호출할 뿐, 처리 중인 메시지를 끝까지 기다리는 타임아웃/drain 로직을 이 프로젝트가
  직접 작성하지 않았다.
- `StreamMessageListenerContainer.stop()`이 내부적으로 진행 중인 `onMessage()` 콜백을
  끝까지 기다리는지 여부는 spring-data-redis 라이브러리 내부 구현에 달려 있다 — Day3
  3단계에서 실제 라이브러리 소스로 확인했다(아래 "g-1. `StreamMessageListenerContainer.stop()`
  소스 조사" 참고). 더 이상 확인 불가가 아니다.
- SIGTERM 자체를 가로채는 별도 코드(`Runtime.getRuntime().addShutdownHook` 등)도 없다
  — Spring Boot의 기본 `@PreDestroy` 처리 경로(임베디드 톰캣 종료 → ApplicationContext
  close)에 의존한다.

### g-1. `StreamMessageListenerContainer.stop()` 소스 조사 (Day3 3단계)

**확인한 Spring Data Redis 버전**

`3.5.11` — `./gradlew dependencies --configuration compileClasspath`로 확인
(`org.springframework.data:spring-data-redis:3.5.11`, Spring Boot `3.5.14`가 관리하는 버전).
로컬 Gradle 캐시(`~/.gradle/caches/modules-2/files-2.1/org.springframework.data/spring-data-redis/3.5.11/`)에는
컴파일된 jar만 있고 sources jar가 없어, 같은 버전 태그(`3.5.11`)의 공식 GitHub 소스
(`https://github.com/spring-projects/spring-data-redis/tree/3.5.11`)로 대체 확인했다 — 태그가
정확히 `3.5.11`로 존재함을 GitHub Releases API로 먼저 확인한 뒤 그 태그의 원본 파일을 그대로 읽었다.

**확인한 클래스·메서드**

- `org.springframework.data.redis.stream.StreamMessageListenerContainer`(인터페이스, `SmartLifecycle` 상속) —
  `stop()` 계열 메서드의 Javadoc.
- `org.springframework.data.redis.stream.DefaultStreamMessageListenerContainer` — 실제 구현체의
  `start()`/`stop()`/`stop(Runnable)`.
- `org.springframework.data.redis.stream.StreamPollTask`(및 내부 `PollState`) — `cancel()`,
  `run()`/`doLoop()`.

**소스상 보장되는 동작**

1. `DefaultStreamMessageListenerContainer.stop()`은 `lifecycleMonitor`로 동기화된 블록 안에서
   `subscriptions.forEach(Cancelable::cancel)`을 호출한 뒤 `running = false`로 설정하고
   **즉시 반환**한다. `join()`이나 `Future.get()` 같은 대기 호출이 전혀 없다.
2. `Cancelable::cancel` → `StreamPollTask.cancel()` → `PollState.cancel()`은
   `state = State.CANCELLED`로 volatile 필드를 바꿀 뿐이다 — 스레드 인터럽트도, 블로킹도 없다.
3. `StreamPollTask.doLoop()`는 `do { readRecords(); deserializeAndEmitRecords(raw); }
   while (pollState.isSubscriptionActive());` 구조다. 취소 여부는 **루프 조건에서만** 확인되고,
   그 확인은 현재 반복(현재 배치의 모든 레코드에 대한 `listener.onMessage()` 호출 포함)이
   **전부 끝난 뒤**에야 일어난다. 즉 `cancel()`이 `onMessage()` 실행 중에 호출되면 그 콜백은
   중간에 끊기지 않고 끝까지 실행된다 — 다만 이것은 "다음 반복을 시작하지 않는다"는 루프
   구조의 부산물이고, `stop()` 자체가 그 완료를 기다려주는 것은 아니다(다음 항목 참고).
4. `Subscription.await(Duration)`(`TaskSubscription.await` → `StreamPollTask.awaitStart`)은
   Task가 **시작**하는 것만 기다리는 API다 — 정지/완료를 기다리는 대응 API(`awaitTermination`
   등)는 `StreamMessageListenerContainer`/`Subscription`/`Task` 어디에도 없다.
5. `StreamMessageListenerContainerOptionsBuilder`의 기본 실행기는
   `new SimpleAsyncTaskExecutor()`다(공유 스레드 풀이 아니라 태스크마다 스레드를 새로 빌려주는
   실행기) — 이 프로젝트(`RedisStreamConsumerConfig`)는 `executor(...)`를 오버라이드하지 않으므로
   기본값을 그대로 쓴다. `DefaultStreamMessageListenerContainer`는 이 executor를 `stop()`이나
   다른 어떤 메서드에서도 종료(shutdown)하지 않는다 — 애초에 풀을 소유하지 않는 설계다.
6. Javadoc(`receive(...)` 3개 오버로드 공통 문구): "On `stop()` all subscriptions are cancelled
   prior to shutting down the container itself." — 이는 **순서**(cancel 먼저, container 상태
   전환 나중)만 보장한다는 뜻이고, "각 구독의 in-flight 콜백 완료를 기다린다"는 의미가 아니다.

**소스만으로 확인할 수 없는 동작**

- `cancel()` 신호 이후 poll 스레드가 실제로 종료되기까지 걸리는 실제 시간 — 이 프로젝트는
  `pollTimeout(Duration.ofSeconds(2))`로 블로킹 `XREADGROUP`을 쓰므로, 최악의 경우 그 블로킹
  호출이 끝나야 다음 루프 조건 검사를 만나 종료된다. 이 지연이 실제로 몇 초인지는 Lettuce의
  블로킹 명령 구현과 OS/네트워크 타이밍에 달려 있어 소스 읽기만으로 정확한 수치를 낼 수 없다.
  (참고: 이번 세션의 Testcontainers 기반 `*MySqlIT`/`SqsAnalysisJobLocalStackIT` 실행 로그에서
  컨텍스트 종료 시점에 `io.lettuce.core.RedisException: Connection closed`가 `WARN`/`ERROR`로
  반복 기록되는 것을 실제로 관찰했다 — poll 스레드가 여전히 살아있는 채로 연결이 먼저 끊기는
  경쟁이 실제로 일어난다는 간접 증거다.)
- Spring Boot의 JVM 종료 훅(`SpringApplicationShutdownHook`)이 `ApplicationContext.close()`를
  호출하는 스레드와, `SimpleAsyncTaskExecutor`가 만든 poll 스레드(비-daemon으로 추정) 사이의
  실제 JVM 종료 순서 — 소스상 `stop()`이 대기하지 않는다는 것은 확인했지만, JVM 자체가 남은
  비-daemon 스레드를 얼마나 기다리는지, docker `SIGTERM`→`SIGKILL` 유예 시간 안에 실제로 그
  콜백(`VisionAnalysisService.analyze()`, OpenAI 호출로 수십 초 소요 가능)이 끝나는지는
  런타임에서 직접 관찰해야 한다.
- `errorHandler.handleError(...)`의 기본 구현(로깅 후 계속)이 종료 도중 발생하는 연결 예외를
  어떤 로그 레벨/빈도로 남기는지의 실제 운영 영향 — 소스는 "로깅 후 계속 진행"까지만 보장하고,
  실제 노이즈 총량은 실행해봐야 안다.

**Day 5 실제 테스트가 필요한 이유**

소스는 "`stop()`이 in-flight `onMessage()` 완료를 기다려주지 않는다"는 것까지만 증명한다 —
이 자체는 이미 위험 신호지만, 실제로 얼마나 위험한지(메시지가 실제로 몇 초 만에, 몇 %의 확률로
끊기는지)는 **타이밍 문제**라 소스 읽기로는 알 수 없다. `docker compose stop -t 90`처럼 실제
프로세스에 SIGTERM을 보내 (1) callback이 실제로 완료됐는지, (2) DB Commit이 됐는지, (3) XACK이
됐는지, (4) 종료까지 걸린 실제 시간을 관찰해야 "Redis Streams 경로는 SQS 트랙과 달리 shutdown
coordinator가 없다"는 이 갭의 실제 크기를 안다. 아래 "g-2. Day 5 통합 테스트 설계"에 방법만
남기고 이번 단계에서는 실행하지 않는다.

### g-2. Day 5 통합 테스트 설계 (실행하지 않음, 방법만 기록)

실행 Profile 조합(단독 아님 - application-redis-baseline-test.yml 참고):
`SPRING_PROFILES_ACTIVE=local,redis-baseline-test` (+ `analysis.redis-baseline-test.vision-delay-ms`로 지연 설정)

```text
local,redis-baseline-test 조합 기동
  + FakeVisionAnalysisService(Sleeper로 결정적 지연) — ai/vision/service/FakeVisionAnalysisService.java
→ 메시지 발행(XADD) 후 Consumer가 그 메시지를 받아 startVisionProcessing() 커밋,
  FakeVisionAnalysisService.analyze() 안에서 sleep 중인 시점을 노려 프로세스에 SIGTERM
  (`docker compose stop -t 90` 또는 로컬 `kill -TERM <pid>`)
→ sleep이 끝난 뒤 onMessage()의 나머지(DB 저장 + XACK)가 실제로 실행됐는지 확인
    - callback 완료 여부: 로그에 "Vision 완료"/예외 로그가 남는지, sleep 종료 시각과
      프로세스 실제 종료 시각을 비교
    - DB Commit 여부: 프로세스 종료 후 별도 연결로 `product_analysis_session`의 상태가
      COMPLETED/VISION_FAILED로 바뀌어 있는지 조회
    - XACK 여부: `redis-cli XPENDING <stream> <group>`으로 그 메시지가 더 이상 pending에
      없는지 확인
    - 종료까지 걸린 시간: SIGTERM을 보낸 시각부터 프로세스가 실제로 종료(포트 응답 없음/
      프로세스 exit)한 시각까지 측정, docker stop timeout(80~90초)과 비교
→ sleep 지연을 (a) 아주 짧게(즉시 완료), (b) docker stop timeout보다 길게 두 가지로 나눠
  각각 실행해, "정상적으로 끝나는 경우"와 "타임아웃에 걸려 강제 종료되는 경우"를 모두 관찰한다
```

### g-3. Day 5 실행 결과 (실제 1회 실행, 2026-09-11)

위 설계 중 (a) 계열(sleep이 docker stop timeout보다 훨씬 짧은 경우, 8초)만 1회 실제로
실행했다 — (b) 계열(sleep이 timeout보다 긴 경우)은 이번 실행 범위에 포함하지 않았다(미실행,
PASS로 표시하지 않음).

**실행 방법**: `backend/scripts/day5-redis-sigterm-test.sh` (신규 스크립트, 재사용 가능).
Consumer 코드는 무수정 — `AnalysisTaskConsumer.onMessage()` 그대로.

기존 `autique-local-mysql`은 재사용하지 않았다 — 사전 점검(`DESCRIBE product_analysis_session`)
에서 그 DB의 실제 `status` 컬럼 ENUM에 `QUEUED`가 빠져 있는 것을 발견했다(Hibernate
`ddl-auto=update`가 이미 존재하는 enum 컬럼의 값 목록을 재생성하지 않는 한계로 보인다 — Java의
`AnalysisStatus`는 `QUEUED`를 포함하지만 그 DB 테이블은 더 예전 상태로 남아 있다). 이는 사용자의
실제 로컬 개발 DB라 스키마를 직접 고치지 않고, 대신 일회용 빈 MySQL 컨테이너를 띄워 최신
엔티티 코드가 `ddl-auto=update`로 올바른 스키마를 새로 만들게 했다(`autique-local-redis`는
스트림 발행/소비에만 쓰므로 그대로 재사용). **이 자체가 부수적으로 발견한 별개의 스키마 드리프트
이슈이며, 이번 Day 5 3단계의 수정 대상은 아니다 — 기록만 남긴다.**

사전 확인: 테스트 실행 시점에 로컬 IDE 기반 `local` 프로세스는 port 8080에 떠 있지 않았고
(`netstat`로 확인), 같은 Redis의 `ai-analysis-workers` consumer group에 idle 값이 수억 ms인
과거 실행의 죽은 consumer 720여 개만 남아 있었을 뿐 활성 consumer/pending 메시지는 없었다
(`XINFO GROUPS`/`XINFO CONSUMERS`로 확인) — 실제 로컬 개발 프로세스가 테스트 메시지를 가로챌
위험은 없었다.

**측정값(스크립트 실제 출력, 가공 없음)**:

| 항목 | 값 |
|---|---|
| `visionDelayMs` (sleep 길이) | 8000ms |
| SIGTERM 방식 | `docker stop -t 90 <컨테이너>` (real SIGTERM) |
| in-flight 확인 | seed 직후 `VISION_PROCESSING` 커밋을 실제로 bounded polling(≤15s)으로 관찰 — SIGTERM 이전에 Consumer가 genuine in-flight였음을 확인 |
| `docker stop` 반환까지 걸린 시간 | **412ms** |
| 최종 DB `status` | **VISION_PROCESSING** (변하지 않음 — COMPLETED/VISION_FAILED로 전이되지 않았다) |
| SIGTERM 이후 `XPENDING` | **1** (해당 메시지가 계속 pending — ACK되지 않았다) |
| 판정 | **CALLBACK_INTERRUPTED** — sleep(8s)이 끝나기 전에 컨테이너가 종료됐다 |

**해석**: `docker stop`이 SIGTERM 발송 후 겨우 412ms 만에 반환됐다 — 8초 sleep이 끝나기까지
기다리지 않고 프로세스가 종료됐다는 뜻이다. 이는 이 문서 g절이 소스 코드 레벨에서 예측한 그대로다:
Spring Boot의 기본 graceful shutdown(`server.shutdown: graceful`)은 **웹 요청 계열만** 커버하고,
이 non-web Redis Stream consumer의 poll/callback 스레드는 감싸지 않는다. 그 결과: (1) callback
(`onMessage()`의 DB 저장 + XACK)이 완료되지 못했고, (2) DB에는 `VISION_PROCESSING`으로 멈춘
채 고아가 된 세션이 남았으며, (3) 메시지는 유실되지는 않았지만(XPENDING=1, 향후 XCLAIM으로
재처리 가능) ACK도 되지 않아 이 세션은 별도의 stale-PEL 재처리 로직 없이는 영영 완료되지 않는다.
**메시지 유실은 없었지만, "graceful하게 완료"도 되지 않았다 — 이 경로에는 SQS Worker 트랙에
있는 shutdown coordinator(§Day5 2단계에서 구현/검증)에 대응하는 장치가 없다는 이 문서의 가설이
실제 실행으로 확인됐다.**

**제한사항(정직하게 기록)**: 이번 실행에서는 컨테이너 stdout 로그를 정리(`docker rm -f`) 전에
별도로 보존하지 않았다 — 위 판정은 DB `status`/Redis `XPENDING` 조회로 직접 관찰한 값이며, 로그
문자열("Vision 완료" 등) 기반 교차검증은 하지 않았다. sleep이 timeout보다 긴 (b) 케이스는
실행하지 않았다(현재 관찰된 결과로 미루어 (a)보다 더 확실히 실패할 것으로 예상되지만, 이는
추정이지 실행 결과가 아니다 — 별도 실행 전까지 확정하지 않는다).

### h. 상태 모델: 상태 목록/전이 위치, 조건부 UPDATE인가 조회 후 저장인가

**"조회 후 저장" 방식.**

상태 목록(`AnalysisStatus.java:3-15`), 11개:
`CREATED, IMAGE_UPLOADED, QUEUED, VISION_PROCESSING, AWAITING_USER_CONFIRMATION,
PRICING_PROCESSING, COMPLETED, IMAGE_UPLOAD_FAILED, QUEUE_FAILED, VISION_FAILED,
PRICING_FAILED`

전이는 전부 `ProductAnalysisSession` 엔티티의 메서드에서 결정된다
(`markImageUploaded`, `failImageUpload`, `markQueued`, `failQueueing`,
`startVisionProcessing`, `completeVision`, `failVision`, `startPricing`,
`completePricing`, `failPricing` — 전부 `ProductAnalysisSession.java:76-142`).
호출 패턴은 항상 `sessionRepository.findById(id)` → 엔티티 메서드 호출(메모리에서
상태 변경, 일부는 가드로 예외) → `sessionRepository.save(session)`이다. SQL
`UPDATE ... WHERE status = ...` 형태의 조건부 UPDATE를 쓰는 코드는 없다
(`ProductAnalysisSessionRepository`는 커스텀 쿼리 메서드가 아예 없는 순수
`JpaRepository`). 같은 패키지의 `ProductAnalysisJob`(SQS 트랙)은 정반대로 "상태
전이 메서드를 엔티티에 두지 않고 Repository의 조건부 UPDATE로만 전이한다"고 주석에
명시돼 있어(`ProductAnalysisJob.java:18`) 대조된다.

### i. 결과 저장과 상태 전이가 한 트랜잭션인가

**있음(하나의 트랜잭션).**

`completeVision(json)`이 `visionResultJson`과 `status`를 같은 엔티티 인스턴스에 함께
쓰고(`ProductAnalysisSession.java:108-111`), 바로 이어지는 `sessionRepository.save(session)`
한 번의 호출로 커밋된다(`AnalysisTaskConsumer.java:94-97`). `save()`는
`SimpleJpaRepository`의 기본 `@Transactional` 경계 안에서 실행되므로, 결과 필드와 상태
필드는 같은 UPDATE/트랜잭션에 속한다. 실패 경로도 동일하게 `failVision()`이 `status`와
`failureMessage`를 함께 설정한 뒤 한 번의 `save()`로 저장된다
(`AnalysisFailureRecorder.java:36-41`, `@Transactional(REQUIRES_NEW)`로 명시적으로도
감싸져 있음).

### j. Consumer 수: 여러 인스턴스가 붙어도 안전한가, Consumer 이름은 고정인가 고유인가

**부분적.**

```java
// RedisStreamConsumerConfig.java:46
String consumerName = properties.getConsumerPrefix() + "-" + UUID.randomUUID();
```

- Consumer 이름은 **인스턴스별 고유값**(기동마다 새 UUID)이다. 고정값이 아니므로 여러
  인스턴스가 같은 이름으로 충돌할 일은 없다.
- 같은 Consumer Group에 여러 Consumer 이름이 붙는 것 자체는 Redis Streams의 기본
  동작이라, **신규(`>`) 메시지 분배**는 인스턴스를 늘려도 안전하게 나눠 가진다(같은
  메시지가 두 인스턴스에 동시에 새로 배달되지는 않는다).
- 다만 b/f에서 확인한 대로, **PEL 재선점 로직이 없으므로** 인스턴스 하나가 죽어 메시지를
  못 끝내고 남기면, 살아있는 다른 인스턴스가 그 메시지를 자동으로 넘겨받지 못한다.
  "새 메시지 분배"는 안전하지만 "죽은 인스턴스의 잔여 작업 인계"는 안전하지 않다 —
  그래서 "부분적"으로 표기했다.

## 표 요약

| 항목 | 판정 | 핵심 근거 |
|---|---|---|
| a. ACK 시점 (완료 후) | 있음 | `AnalysisTaskConsumer.java:78-80`, `93-103` — `save()` 성공 반환 후에만 `acknowledge()` |
| b. PEL 재선점(XCLAIM 등) | 없음 | 프로덕션 코드에 `XPENDING`/`XCLAIM`/`XAUTOCLAIM` 호출 없음, idle 기준값도 없음 |
| c. 중복 처리 방어 | 부분적 | `ProductAnalysisSession.java:99-106` 상태 가드는 있으나 조회 후 저장 방식, `@Version`/조건부 UPDATE 없음 |
| d. Lease/Fencing | 없음 | `worker_id`/`@Version` 컬럼 없음, 재선점 로직 자체가 없어 필요한 경로도 없음 |
| e. 실패 분류(일시적/영구) | 없음 | `AnalysisTaskConsumer.java:66-76` 단일 `RuntimeException` catch |
| f. 재시도 한도/DLQ | 없음 | 카운터/DLQ 없음. ACK 안 되면 PEL에 무기한 정체(유실도 무한재시도도 아님) |
| g. Graceful shutdown | 없음 (소스로 확인됨) | `DefaultStreamMessageListenerContainer.stop()`(spring-data-redis 3.5.11)은 `cancel()` 신호만 내리고 즉시 반환 — in-flight `onMessage()` 완료를 기다리는 대기/timeout 없음(g-1) |
| h. 상태 모델 | 조회 후 저장 | `AnalysisStatus.java:3-15`, `ProductAnalysisSession.java:76-142`, 조건부 UPDATE 아님 |
| i. 결과+상태 단일 트랜잭션 | 있음 | `completeVision()` + 단일 `save()` 호출 (`AnalysisTaskConsumer.java:93-103`) |
| j. 다중 Consumer 안전성 | 부분적 | 이름은 인스턴스별 고유(`RedisStreamConsumerConfig.java:46`), 신규 메시지 분배는 안전하나 PEL 인계는 불가 |

## 3. "인스턴스 2대 + Worker 1대 kill -9" 시나리오

인스턴스를 2대로 늘리면 각각 `worker-{UUID-1}`, `worker-{UUID-2}`라는 서로 다른 Consumer
이름으로 같은 Consumer Group(`ai-analysis-workers`)에 붙는다(`RedisStreamConsumerConfig.java:46,52-56`).
이 시점 이후 새로 `XADD`되는 메시지는 Redis가 두 Consumer에게 나눠 배분하므로, 정상
동작 중에는 문제가 없다. 문제는 그 중 한 인스턴스(예: `worker-1`)가 메시지를 하나 받아
`startVisionProcessing()`까지 저장한 직후, Vision 응답을 기다리는 도중에 `kill -9`로
죽는 경우다. 이 메시지는 `worker-1`이라는 이름으로 이미 `XREADGROUP`에 의해 배달됐고
아직 `XACK`되지 않았으므로 Redis 서버 관점에서는 Consumer Group의 PEL에 "owner:
`worker-1`" 상태로 그대로 남는다(b 참고). `worker-2`는 살아있지만, 이 프로젝트 코드에는
`XCLAIM`/`XAUTOCLAIM`을 호출하는 부분이 전혀 없고 `XREADGROUP`도 항상 `>`(새 메시지)만
요청하므로, `worker-2`가 이 메시지를 자동으로 넘겨받을 방법이 없다(j 참고). 결과적으로
그 세션은 `VISION_PROCESSING` 상태로 영구히 멈추고, `GET /analyze/{taskId}`를 아무리
폴링해도 상태가 바뀌지 않는다 — 사람이 `redis-cli XCLAIM`으로 수동 개입하지 않는 한
복구되지 않는다. 게다가 수동 개입으로 메시지가 재전달되더라도, 세션이 이미
`VISION_PROCESSING`(≠`QUEUED`)이라 `startVisionProcessing()`이 예외를 던지고 Consumer는
이를 "중복 메시지"로 오인해 그대로 `XACK`하고 버려버린다(`AnalysisTaskConsumer.java:51-60`,
갭 2). 즉 인스턴스를 늘려도 이 장애 시나리오에서 얻는 가용성 이득은 "새 요청은 계속
처리된다"는 것뿐이고, 죽은 인스턴스가 들고 있던 그 세션 하나는 어떤 자동 복구 경로도
없이 방치된다.

## 4. 갭별 보완 방법 (Redis Streams 내 vs 관리형 큐 기본 제공)

| 갭 | Redis Streams 안에서 보완 | 관리형 큐(SQS 등)가 기본 제공 |
|---|---|---|
| PEL 재선점 없음 (b) | 주기적 스케줄러가 `XPENDING`으로 idle-time 긴 항목을 찾아 `XCLAIM`/`XAUTOCLAIM`으로 회수하는 로직을 직접 구현 | Visibility Timeout이 지나면 큐가 자동으로 메시지를 다시 노출(별도 recovery 코드 불필요) |
| 중복 처리 방어가 조회 후 저장 방식 (c) | 엔티티에 `@Version` 컬럼을 추가해 낙관적 락을 걸거나, Repository에 조건부 `UPDATE ... WHERE status = 'QUEUED'`를 직접 작성 | 큐 자체는 여전히 at-least-once라 동일한 보완이 필요 — 이 갭은 관리형 큐로 옮겨도 자동 해결되지 않음(멱등 처리는 Consumer 책임) |
| Lease/Fencing 없음 (d) | 세션에 `worker_id`/lease 만료시각 컬럼을 추가해 재선점 시 값을 갱신하고, 저장 시 그 값을 조건으로 검증 | Visibility Timeout 자체가 사실상의 lease 역할을 하지만, receiveCount/ApproximateReceiveCount 외에 별도 fencing 토큰은 SQS도 기본 제공하지 않음(FIFO 큐의 group 단위 순서 보장 정도) |
| 실패 분류 없음 (e) | Vision 호출부에서 예외 타입을 세분화(타임아웃/429/5xx vs 4xx 등)해 재시도 대상과 즉시 FAILED 대상을 분기하는 코드를 직접 작성 | 재시도 정책은 여전히 애플리케이션이 정해야 하지만, 재시도 자체(카운트 증가, 지연 재배달)는 큐가 기본 수행 |
| 재시도 한도/DLQ 없음 (f) | 메시지 payload 또는 세션에 재시도 횟수 필드를 추가하고, 한도 초과 시 별도 "실패 전용" Stream으로 직접 `XADD`해 옮기는 코드를 작성 | Redrive Policy로 `maxReceiveCount` 초과 메시지를 DLQ로 자동 이동(설정만으로 제공) |
| PEL 모니터링/알림 없음 (5, 감사 1차 문서) | `XPENDING` summary를 주기적으로 조회해 개수/최대 idle-time을 메트릭으로 노출하는 코드를 직접 작성 | CloudWatch `ApproximateNumberOfMessagesNotVisible`/`ApproximateAgeOfOldestMessage` 등 지표를 큐가 기본 제공 |
| Graceful shutdown 시 in-flight 처리 보장이 불명확 (g) | `@PreDestroy`에서 `container.stop()` 전에 진행 중 메시지 완료를 기다리는 타임아웃/드레인 로직을 직접 작성 | Consumer가 SIGTERM 시 정상 종료하지 못해도, Visibility Timeout이 지나면 메시지가 자동으로 다시 노출되어 별도 drain 로직 없이도 유실을 막음 |
