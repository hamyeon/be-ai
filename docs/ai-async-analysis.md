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
  "size": 270,
  "conditionDescription": "...",
  "conditionGrade": "B",
  "failureStage": null,
  "failureMessage": null
}
```

`status`가 `QUEUED`/`VISION_PROCESSING`이면 `brand` 등은 전부 `null`이고, `*_FAILED` 상태면
`failureStage`/`failureMessage`가 채워진다.

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

## 이번 이슈 범위 밖 — 후속 확장 항목

- **자동 재시도**: 지금은 실패해도 재시도 로직이 없다. 실패 상태(`*_FAILED`)로 남을 뿐이다.
- **Pending 메시지 회수(claim)**: `XPENDING`으로 오래 미처리 상태인 메시지를 찾아서 다른 Consumer가
  `XCLAIM`으로 가져가 재처리하는 로직이 없다. 지금은 Consumer가 죽으면 그 메시지는 ack될 때까지 그
  Consumer 이름으로 계속 pending 상태로 남는다.
- **DLQ(Dead Letter Queue)**: 계속 실패하는 메시지(예: 파싱조차 안 되는 메시지)를 별도 큐로 옮겨서
  격리하는 처리가 없다. 지금은 그냥 pending 목록에 무기한 남는다.
- **알려진 구멍 하나**: Consumer가 `startVisionProcessing()` 저장에는 성공했지만 그 직후(Vision 호출 전)
  크래시하면, 세션은 `VISION_PROCESSING`에 멈춘 채로 남는다. 이 메시지가 재전달되면 현재 로직은
  "QUEUED가 아니니 이미 처리된 중복 메시지"로 오인해서 ack하고 버려버린다 — 사실은 Vision이 한 번도
  안 돌았는데도. Pending 회수 로직을 나중에 설계할 때 이 케이스(QUEUED도 아니고 완료/실패도 아닌 상태로
  오래 멈춰있는 세션)를 같이 다뤄야 한다.
