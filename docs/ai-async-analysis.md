# AI 분석 비동기 파이프라인 (#17)

`POST /api/products/analyze`가 Vision 분석이 끝날 때까지 요청을 붙잡고 있던 동기 방식을,
Redis Streams로 작업을 넘기고 즉시 응답하는 비동기 방식으로 바꾼 작업 정리.

## 왜

기존에는 이미지 업로드부터 OpenAI Vision 분석까지 전부 하나의 HTTP 요청 안에서 처리했다.
Vision 응답이 느려지면 요청 시간이 그대로 늘어나고, 그동안 서버 요청 스레드가 계속 점유된다.

## 구조

```
POST /api/products/analyze
  → 이미지 검증
  → S3 업로드
  → Redis Stream(XADD)에 {analysisId, imageUrls} 적재
  → 세션 상태 QUEUED
  → 202 Accepted + { analysisId, status }  (여기서 응답 끝, Vision은 아직 안 돌아감)

AnalysisTaskConsumer (같은 Spring 애플리케이션 안의 백그라운드 컴포넌트)
  → Redis Stream에서 메시지 수신(XREADGROUP)
  → 세션이 QUEUED 상태인지 확인 (아니면 중복 메시지로 보고 버림)
  → VisionAnalysisService 호출
  → 성공/실패 결과를 MySQL(ProductAnalysisSession)에 저장
  → 저장이 "성공"했을 때만 XACK

GET /api/products/analyze/{taskId}
  → ProductAnalysisSession 조회해서 현재 상태 + (있으면) Vision 결과 + (있으면) 실패 정보 반환
  → 클라이언트가 이 API를 폴링해서 진행 상황을 확인
```

`taskId`는 별도로 발급하지 않고 기존 `ProductAnalysisSession.id`(analysisId)를 그대로 쓴다.

## 상태 흐름

```
CREATED → IMAGE_UPLOADED → QUEUED → VISION_PROCESSING → AWAITING_USER_CONFIRMATION → ...(기존 Pricing 흐름과 동일)

실패:
IMAGE_UPLOAD_FAILED  (S3 업로드 실패)
QUEUE_FAILED          (Redis Stream 적재 실패, 이번에 신규 추가)
VISION_FAILED         (Vision 분석 실패)
```

`QUEUED` 상태가 아닌 세션에 대해 `startVisionProcessing()`을 호출하면 `InvalidAnalysisStatusException`이
발생하도록 엔티티에 가드를 추가했다 — Consumer가 같은 메시지를 중복으로 받아도 Vision이 두 번 실행되지
않는다. Redis Stream의 Consumer Group은 최소 한 번 전달(at-least-once)을 보장하므로 이 가드가 실질적인
중복 방지 장치다.

## ⚠️ Breaking Change

`POST /api/products/analyze`의 응답이 바뀌었다.

| | 이전 | 이후 |
|---|---|---|
| HTTP 상태 | 200 OK | **202 Accepted** |
| 응답 본문 | `{ analysisId, imageUrls, brand, modelName, color, size, conditionDescription, conditionGrade }` (Vision 결과 즉시 포함) | `{ analysisId, status }` (Vision 결과 없음, 상태만) |

Vision 분석 결과를 확인하려면 응답으로 받은 `analysisId`로 `GET /api/products/analyze/{taskId}`를
폴링해야 한다. **프론트엔드에서 이 API를 쓰는 코드가 있다면 반드시 같이 수정해야 한다.**

`GET /api/products/analyze/{taskId}` 응답:

```json
{
  "analysisId": 1,
  "status": "AWAITING_USER_CONFIRMATION",
  "imageUrls": ["..."],
  "brand": "Nike",
  "modelName": "Dunk Low",
  "color": "Panda",
  "size": null,
  "boxIncluded": true,
  "conditionDescription": "...",
  "conditionGrade": "B",
  "defects": [
    {"type": "crease", "location": "toe_box", "severity": "moderate", "description": "앞코에 주름이 있습니다."}
  ],
  "candidates": [],
  "confidence": 0.6,
  "needsUserConfirmation": true,
  "warnings": ["사이즈 표기를 읽어낸 근거가 없어 값을 비웠습니다. 라벨이나 밑창 사진을 추가해 주세요."],
  "failureStage": null,
  "failureMessage": null
}
```

`status`가 `QUEUED`/`VISION_PROCESSING`이면 `brand` 등은 전부 `null`이고, `*_FAILED` 상태면
`failureStage`/`failureMessage`가 채워진다. 리스트 필드(`defects`/`candidates`/`warnings`)는
분석 전에도 `null`이 아니라 빈 배열로 나간다.

`warnings`와 `needsUserConfirmation`이 실려 나가는 이유는 #21의 근거 검증 때문이다. Vision이 근거
없이 채운 값은 저장 전에 제거되는데(`VisionEvidenceValidator`), 그러면 프론트 입장에서는 그냥 `null`로만
보인다. 위 예시처럼 `size`가 비었을 때 **왜 비었고 사용자에게 뭘 요청해야 하는지**는 `warnings`에만
들어 있다. 자세한 내용은 `docs/ai-vision-agent.md` 참고.

판단 근거(`evidence`)는 일부러 응답에 넣지 않았다. 항목마다 한국어 문장이 붙어 응답이 커지는데 폴링으로
반복 호출되는 API이고, 사용자에게 보여줄 정보도 아니다. 필요하면 `vision_result_json`에 그대로 있다.

## Redis Streams 설정

`application.yml`의 `analysis.stream.*`로 분리되어 있고, 환경변수로 재정의 가능하다.

```yaml
analysis:
  stream:
    key: ${ANALYSIS_STREAM_KEY:ai:analysis:requests}
    group: ${ANALYSIS_STREAM_GROUP:ai-analysis-workers}
    consumer-prefix: ${ANALYSIS_STREAM_CONSUMER_PREFIX:worker}
```

Consumer 이름은 기동할 때마다 `{consumer-prefix}-{UUID}`로 생성돼서 인스턴스별로 겹치지 않는다.

## ACK 정책 (요구사항 그대로 구현)

- Vision 분석이 성공하든 실패하든, **그 결과를 MySQL에 저장하는 데 성공했을 때만 XACK한다.**
- DB 저장 자체가 실패하면(예: 순간적인 DB 커넥션 문제) ack하지 않고 미처리 메시지(Pending Entries List)로
  남긴다 — 나중에 재처리할 수 있게.
- 세션이 아예 없거나(`analysisId`가 잘못됨), 이미 `QUEUED`가 아닌 상태(중복 전달)면 재처리할 이유가
  없으므로 바로 ack하고 버린다.
- 메시지 자체가 파싱이 안 되면(손상된 JSON) ack하지 않고 남긴다.

## 로컬 개발 환경

```bash
cd backend
docker compose up -d redis   # Redis만 띄우기 (healthcheck 포함)
./gradlew bootRun            # 앱은 로컬에서 직접 실행, localhost:6379로 접속
```

`spring.data.redis.host`/`port`는 기본값이 `localhost:6379`라서 로컬 개발 시 별도 설정 없이 바로 접속된다.
`docker compose up`으로 `backend` 서비스까지 같이 띄우면 `SPRING_DATA_REDIS_HOST=redis`로 자동 연결된다.

## 검증한 것 / 못한 것

- Redis Streams 명령어 자체(XADD/XGROUP/XREADGROUP/XACK/XPENDING)는 `redis-cli`로 직접 손으로 확인함
- `AnalysisTaskProducer`(실제 프로덕션 코드)가 실제 로컬 Redis에 XADD로 적재하고, 같은 Consumer Group으로
  XREADGROUP → XACK까지 왕복하는 것을 `@DataRedisTest` 통합 테스트로 확인함 (`AnalysisTaskProducerRedisIntegrationTest`,
  JPA/MySQL 없이 Redis 빈만 로드해서 기존 `application-secret.yml` 부재 문제를 우회)
- `AnalysisTaskConsumer`의 판단 로직(중복 방지, ack/미처리 분기)은 Redis를 목킹한 유닛 테스트로 전부 검증함
- **다만 `StreamMessageListenerContainer`가 실제로 메시지를 `AnalysisTaskConsumer.onMessage()`에 전달하는
  전체 배선(풀 애플리케이션 기동)은 검증하지 못했다** — `AnalysisTaskConsumer`가 `VisionAnalysisService`,
  `ProductAnalysisSessionRepository` 등 JPA/DataSource에 의존하는 빈들과 얽혀 있어서, 이걸 띄우려면 결국
  기존에도 있던 `application-secret.yml` 부재 문제(실제 MySQL 자격증명 없음)에 다시 걸린다. 실제 MySQL
  자격증명이 있는 환경(로컬에 `application-secret.yml` 채워넣거나 CI)에서 `./gradlew bootRun` 하고
  이미지를 업로드해서 실제로 `GET /analyze/{taskId}`가 `AWAITING_USER_CONFIRMATION`으로 바뀌는지 한 번
  확인해봐야 한다.

## 회수·재시도·실패 Stream (#110, #111에서 구현)

과거 이 섹션에 "범위 밖"으로 적었던 항목들은 이후 구현됐다. 핵심만 요약한다 — 세부 근거는
`AnalysisTaskConsumer`/`AnalysisStreamRecoveryScheduler`/`VisionAttemptCoordinator`/
`VisionFailureClassifier`/`VisionFailureStreamRecorder`의 클래스·메서드 주석 참고.

- **PEL 회수**: `AnalysisStreamRecoveryScheduler`가 `analysis.stream.recovery.min-idle-time-ms`
  (기본 240s = `analysis.vision.overall-timeout-ms` 180s + 여유)보다 오래 pending인 메시지를
  주기적으로 XCLAIM으로 회수해 재처리한다.
- **소유권(fencing token)**: `ProductAnalysisSession.visionProcessingToken`으로 재선점 시 이전
  시도의 뒤늦은 완료/실패 기록을 차단한다(`VisionAttemptCoordinator`).
- **재시도**: `VisionFailureClassifier`가 원인을 셋으로 가른다.
  (1) executor 포화(`RejectedExecutionException`, Vision을 아예 시도조차 못함) - 로컬 용량
      문제일 뿐이라 재시도 상한과 무관하게 항상 재시도한다. DB에 손대지 않는다.
  (2) 그 외 재시도 가치가 있는 오류(HTTP 429/5xx, 네트워크, 처리 상한 초과 등) - 진짜로 Vision을
      시도했다가 실패한 횟수(`ProductAnalysisSession.visionFailureAttemptCount`, Redis 배달
      횟수가 아니다)가 `analysis.vision.max-vision-failure-attempts`(기본 5) 미만이면 ACK하지
      않고 다음 PEL 회수를 기다린다 - 그것이 이 설계의 "재시도"다. Redis 배달 횟수를 쓰지 않는
      이유: executor 포화로 인한 재전달도 함께 세면, 여유가 생겨 실제로 처음 Vision을 호출한
      순간 이미 상한을 넘겨 곧바로 최종 실패로 확정돼버린다.
  (3) 재시도 가치가 없는 오류(4xx 등) - 즉시 최종 실패로 기록한다.
  `OpenAiVisionClient`의 단계별 자체 재시도(최대 5회)와 중복되지 않도록, 여기서는 그 재시도를
  반복하지 않는다.
- **실패 Stream**: 최종 실패는 `analysis.stream.failure-key`(기본 `ai:analysis:failures`)에
  analysisId 기반 이벤트로 발행된다. DB 커밋 → 발행 → 발행 플래그 커밋까지 성공해야 원본
  메시지를 ACK한다 - 셋 중 하나라도 실패하면 재전달을 통해 다시 시도한다(at-least-once,
  `VisionFailureStreamRecorder`).
- **종료 처리**: 종료 신호 시 새 작업 수신을 멈추고, 진행 중인 작업이 끝날 시간을
  `analysis.stream.shutdown-grace-period-ms`(기본 200s)만큼 기다린다. 이 시간 안에도 못 끝나면
  ACK되지 않은 채로 남아 다음 Worker가 회수한다(`RedisStreamConsumerConfig`). Docker의
  `stop_grace_period`(docker-compose.yml)가 이보다 짧으면 SIGKILL이 먼저 와 이 대기가
  무의미해지므로 반드시 함께 맞춘다.
- **DLQ**: 별도 DLQ는 없다 — 실패 Stream이 그 역할을 겸한다(소비자는 별도로 구축 필요).

## 관측 지표 및 알람 조건 (계측만 돼 있음 — 실제 알람 파이프라인 연동은 아직 없음)

`AnalysisStreamMetrics`가 Micrometer(`MeterRegistry`, 신규 의존성 없음)로 아래 지표를 남긴다.
현재 이 프로젝트에는 Prometheus/CloudWatch 등 실제 스크레이핑·알람 연동이 없어(actuator의
`/actuator/metrics/{name}`으로 개별 조회만 가능), 아래는 "계측된 지표"이지 "설정된 알람"이
아니다 — 알람 파이프라인을 실제로 붙일 때 이 조건을 그대로 옮기면 된다.

| 지표 | 의미 | 제안 알람 조건 |
|---|---|---|
| `analysis.stream.pending.count` | 현재 PEL에 남은 작업 수 | 지속적으로 증가하거나 비정상적으로 큰 값 |
| `analysis.stream.pending.oldest_idle_ms` | 가장 오래 대기 중인 PEL 항목의 idle 시간 | `min-idle-time-ms`(240s)의 2배 이상 지속 - 회수가 안 되고 있다는 뜻 |
| `analysis.stream.pending.last_check_age_ms` | XPENDING 조회가 마지막으로 성공한 지 지난 시간 | `scan-interval-ms`(30s)의 여러 배 이상 - 위 두 게이지가 신뢰할 수 없는 상태(조회 자체가 막힘) |
| `analysis.stream.redis_errors{operation}` | Redis 연결/명령 오류 횟수(xpending/xclaim 태그로 구분) | 짧은 시간 안에 연속 증가 |
| `analysis.stream.reclaimed` | PEL에서 회수(재시도)된 건수 | 급격한 증가 - Vision 호출 실패율이 올라갔다는 신호일 수 있음 |
| `analysis.stream.final_failures` | 최종 실패(VISION_FAILED)로 확정된 건수 | 급격한 증가 |
