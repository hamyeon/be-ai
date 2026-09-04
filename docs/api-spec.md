# Vintic 백엔드 API 명세서

프론트엔드 연동용 확정 API 명세서입니다. 아래 API들은 요청/응답 구조가 확정되어 더 이상 변경되지 않습니다.

## 배포 서버 정보

Base URL : http://44.193.0.36:8080

## 공통 응답 형식

모든 API는 공통 응답 DTO를 적용하여 `success`, `data`, `error` 형식으로 응답합니다.

성공 시 `data`에 결과가 담기고 `error`는 `null`, 실패 시 `data`는 `null`이고 `error`에 오류 코드와 메시지가 담깁니다.

```json
{
  "success": true,
  "data": { },
  "error": null
}
```

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40001,
    "message": "유효하지 않은 요청입니다."
  }
}
```

## 인증 (`X-User-Id`)

로그인한 사용자를 식별해야 하는 API에만 `X-User-Id` 헤더가 필요합니다. 실제 로그인 연동 전까지 사용하는 mock 인증이며, 헤더 값은 `users` 테이블에 존재하는 사용자 ID여야 합니다.

| API | 헤더 |
| --- | --- |
| `POST /api/auctions/{id}/bids` (입찰) | **필수** |
| `POST` / `GET` / `PATCH` / `DELETE /api/auctions/{id}/auto-bids/me` (자동입찰) | **필수** |
| `POST` / `DELETE /api/auctions/{id}/likes` (찜) | **필수** |
| `GET /api/auctions/{id}/result` (낙찰 결과) | **필수** |
| `POST /api/auctions/{id}/award/forfeit` (낙찰 포기) | **필수** |
| `GET /api/auctions/{id}/live` (실시간 경매) | **필수** |
| `GET /api/auctions/{id}/auto-bid/recommendation` | **필수** |
| `GET` / `POST /api/backup-offers/...` (차순위) | **필수** |
| `GET /api/auctions/{id}` (경매 상세) | 선택 — 있으면 조회 로그를 남깁니다 |
| `GET /api/auctions/{id}/bids` (입찰 이력) | 선택 |
| `GET /api/auctions/{id}/similar` (비슷한 상품) | 선택 — 있으면 찜 여부를 함께 내려줍니다 |
| `GET /api/recommendations/auctions` (추천) | 선택 — 없으면 Fallback 결과 |
| 그 외 (`/api/products/...`) | 불필요 |

헤더가 없거나, 숫자가 아니거나, 존재하지 않는 사용자 ID인 경우 `401 Unauthorized`(`40101`)를 반환합니다.

## 에러 코드 정리

| HTTP 상태 | code | 상황 |
| --- | --- | --- |
| 400 | 40001 | 요청 필수값 누락 등 검증 실패 |
| 400 | 40002 | 이미지 파일 없음 |
| 400 | 40003 | 잘못된 분석 상태에서의 요청 |
| 400 | 40004 | 필수 요청 헤더 누락 |
| 401 | 40101 | `X-User-Id` 헤더 누락 / 형식 오류 / 존재하지 않는 사용자 |
| 403 | 40301 | 판매자 본인 입찰 시도 |
| 403 | 40302 | 입찰 제한(패널티) 기간 중인 사용자의 입찰 시도 |
| 403 | 40303 | 낙찰자가 아닌 사용자의 낙찰 포기 시도 |
| 404 | 40400 | 존재하지 않는 경로 (URL 오타 등) |
| 404 | 40401 | 존재하지 않는 경매 |
| 404 | 40402 | 존재하지 않는 분석 세션 |
| 404 | 40403 | 존재하지 않는 차순위 제안 |
| 404 | 40404 | 등록된 자동입찰 없음 |
| 409 | 40901 | 이미 현재 최고입찰자인 사용자의 추가 입찰 시도 |
| 409 | 40902 | 아직 시작되지 않은 경매에 대한 입찰 시도 |
| 409 | 40903 | 종료/취소된 경매에 대한 입찰 시도 |
| 409 | 40904 | 최소 입찰 금액 미만 입찰 시도 |
| 409 | 40905 | 동일 Idempotency-Key에 다른 요청 내용 |
| 409 | 40906 | 자동입찰 상한가가 너무 낮음 |
| 409 | 40907 | 자동입찰 상한가가 인상되지 않음 |
| 409 | 40908 | 이미 등록된 자동입찰 존재 |
| 409 | 40909 | 동시성 충돌 (재시도 가능) |
| 409 | 40910 | 결제 기한 만료 |
| 409 | 40911 | 차순위 구매 기한 만료 |
| 409 | 40912 | 이미 처리된 차순위 제안에 재시도 |
| 409 | 40913 | 입찰 금액이 입찰 단위 배수가 아님 |
| 409 | 40914 | 결제 완료 후 낙찰 포기 시도 |
| 500 | 50001 | 서버 내부 오류 |
| 500 | 50002 | S3 이미지 업로드 실패 |
| 500 | 50003 | AI(OpenAI) 통신 오류 |
| 500 | 50004 | 분석 작업 Queue 적재 실패 |

---

# API 상세 설명

`POST /api/products/analyze`

신발 이미지 파일을 업로드하면 이미지를 S3에 저장하고, AI Vision 분석 작업을 비동기 Queue에 적재하는 API입니다.

분석 결과는 즉시 반환하지 않습니다. 요청이 정상적으로 접수되면 **202 Accepted**와 함께 `analysisId`를 반환하며, 실제 AI Vision 분석은 백그라운드 Worker에서 진행됩니다.

프론트에서는 반환받은 `analysisId`를 `taskId`로 사용하여 `GET /api/products/analyze/{taskId}` API를 폴링하고 분석 진행 상태 및 최종 결과를 조회합니다.

## Request ✔️

### Request Header

```
Content-Type: multipart/form-data
```

### Request Body

`multipart/form-data`

| 파트명 | 타입 | 설명 |
| --- | --- | --- |
| `images` | `file` (여러 개) | AI 분석을 수행할 신발 이미지 파일 목록 |

```
images: (신발 이미지 파일 1)
images: (신발 이미지 파일 2)
images: (신발 이미지 파일 3)
```

## Response ✔️

### Success ✅

### 202 Accepted - 분석 작업 접수 성공

```json
{
  "success": true,
  "data": {
    "analysisId": 1,
    "status": "QUEUED"
  },
  "error": null
}
```

`analysisId`는 생성된 AI 분석 작업의 식별자입니다.

분석 작업은 응답 이후 백그라운드에서 비동기로 진행되며, 반환받은 `analysisId`를 저장한 뒤 다음 폴링 API의 `{taskId}` 값으로 사용합니다.

```
GET /api/products/analyze/{taskId}
```

예를 들어 `analysisId`가 `1`인 경우 다음과 같이 조회합니다.

```
GET /api/products/analyze/1
```

### Failure ❌

### 400 Bad Request - 이미지 파일 없음

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40002,
    "message": "이미지 파일이 존재하지 않습니다."
  }
}
```

분석할 이미지가 요청에 포함되지 않은 경우 반환됩니다.

---

### 500 Internal Server Error - S3 이미지 업로드 실패

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 50002,
    "message": "S3 이미지 업로드 중 문제가 발생했습니다."
  }
}
```

업로드된 이미지를 S3에 저장하는 과정에서 오류가 발생한 경우 반환됩니다.

---

### 500 Internal Server Error - 분석 작업 Queue 적재 실패

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 50004,
    "message": "분석 작업을 큐에 적재하는 중 오류가 발생했습니다: ..."
  }
}
```

S3 이미지 업로드 이후 AI 분석 작업을 비동기 Queue에 등록하는 과정에서 오류가 발생한 경우 반환됩니다.

공통 응답 DTO를 적용하여 모든 응답을 `success`, `data`, `error` 형식으로 반환합니다. 성공적으로 분석 요청이 접수된 경우 `status`는 `QUEUED`이며, 이후 실제 분석 상태 및 결과는 `GET /api/products/analyze/{taskId}` API를 통해 확인합니다.

---

# API 상세 설명

`GET /api/products/analyze/{taskId}`

`POST /api/products/analyze` 요청 시 반환받은 `analysisId`를 이용하여 **AI 분석 작업의 진행 상태와 Vision 분석 결과를 조회하는 API**입니다.

프론트에서는 반환받은 `analysisId`를 `{taskId}`로 사용해 주기적으로 해당 API를 호출합니다.

Vision 분석이 완료되어 `AWAITING_USER_CONFIRMATION` 상태가 되거나, 분석 과정에서 `*_FAILED` 상태가 될 때까지 폴링합니다.

## 상태(status) 흐름

```text
CREATED
  → IMAGE_UPLOADED
  → QUEUED
  → VISION_PROCESSING
  → AWAITING_USER_CONFIRMATION
  → PRICING_PROCESSING
  → COMPLETED
```

| status | 의미 |
| --- | --- |
| `CREATED` | 분석 세션이 생성된 상태 |
| `IMAGE_UPLOADED` | S3 이미지 업로드가 완료된 상태 |
| `QUEUED` | 분석 작업이 Queue에 적재된 상태 (`POST /api/products/analyze`의 202 응답 시점) |
| `VISION_PROCESSING` | AI Vision 분석이 진행 중인 상태 |
| `AWAITING_USER_CONFIRMATION` | **Vision 분석 완료.** 사용자가 분석 결과를 확인하거나 수정하는 단계 |
| `PRICING_PROCESSING` | 사용자 확인 이후 가격 계산이 진행 중인 상태 |
| `COMPLETED` | 가격 계산까지 모두 완료된 상태 |
| `IMAGE_UPLOAD_FAILED` / `QUEUE_FAILED` / `VISION_FAILED` / `PRICING_FAILED` | 각 처리 단계에서 오류가 발생한 상태. `failureStage`, `failureMessage`에 오류 정보가 포함됨 |

---

## Request ✔️

### Path Variable

| 이름 | 타입 | 설명 |
| --- | --- | --- |
| `taskId` | `Long` | 분석 요청 시 반환받은 `analysisId` |

---

## Response ✔️

### Success ✅

### 200 OK - 분석 진행 중 (`VISION_PROCESSING`)

AI Vision 분석이 아직 완료되지 않은 상태입니다.

분석 결과가 생성되기 전이므로 상품 정보 관련 필드는 `null`, 리스트 타입 필드는 빈 배열(`[]`)로 반환됩니다.

```json
{
  "success": true,
  "data": {
    "analysisId": 1,
    "status": "VISION_PROCESSING",
    "imageUrls": [
      "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/example_shoe_1.png",
      "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/example_shoe_2.png",
      "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/example_shoe_3.png"
    ],
    "brand": null,
    "modelName": null,
    "color": null,
    "size": null,
    "boxIncluded": null,
    "conditionDescription": null,
    "conditionGrade": null,
    "defects": [],
    "candidates": [],
    "confidence": null,
    "needsUserConfirmation": null,
    "warnings": [],
    "failureStage": null,
    "failureMessage": null
  },
  "error": null
}
```

`VISION_PROCESSING` 상태인 경우 프론트에서는 일정한 간격으로 동일한 API를 다시 호출하여 분석 완료 여부를 확인합니다.

---

### 200 OK - Vision 분석 완료 (`AWAITING_USER_CONFIRMATION`)

AI Vision 분석이 완료되어 **사용자가 결과를 확인하거나 수정할 수 있는 상태**입니다.

브랜드, 모델명, 색상, 사이즈, 상품 상태, 하자 정보 등 사용자 확인 화면을 구성하는 데 필요한 분석 결과가 반환됩니다.

Vision 분석 과정에서 충분한 근거를 확인하지 못한 항목은 `null`로 반환될 수 있으며, 사용자 확인이 필요한 내용은 `warnings`와 `needsUserConfirmation`을 통해 전달합니다.

```json
{
  "success": true,
  "data": {
    "analysisId": 1,
    "status": "AWAITING_USER_CONFIRMATION",
    "imageUrls": [
      "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/example_shoe_1.png",
      "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/example_shoe_2.png",
      "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/example_shoe_3.png"
    ],
    "brand": "Nike",
    "modelName": "Dunk Low",
    "color": "Panda",
    "size": 270,
    "boxIncluded": true,
    "conditionDescription": "토박스에 얕은 주름이 있고 아웃솔에 사용감이 보이는 중고 상태입니다.",
    "conditionGrade": "B",
    "defects": [
      {
        "type": "crease",
        "location": "toe_box",
        "severity": "minor",
        "description": "토박스에 얕은 주름이 있습니다."
      },
      {
        "type": "sole_wear",
        "location": "outsole",
        "severity": "moderate",
        "description": "아웃솔 뒤꿈치 부분에 마모가 있습니다."
      }
    ],
    "candidates": [
      {
        "brand": "Nike",
        "modelName": "Dunk Low",
        "color": "Panda",
        "confidence": 0.92
      }
    ],
    "confidence": 0.92,
    "needsUserConfirmation": true,
    "warnings": [
      "사이즈 라벨이 흐릿하게 보여 사이즈는 사용자 확인이 필요합니다."
    ],
    "failureStage": null,
    "failureMessage": null
  },
  "error": null
}
```

`AWAITING_USER_CONFIRMATION` 상태가 반환되면 Vision 분석 단계의 폴링을 중단하고, 반환된 분석 결과를 사용자 확인/수정 화면에 표시합니다.

---

### 응답 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `analysisId` | `Long` | 분석 세션 ID |
| `status` | `String` | 현재 분석 진행 상태 |
| `imageUrls` | `String[]` | S3에 업로드된 이미지 URL 목록 |
| `brand` | `String` | AI가 인식한 브랜드. 근거가 부족하면 `null` |
| `modelName` | `String` | AI가 인식한 모델명. 근거가 부족하면 `null` |
| `color` | `String` | AI가 인식한 컬러웨이. 근거가 부족하면 `null` |
| `size` | `Integer` | 한국 신발 사이즈(mm 단위, 예: `270`) |
| `boxIncluded` | `Boolean` | 박스 포함 여부 |
| `conditionDescription` | `String` | 상품 상태에 대한 AI 분석 요약 |
| `conditionGrade` | `String` | 상품 상태 등급: `DS` / `A` / `B` / `C` / `UNKNOWN`. `UNKNOWN`은 AI가 등급을 판단하지 못한 경우로, 사용자가 직접 선택해야 합니다 |
| `defects` | 객체 배열 | 사진에서 발견된 하자 목록 (`type`, `location`, `severity`, `description`) |
| `candidates` | 객체 배열 | 모델 인식 후보 목록 (`brand`, `modelName`, `color`, `confidence`) |
| `confidence` | `Double` | 모델 인식 신뢰도 (`0.0 ~ 1.0`) |
| `needsUserConfirmation` | `Boolean` | 사용자 확인이 필요한 항목이 존재하는지 여부 |
| `warnings` | `String[]` | 사용자에게 추가 확인이 필요한 항목에 대한 안내 문구 |
| `failureStage` | `String` | 실패 단계: `IMAGE_UPLOAD` / `QUEUE` / `VISION` / `PRICING`. 정상 처리 시 `null` |
| `failureMessage` | `String` | 실패 상세 메시지. 정상 처리 시 `null` |

### `defects` 객체

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `type` | `String` | 하자 유형 (예: `crease`, `stain`, `sole_wear`, `scuff`) |
| `location` | `String` | 하자가 발견된 위치 (예: `toe_box`, `outsole`, `upper`) |
| `severity` | `String` | 하자 심각도 (`minor` / `moderate` / `severe`) |
| `description` | `String` | 하자에 대한 상세 설명 |

### `candidates` 객체

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `brand` | `String` | 후보 상품 브랜드 |
| `modelName` | `String` | 후보 상품 모델명 |
| `color` | `String` | 후보 상품 컬러웨이 |
| `confidence` | `Double` | 해당 후보에 대한 AI 인식 신뢰도 |

---

### 200 OK - 분석 실패 (`VISION_FAILED`)

비동기 분석 작업 자체가 실패한 경우에도 **조회 API 요청은 정상적으로 처리되었기 때문에 HTTP Status는 200 OK**로 반환됩니다.

분석 작업의 실패 여부는 `status`, `failureStage`, `failureMessage`를 통해 확인합니다.

```json
{
  "success": true,
  "data": {
    "analysisId": 1,
    "status": "VISION_FAILED",
    "imageUrls": [
      "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/example_shoe_1.png"
    ],
    "brand": null,
    "modelName": null,
    "color": null,
    "size": null,
    "boxIncluded": null,
    "conditionDescription": null,
    "conditionGrade": null,
    "defects": [],
    "candidates": [],
    "confidence": null,
    "needsUserConfirmation": null,
    "warnings": [],
    "failureStage": "VISION",
    "failureMessage": "OpenAI Vision API 호출 중 오류가 발생했습니다."
  },
  "error": null
}
```

`IMAGE_UPLOAD_FAILED`, `QUEUE_FAILED`, `VISION_FAILED`, `PRICING_FAILED` 상태가 반환되면 해당 분석 작업은 실패한 것으로 판단하고 추가 폴링을 중단합니다.

---

### Failure ❌

### 404 Not Found - 존재하지 않는 분석 세션

요청한 `taskId`에 해당하는 분석 세션이 존재하지 않는 경우 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40402,
    "message": "분석 세션을 찾을 수 없습니다. analysisId: 999"
  }
}
```

공통 응답 DTO를 적용하여 모든 응답을 `success`, `data`, `error` 형식으로 반환합니다.

비동기 분석 작업의 실패와 API 요청 자체의 실패는 다음과 같이 구분합니다.

- **분석 작업 실패**: `200 OK` + `success: true` + `status: *_FAILED`
- **API 요청 실패**: `4xx` + `success: false` + `error` 정보 반환

---

# API 상세 설명

`POST /api/products/calculate-price`

사용자가 AI Vision 분석 결과를 확인하거나 수정한 뒤, **최종 확정한 상품 정보**를 기준으로 추천 판매가를 계산하는 API입니다.

### 추천가 산정 방식 (우선순위)

우리 서비스는 중고 경매이므로, **같은 모델의 중고 실거래 시세를 1순위**로 씁니다.

| 순위 | 근거 | 계산 |
| --- | --- | --- |
| 1 | **당근마켓·후르츠패밀리 중고 실거래** (모델별 10건 이상, 현재 45개 모델·3,385건) | 실거래 중앙값 × 상태비율 × 구성품 반영률. 권장 범위는 실거래 IQR(25~75% 구간) |
| 2 | KREAM 새제품 시세 (1순위 매칭이 없을 때) | KREAM 평균 × 상태계수 × 구성품 반영률. 권장 범위는 ±5% |
| — | 둘 다 없으면 | `0원 + "시세 정보 없음"` |

2순위에서 **eBay는 기준 시세에 섞지 않습니다** (실측 근거: 당근 실거래 예측 오차가 KREAM 단독 25% vs 혼합 0.7/0.3 38% vs eBay 단독 155% — eBay는 해외 호가라 국내 실거래보다 중앙값 기준 약 2.5배 높습니다). eBay 매칭은 `ebayAveragePrice`/`ebayMatches`로 계속 내려가되 참고 정보이며, KREAM 매칭이 없으면 eBay만으로 가격을 만들지 않고 "시세 정보 없음"으로 응답합니다.

어느 경로로 계산됐는지는 `reason` 문구로 구분됩니다. 1순위 경로에서는 `kreamAveragePrice`/`ebayAveragePrice`가 `0`, `kreamMatches`/`ebayMatches`가 빈 배열로 내려갑니다 — 그 근거를 쓰지 않았다는 뜻이며 오류가 아닙니다.

1순위 경로의 상태 반영은 "전체 매물 대비 비율"입니다. 시세 중앙값이 상태 불문 전체 매물에서 나온 값이라, 분모도 같은 모집단(전체 매물 계수 `ALL`)을 씁니다. 예를 들어 `DS`(새상품)는 실측 계수 비율(약 1.6배)만큼 올라가고 `B`(약 0.7배)는 내려갑니다. `UNKNOWN`은 비율 1.0으로 시세 중앙값이 그대로 추천가가 됩니다.

상태 등급별 계수의 출처(원본은 `condition_rates.csv` — 재측정 시 아래 수치는 갱신됩니다. 2026-09 기준):

| 등급 | 계수 | 출처 |
| --- | --- | --- |
| `DS` | 0.724 | 실측 (당근 114건) |
| `S` | 0.70 | 기본값 (표본 30건, 부족) |
| `A` | 0.439 | 실측 (매물 설명 재분류, 189건) |
| `B` | 0.333 | 실측 (매물 설명 재분류, 130건) |
| `C` | 0.20 | 기본값 (표본 18건, 부족) |
| `ALL`(기준선) | 0.438 | 실측 (전체 매물 595건) |

실측/기본값 여부는 `reason` 문구 끝의 괄호로 응답마다 공개됩니다. 일부 모델(에어포스1, 가젤 등)은 모델 전용 실측 계수가 우선 적용됩니다.

`analysisId`를 통해 기존 AI 분석 세션과 연결되며, 가격 계산이 시작되면 분석 세션의 상태가 다음과 같이 변경됩니다.

```text
AWAITING_USER_CONFIRMATION
  → PRICING_PROCESSING
  → COMPLETED
```

가격 계산 요청은 Vision 분석이 완료되어 `AWAITING_USER_CONFIRMATION` 상태인 세션에서만 수행할 수 있습니다.

또한 **가격 계산은 분석 세션당 1회만 요청할 수 있습니다.** 계산이 완료되면 세션 상태가 `COMPLETED`로 변경되므로, 같은 `analysisId`로 다시 요청하면 `400 Bad Request`(`40003`)가 반환됩니다.

## 요청 필드 값 범위

| 필드 | 허용 값 |
| --- | --- |
| `conditionGrade` | `DS`(새상품) / `S` / `A` / `B` / `C` / `UNKNOWN`(판정 불가) |
| `componentStatus` | `FULL`(모두 포함) / `PARTIAL`(일부 포함) / `NONE`(없음) |

`conditionGrade`에 대해 두 가지를 유의해 주세요.

- **`UNKNOWN`을 그대로 보낼 수 있습니다.** Vision이 사진에서 상태를 판정할 근거를 찾지
  못하면 `UNKNOWN`을 반환합니다. 사용자가 고치지 않고 그대로 보내도 가격 계산은
  정상 동작하며, 기본 반영률이 적용됩니다.
- **`S`는 Vision이 반환하지 않습니다.** Vision의 등급은 `DS` / `A` / `B` / `C` / `UNKNOWN`
  다섯 가지입니다. `S`는 사용자가 직접 등급을 선택하는 화면에서만 들어옵니다.

---

## Request ✔️

### Request Header

```http
Content-Type: application/json
```

### Request Body

```json
{
  "analysisId": 1,
  "brand": "Nike",
  "modelName": "Dunk Low",
  "color": "Panda",
  "size": 270,
  "conditionGrade": "C",
  "componentStatus": "FULL"
}
```

### Request Body 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `analysisId` | `Long` | AI 분석 요청 시 생성된 분석 세션 ID |
| `brand` | `String` | 사용자가 최종 확인한 브랜드 |
| `modelName` | `String` | 사용자가 최종 확인한 모델명 |
| `color` | `String` | 사용자가 최종 확인한 컬러웨이 |
| `size` | `Integer` | 한국 신발 사이즈(mm 단위, 예: `270`) |
| `conditionGrade` | `String` | 최종 상품 상태 등급 |
| `componentStatus` | `String` | 박스 등 구성품 포함 상태 |

---

## Response ✔️

### Success ✅

### 200 OK - 중고 실거래 시세 기반 (1순위)

```json
{
  "success": true,
  "data": {
    "recommendedPrice": 40000,
    "baseMarketPrice": 40000,
    "kreamAveragePrice": 0,
    "ebayAveragePrice": 0,
    "minRecommendedPrice": 25000,
    "maxRecommendedPrice": 81000,
    "priceRange": "25,000원 ~ 81,000원",
    "reason": "당근마켓·후르츠패밀리에 올라온 Dunk Low 중고 매물 116건을 근거로 계산했습니다. 실거래가 중앙값은 40,000원이고, 매물의 절반이 25,000원 ~ 81,000원 사이에 있습니다. 상품 상태 UNKNOWN(기타 상태)는 전체 매물 시세 대비 100% 수준으로 반영했습니다. 구성품이 모두 포함되어 있어 100% 반영률을 적용했습니다. 이를 바탕으로 최종 추천가는 40,000원이며, 판매 권장 범위는 실거래 분포를 따라 25,000원 ~ 81,000원입니다.",
    "kreamMatches": [],
    "ebayMatches": []
  },
  "error": null
}
```

권장 범위가 ±5%보다 넓게 보일 수 있는데, 이는 **실제 거래 분포(IQR)**이기 때문입니다. 임의로 좁힌 범위보다 정직한 값입니다.

---

### 200 OK - KREAM/eBay 시세 기반 (2순위)

1순위 중고 시세에 매칭이 없을 때, KREAM에서 조건에 맞는 새제품 유사 거래를 조회한 뒤 상품 상태·구성품 상태를 반영하여 추천 판매가를 계산합니다. eBay 매칭은 참고 정보로만 함께 내려갑니다.

```json
{
  "success": true,
  "data": {
    "recommendedPrice": 23000,
    "baseMarketPrice": 114000,
    "kreamAveragePrice": 114000,
    "ebayAveragePrice": 137280,
    "minRecommendedPrice": 22000,
    "maxRecommendedPrice": 24000,
    "priceRange": "22,000원 ~ 24,000원",
    "reason": "KREAM 새제품 유사 거래 1건의 평균가 114,000원을 기준 시세로 사용했습니다. eBay 해외 매물 50건(평균 137,280원)은 국내 실거래와 차이가 커 참고로만 표시합니다. 상품 상태는 C(하자 있음)로 판단하여 20% 반영률을 적용했습니다. (실거래 표본이 부족해 기본값을 사용했습니다) 구성품이 모두 포함되어 있어 100% 반영률을 적용했습니다. 이를 바탕으로 최종 추천가는 23,000원으로 산정했으며, 판매 권장 범위는 22,000원 ~ 24,000원입니다. 추천가는 KREAM 평균가 대비 약 80% 낮은 수준입니다.",
    "kreamMatches": [
      {
        "source": "KREAM",
        "brand": "Nike",
        "modelName": "Dunk Low",
        "color": "Panda",
        "size": 270,
        "conditionGrade": "DS",
        "componentStatus": "NONE",
        "price": 114000,
        "url": "https://kream.co.kr/products/548447"
      }
    ],
    "ebayMatches": [
      {
        "source": "EBAY",
        "brand": "Nike",
        "modelName": "Dunk Low",
        "color": "Black White Panda",
        "size": 270,
        "conditionGrade": "B",
        "componentStatus": "NONE",
        "price": 27000,
        "url": "https://www.ebay.com/..."
      }
    ]
  },
  "error": null
}
```

가격 계산이 정상적으로 완료되면 해당 분석 세션의 상태는 `COMPLETED`로 변경됩니다.

---

### 응답 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `recommendedPrice` | `Integer` | 최종 추천 판매가 |
| `baseMarketPrice` | `Integer` | KREAM/eBay 시세를 반영하여 계산한 기준 시세 |
| `kreamAveragePrice` | `Integer` | 조회된 KREAM 유사 상품의 평균 가격 |
| `ebayAveragePrice` | `Integer` | 조회된 eBay 유사 상품의 평균 가격 |
| `minRecommendedPrice` | `Integer` | 추천 판매가 범위의 최솟값 |
| `maxRecommendedPrice` | `Integer` | 추천 판매가 범위의 최댓값 |
| `priceRange` | `String` | 사용자에게 표시할 추천 가격 범위 |
| `reason` | `String` | 시세 및 상태 반영 기준을 포함한 추천가 산정 설명 |
| `kreamMatches` | 객체 배열 | 추천가 계산에 사용된 KREAM 유사 거래 목록 |
| `ebayMatches` | 객체 배열 | 추천가 계산에 사용된 eBay 유사 거래 목록 |

### 시세 Match 객체

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `source` | `String` | 시세 데이터 출처 (`KREAM` / `EBAY`) |
| `brand` | `String` | 유사 거래 상품 브랜드 |
| `modelName` | `String` | 유사 거래 상품 모델명 |
| `color` | `String` | 유사 거래 상품 컬러웨이 |
| `size` | `Integer` | 상품 사이즈 |
| `conditionGrade` | `String` | 해당 거래 상품 상태 등급 |
| `componentStatus` | `String` | 해당 거래 상품 구성품 상태 |
| `price` | `Integer` | 거래 가격 |
| `url` | `String` | 해당 상품 또는 거래 페이지 URL |

---

### 200 OK - 시세 데이터 없음

입력한 상품 조건과 일치하거나 추천가 산정에 사용할 수 있는 KREAM/eBay 유사 거래 데이터를 찾지 못한 경우입니다.

API 요청 자체는 정상적으로 처리되었으므로 `200 OK`가 반환되지만, 추천 가격 관련 값은 `0`으로 반환됩니다.

```json
{
  "success": true,
  "data": {
    "recommendedPrice": 0,
    "baseMarketPrice": 0,
    "kreamAveragePrice": 0,
    "ebayAveragePrice": 0,
    "minRecommendedPrice": 0,
    "maxRecommendedPrice": 0,
    "priceRange": "시세 정보 없음",
    "reason": "입력한 브랜드, 모델명, 색상, 사이즈와 일치하는 KREAM/eBay 시세 데이터를 찾지 못했습니다. 추천 가격 산정을 위해서는 유사 거래 데이터가 추가로 필요합니다.",
    "kreamMatches": [],
    "ebayMatches": []
  },
  "error": null
}
```

이 경우에도 가격 계산 요청 자체는 완료된 것으로 처리되어 분석 세션 상태는 `COMPLETED`로 변경됩니다.

---

### Failure ❌

### 400 Bad Request - 필수값 누락

가격 계산에 필요한 필수 요청값이 누락된 경우 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40001,
    "message": "브랜드는 필수입니다."
  }
}
```

필수값별 오류 메시지는 다음과 같습니다.

- `분석 세션 ID는 필수입니다.`
- `브랜드는 필수입니다.`
- `모델명은 필수입니다.`
- `컬러웨이는 필수입니다.`
- `한국 사이즈는 필수입니다.`
- `상품 상태 등급은 필수입니다.`
- `구성품 상태는 필수입니다.`

---

### 400 Bad Request - 가격 계산이 불가능한 분석 상태

해당 분석 세션의 현재 상태가 `AWAITING_USER_CONFIRMATION`이 아닌 경우 반환됩니다.

Vision 분석이 아직 진행 중인 경우뿐만 아니라, 이미 가격 계산이 완료되어 `COMPLETED` 상태가 된 세션으로 다시 가격 계산을 요청하는 경우도 포함됩니다.

예를 들어 현재 세션이 `VISION_PROCESSING` 상태인 경우 다음과 같이 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40003,
    "message": "가격 계산을 요청할 수 없는 분석 상태입니다. 현재 상태: VISION_PROCESSING"
  }
}
```

이미 가격 계산이 완료된 세션으로 다시 요청하는 경우에는 현재 상태가 `COMPLETED`로 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40003,
    "message": "가격 계산을 요청할 수 없는 분석 상태입니다. 현재 상태: COMPLETED"
  }
}
```

가격 계산은 `AWAITING_USER_CONFIRMATION` 상태에서만 **분석 세션당 1회** 요청할 수 있습니다.

가격 계산이 완료된 이후 사용자가 상품 상태 등급이나 구성품 상태 등을 변경하여 다시 가격을 계산하려는 경우에는 기존 `analysisId`를 재사용할 수 없으며, `POST /api/products/analyze`부터 새로운 분석 세션을 생성해야 합니다.

---

### 404 Not Found - 존재하지 않는 분석 세션

요청한 `analysisId`에 해당하는 분석 세션이 존재하지 않는 경우 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40402,
    "message": "분석 세션을 찾을 수 없습니다. analysisId: 999"
  }
}
```

공통 응답 DTO를 적용하여 모든 응답을 `success`, `data`, `error` 형식으로 반환합니다.

---

# API 상세 설명

`GET /api/auctions/{auctionId}`

경매 ID를 이용하여 **특정 경매의 상세 정보를 조회하는 API**입니다.

경매 시작가, 현재가, 입찰 단위, 경매 시작·종료 시각, 현재 상태, 총 입찰 수 등 경매 상세 화면을 구성하는 데 필요한 정보를 반환합니다.

다음 최소 입찰 금액은 아래와 같이 계산합니다.

```text
다음 최소 입찰가 = currentPrice + bidIncrement
```

예를 들어 현재가가 `26,000원`이고 입찰 단위가 `1,000원`인 경우, 다음 최소 입찰가는 `27,000원`입니다.

---

## 경매 상태(status)

| status | 의미 |
| --- | --- |
| `SCHEDULED` | 아직 시작되지 않은 경매 |
| `LIVE` | 현재 진행 중이며 입찰이 가능한 경매 |
| `ENDED` | 종료된 경매 |
| `CANCELED` | 취소된 경매 |

---

## Request ✔️

### Path Variable

| 이름 | 타입 | 설명 |
| --- | --- | --- |
| `auctionId` | `Long` | 조회할 경매 ID |

---

## Response ✔️

### Success ✅

### 200 OK - 경매 상세 조회 성공

요청한 `auctionId`에 해당하는 경매가 존재하는 경우 경매 상세 정보를 반환합니다.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "productId": 1,
    "sellerId": 1,
    "currentWinnerId": 2,
    "startPrice": 20000,
    "currentPrice": 26000,
    "bidIncrement": 1000,
    "startAt": "2026-08-20T10:00:00",
    "endAt": "2026-08-27T10:00:00",
    "status": "LIVE",
    "bidCount": 6
  },
  "error": null
}
```

---

### 응답 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `id` | `Long` | 경매 ID |
| `productId` | `Long` | 경매 대상 상품 ID |
| `sellerId` | `Long` | 경매 상품을 등록한 판매자 ID |
| `currentWinnerId` | `Long` | 현재 최고 입찰자 ID. 아직 입찰이 없는 경우 `null` |
| `startPrice` | `Long` | 경매 시작 가격 |
| `currentPrice` | `Long` | 현재가. 입찰이 있으면 최고 입찰가이고, 아직 입찰이 없으면 `startPrice`와 같은 값 |
| `bidIncrement` | `Long` | 최소 입찰 증가 단위. 다음 최소 입찰가는 `currentPrice + bidIncrement` |
| `startAt` | `LocalDateTime` | 경매 시작 시각 |
| `endAt` | `LocalDateTime` | 경매 종료 시각 |
| `status` | `String` | 현재 경매 상태 (`SCHEDULED` / `LIVE` / `ENDED` / `CANCELED`) |
| `bidCount` | `Long` | 해당 경매에 등록된 총 입찰 수 |

---

### Failure ❌

### 404 Not Found - 존재하지 않는 경매

요청한 `auctionId`에 해당하는 경매가 존재하지 않는 경우 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40402,
    "message": "존재하지 않는 경매입니다. auctionId: 999"
  }
}
```

공통 응답 DTO를 적용하여 모든 응답을 `success`, `data`, `error` 형식으로 반환합니다.

---

# API 상세 설명

`GET /api/auctions/{auctionId}/bids`

특정 경매의 **입찰 이력을 페이지 단위로 조회하는 API**입니다.

입찰 이력은 정렬 순서를 지정하여 조회할 수 있으며, 기본 정렬은 최신 입찰이 먼저 조회되는 `latest`입니다.

---

## Request ✔️

### Path Variable

| 이름 | 타입 | 설명 |
| --- | --- | --- |
| `auctionId` | `Long` | 입찰 이력을 조회할 경매 ID |

### Request Parameter

| 이름 | 타입 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `page` | `int` | `0` | 조회할 페이지 번호. `0`부터 시작 |
| `size` | `int` | `20` | 한 페이지에 포함할 입찰 이력 수 |
| `order` | `String` | `latest` | 정렬 순서. `latest`(최신순) / `oldest`(오래된순) |

### Request Example

```http
GET /api/auctions/1/bids?page=0&size=20&order=latest
```

`order=latest`인 경우 최근 입찰부터 조회되며, `order=oldest`인 경우 오래된 입찰부터 조회됩니다. `oldest` 외의 값이 전달되면 오류 없이 `latest`로 처리됩니다.

---

## Response ✔️

### Success ✅

### 200 OK - 입찰 이력 조회 성공

요청한 경매의 입찰 이력을 지정한 페이지와 정렬 조건에 따라 반환합니다.

```json
{
  "success": true,
  "data": {
    "bids": [
      {
        "bidId": 6,
        "bidderId": 2,
        "amount": 26000,
        "bidType": "MANUAL",
        "bidAt": "2026-08-20T14:35:12"
      },
      {
        "bidId": 5,
        "bidderId": 3,
        "amount": 25000,
        "bidType": "AUTO",
        "bidAt": "2026-08-20T14:30:05"
      }
    ],
    "page": 0,
    "size": 20,
    "hasNext": false
  },
  "error": null
}
```

---

### 응답 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `bids` | 객체 배열 | 해당 페이지에 포함된 입찰 이력 목록 |
| `page` | `int` | 현재 조회한 페이지 번호 |
| `size` | `int` | 요청한 페이지 크기 |
| `hasNext` | `boolean` | 다음 페이지가 존재하는지 여부 |

### `bids` 객체

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `bidId` | `Long` | 입찰 ID |
| `bidderId` | `Long` | 입찰을 수행한 사용자 ID |
| `amount` | `Long` | 해당 입찰 금액 |
| `bidType` | `String` | 입찰 유형. `MANUAL`(직접 입찰) / `AUTO`(자동 입찰). 자동 입찰 기능은 아직 구현 전이라 현재는 `MANUAL`만 반환됩니다 |
| `bidAt` | `LocalDateTime` | 입찰이 등록된 시각 |

`hasNext`가 `true`인 경우 다음 페이지가 존재하므로 `page` 값을 1 증가시켜 추가 입찰 이력을 조회할 수 있습니다.

예를 들어 현재 응답이 `page: 0`, `hasNext: true`인 경우 다음 요청은 다음과 같습니다.

```http
GET /api/auctions/1/bids?page=1&size=20&order=latest
```

---

### Failure ❌

### 404 Not Found - 존재하지 않는 경매

요청한 `auctionId`에 해당하는 경매가 존재하지 않는 경우 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40402,
    "message": "존재하지 않는 경매입니다. auctionId: 999"
  }
}
```

공통 응답 DTO를 적용하여 모든 응답을 `success`, `data`, `error` 형식으로 반환합니다.

---

# API 상세 설명

`POST /api/auctions/{auctionId}/bids`

진행 중인 경매에 **직접 입찰을 등록하는 API**입니다.

입찰 금액은 반드시 아래 최소 입찰 금액 이상이어야 합니다.

```text
최소 입찰 금액 = currentPrice + bidIncrement
```

입찰에 성공하면 새로운 입찰 이력이 생성되고, 해당 경매의 `currentPrice`와 `currentWinnerId`가 갱신됩니다.

이 문서에서 **`X-User-Id` 헤더가 필요한 API**이며, 해당 헤더를 통해 입찰자를 식별합니다.

현재 입찰 가능 여부는 경매의 `status` 값을 기준으로 판단합니다. 경매 상태를 `startAt`, `endAt` 시각에 따라 자동으로 변경하는 기능은 아직 구현되어 있지 않으므로 다음과 같이 동작합니다.

- `startAt`이 지났더라도 `status`가 `SCHEDULED`이면 입찰할 수 없습니다.
- `endAt`이 지났더라도 `status`가 `LIVE`이면 현재 구현상 입찰이 처리됩니다.

---

## Request ✔️

### Path Variable

| 이름 | 타입 | 설명 |
| --- | --- | --- |
| `auctionId` | `Long` | 입찰할 경매 ID |

### Request Header

```http
Content-Type: application/json
X-User-Id: 2
```

`X-User-Id`는 현재 입찰자를 식별하기 위해 사용하는 mock 인증 헤더입니다.

전달한 값은 `users` 테이블에 실제로 존재하는 사용자 ID여야 합니다.

### Request Body

```json
{
  "amount": 27000
}
```

### Request Body 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `amount` | `Long` | 사용자가 제출할 입찰 금액. 필수값이며 `0`보다 커야 함 |

또한 실제 입찰이 처리되기 위해서는 다음 조건을 만족해야 합니다.

```text
amount >= currentPrice + bidIncrement
```

예를 들어 `currentPrice`가 `26,000원`, `bidIncrement`가 `1,000원`이라면 최소 `27,000원` 이상을 입찰해야 합니다.

입찰이 아직 한 건도 없는 경우에는 `currentPrice = startPrice`이므로, **첫 입찰 역시 `startPrice + bidIncrement` 이상**이어야 합니다.

---

## Response ✔️

### Success ✅

### 201 Created - 입찰 성공

입찰이 정상적으로 등록되면 새로 생성된 입찰 정보와 입찰 반영 후 경매 상태를 반환합니다.

```json
{
  "success": true,
  "data": {
    "bidId": 7,
    "auctionId": 1,
    "submittedAmount": 27000,
    "currentPrice": 27000,
    "currentWinnerId": 2,
    "bidAt": "2026-08-20T14:40:00"
  },
  "error": null
}
```

### 응답 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `bidId` | `Long` | 새로 생성된 입찰 ID |
| `auctionId` | `Long` | 입찰이 등록된 경매 ID |
| `submittedAmount` | `Long` | 사용자가 실제 제출한 입찰 금액 |
| `currentPrice` | `Long` | 해당 입찰이 반영된 이후의 경매 현재가 |
| `currentWinnerId` | `Long` | 입찰 반영 이후 현재 최고입찰자 ID |
| `bidAt` | `LocalDateTime` | 입찰이 등록된 시각 |

---

### Failure ❌

### 400 Bad Request - 입찰 금액 누락 또는 형식 오류

`amount`가 누락되거나 유효성 검증 조건을 만족하지 않는 경우 반환됩니다.

현재 Bean Validation의 기본 메시지를 그대로 반환합니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40001,
    "message": "널이어서는 안됩니다"
  }
}
```

---

### 401 Unauthorized - 인증 헤더 누락 또는 사용자 확인 실패

`X-User-Id` 헤더가 없거나, 올바른 사용자 ID로 해석할 수 없거나, 해당 사용자가 존재하지 않는 경우 반환됩니다.

#### `X-User-Id` 헤더 누락

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40101,
    "message": "X-User-Id 헤더가 없습니다."
  }
}
```

다음 경우에도 동일한 오류 코드 `40101`이 사용됩니다.

- 잘못된 헤더 형식 — `X-User-Id 헤더 형식이 올바르지 않습니다: abc`
- 존재하지 않는 사용자 — `존재하지 않는 사용자입니다: 999`

---

### 403 Forbidden - 판매자 본인 입찰

경매 판매자가 자신의 경매에 직접 입찰하려는 경우 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40301,
    "message": "판매자는 자신의 경매에 입찰할 수 없습니다. auctionId: 1"
  }
}
```

---

### 403 Forbidden - 입찰 제한 기간 중인 사용자

사용자의 `bidRestrictedUntil` 시각이 아직 지나지 않아 입찰 제한 상태인 경우 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40302,
    "message": "입찰 제한 기간 중인 사용자입니다. userId: 2"
  }
}
```

---

### 404 Not Found - 존재하지 않는 경매

요청한 `auctionId`에 해당하는 경매가 존재하지 않는 경우 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40402,
    "message": "존재하지 않는 경매입니다. auctionId: 999"
  }
}
```

---

### 409 Conflict - 이미 현재 최고입찰자

현재 최고입찰자인 사용자가 같은 경매에 추가로 직접 입찰하려는 경우 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40901,
    "message": "이미 현재 최고입찰자입니다. auctionId: 1"
  }
}
```

---

### 409 Conflict - 아직 시작되지 않은 경매

경매의 현재 `status`가 `SCHEDULED`인 경우 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40902,
    "message": "아직 시작되지 않은 경매입니다. auctionId: 1"
  }
}
```

현재 구현에서는 실제 현재 시각과 `startAt`을 직접 비교하여 입찰 가능 여부를 판단하는 것이 아니라, **경매의 `status` 값을 기준으로 판단합니다.**

---

### 409 Conflict - 종료되었거나 취소된 경매

경매 상태가 `ENDED` 또는 `CANCELED`인 경우 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40903,
    "message": "이미 종료되었거나 취소된 경매입니다. auctionId: 1, 상태: ENDED"
  }
}
```

---

### 409 Conflict - 최소 입찰 금액 미만

사용자가 제출한 `amount`가 `currentPrice + bidIncrement`보다 작은 경우 반환됩니다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": 40904,
    "message": "입찰 금액은 27000원 이상이어야 합니다. 입력값: 26500"
  }
}
```

최소 입찰 금액은 다음과 같이 계산됩니다.

```text
minimumBidAmount = currentPrice + bidIncrement
```

입찰이 없는 초기 상태에서도 `currentPrice`는 `startPrice`와 같으므로, **첫 입찰 역시 `startPrice`가 아니라 `startPrice + bidIncrement` 이상이어야 합니다.**

---

공통 응답 DTO를 적용하여 모든 응답을 `success`, `data`, `error` 형식으로 반환합니다.

입찰 성공 시에는 `201 Created`를 반환하며, 실패한 경우 원인에 따라 다음과 같이 구분됩니다.

- 요청값 검증 실패 → `400 Bad Request`
- 사용자 인증/식별 실패 → `401 Unauthorized`
- 입찰 자격 제한 → `403 Forbidden`
- 경매 미존재 → `404 Not Found`
- 경매 상태 또는 입찰 조건 충돌 → `409 Conflict`


---

# API 상세 설명

`GET /api/recommendations/auctions`

사용자의 행동 로그(조회·찜·입찰)를 기반으로 취향에 맞는 진행 중 경매를 추천하는 API입니다.

### `GET /api/auctions/{auctionId}/similar`와의 차이

이름이 비슷해 헷갈리기 쉬운데 **기준이 되는 축이 다릅니다.** 둘 다 필요하며 대체 관계가 아닙니다.

| | `/recommendations/auctions` | `/auctions/{id}/similar` |
| --- | --- | --- |
| 기준 | **사용자** (X-User-Id) | **경매 하나** (auctionId) |
| 방식 | 행동 로그 → 취향 벡터 → 코사인 유사도 | 같은 브랜드 |
| 정렬 | 유사도 높은 순 | 마감 임박 순 |
| 쓰는 화면 | 홈 "회원님을 위한 추천" | 상세 페이지 "비슷한 상품" |

상세 페이지에서 `/recommendations`를 쓰면 "이 신발과 비슷한 것"을 물었는데
"내가 예전에 본 것"이 나오므로 화면에 맞는 쪽을 호출해야 합니다.

추천은 두 가지 경로로 동작하며, 어느 경로로 만들어진 결과인지 응답의 `personalized` 필드로 구분합니다.

| `personalized` | 동작 | 조건 |
| --- | --- | --- |
| `true` | **개인화 추천.** 최근 행동 로그로 취향 벡터를 만들고, 각 경매 상품 벡터와의 코사인 유사도 순으로 정렬합니다. | `X-User-Id`가 있고, 해당 유저의 행동 로그가 **3건 이상**이며, 그 행동에 해당하는 상품 벡터가 존재할 때 |
| `false` | **Cold Start Fallback.** 마감 임박 경매와 인기(입찰 많은) 경매를 번갈아 섞어 내려줍니다. | 위 조건을 만족하지 못하는 모든 경우 (비로그인, 신규 가입자, 벡터 미생성 등) |

Fallback도 정상 응답(`200 OK`)입니다. 추천할 데이터가 없다는 이유로 에러를 반환하지 않습니다.

## 취향 벡터 계산 방식

행동 종류마다 취향을 드러내는 강도가 다르므로 가중치를 다르게 둡니다.

| 행동 | 가중치 | 비고 |
| --- | --- | --- |
| `BID` (입찰) | 3.0 | 돈이 걸린 행동이라 취향을 가장 강하게 드러냅니다 |
| `LIKE` (찜) | 2.0 | `POST /api/auctions/{id}/likes` 호출 시 자동 기록됩니다. 찜을 해제하면 기록도 지워집니다 |
| `VIEW` (조회) | 1.0 | 가장 흔하지만 신호는 약합니다 |
| `DWELL` (체류) | 1.0 | 프론트에서 전송해야 하며 현재는 수집하지 않습니다 |

최근 **100건**의 행동만 사용하며, 같은 상품을 여러 번 조회하면 가중치가 누적됩니다.

## Request ✔️

### Request Header

| 헤더 | 필수 | 설명 |
| --- | --- | --- |
| `X-User-Id` | ❌ | 추천 대상 사용자 ID. **없으면 Fallback 결과를 반환합니다.** |

### Query Parameter

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| `limit` | `int` | ❌ | `10` | 추천 개수. 최대 `50`까지이며, 초과 입력 시 50으로 잘립니다. |

```
GET /api/recommendations/auctions?limit=10
X-User-Id: 3
```

## Response ✔️

### Success ✅

### 200 OK - 개인화 추천 (`personalized: true`)

```json
{
  "success": true,
  "data": {
    "personalized": true,
    "reason": "최근 관심 상품과 비슷한 경매를 추천합니다.",
    "items": [
      {
        "auctionId": 1,
        "productId": 5,
        "brand": "Nike",
        "model": "Dunk Low",
        "colorway": "Panda",
        "sizeKr": 270,
        "currentPrice": 100000,
        "endAt": "2026-08-21T15:30:00",
        "similarity": 0.9774
      }
    ]
  },
  "error": null
}
```

---

### 200 OK - Cold Start Fallback (`personalized: false`)

```json
{
  "success": true,
  "data": {
    "personalized": false,
    "reason": "아직 취향을 파악할 정보가 부족해 마감 임박·인기 경매를 보여드립니다.",
    "items": [
      {
        "auctionId": 1,
        "productId": 5,
        "brand": "Nike",
        "model": "Dunk Low",
        "colorway": "Panda",
        "sizeKr": 270,
        "currentPrice": 100000,
        "endAt": "2026-08-21T15:30:00",
        "similarity": null
      }
    ]
  },
  "error": null
}
```

### 응답 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `personalized` | `boolean` | 개인화 추천 여부. `false`면 Fallback 결과입니다. |
| `reason` | `string` | 추천 근거 문구. 프론트에서 그대로 노출할 수 있습니다. |
| `items[].auctionId` | `number` | 경매 ID |
| `items[].productId` | `number` | 상품 ID |
| `items[].brand` | `string` | 브랜드 |
| `items[].model` | `string` | 모델명 |
| `items[].colorway` | `string` | 컬러웨이 |
| `items[].sizeKr` | `number` | 사이즈(mm) |
| `items[].currentPrice` | `number` | 현재 입찰가 |
| `items[].endAt` | `string` | 경매 종료 시각 |
| `items[].similarity` | `number \| null` | 취향 벡터와의 코사인 유사도(0~1). **Fallback이거나, 개인화 목록 뒤쪽에 붙는 순위 없는 항목이면 `null`입니다.** |

### 프론트 적용 시 유의사항

- `personalized` 값에 따라 섹션 문구를 다르게 노출해 주세요. `true`면 "회원님을 위한 추천", `false`면 "지금 인기 있는 경매"처럼 표현하는 것을 권장합니다.
- `similarity`는 `null`일 수 있으므로 화면에 표시한다면 항상 null 체크가 필요합니다. `personalized: true`여도 목록 **뒤쪽에는 `similarity: null`인 항목이 붙을 수 있습니다** — 벡터가 아직 만들어지지 않아 순위를 매길 수 없는 경매를 버리는 대신, 유사도 순 뒤에 마감 임박 순으로 이어 붙입니다.
- 추천 대상은 **진행 중(`SCHEDULED`, `LIVE`) 경매**뿐입니다. 종료된 경매는 포함되지 않습니다.
- 행동 로그는 아래 API 호출 시 `X-User-Id` 헤더가 있으면 **자동으로 기록**됩니다. 프론트에서 별도 로그 전송 API를 호출할 필요는 없습니다.
  - `GET /api/auctions/{auctionId}` → `VIEW`
  - `POST /api/auctions/{auctionId}/likes` → `LIKE` / `DELETE`는 기록 삭제
  - `POST /api/auctions/{auctionId}/bids` → `BID`

### Failure ❌

추천 API는 데이터가 부족해도 Fallback으로 응답하므로 고유한 에러 코드가 없습니다. 서버 내부 오류(`50001`)만 공통 형식으로 반환됩니다.
