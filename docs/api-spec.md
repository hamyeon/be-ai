# Vintic 백엔드 API 명세서

프론트엔드 연동용 확정 API 명세서입니다. 아래 API들은 요청/응답 구조가 확정되어 더 이상 변경되지 않습니다.

## 배포 서버 정보

Base URL : http://44.193.0.36:8080

## 공통 사항

모든 API는 공통 응답 래퍼(`success` / `data` / `error`) 형식으로 응답합니다. 성공 시 `data`에 결과가 담기고 `error`는 `null`, 실패 시 `data`는 `null`이고 `error`에 코드와 메시지가 담깁니다.

### 인증 (`X-User-Id`)

로그인한 사용자를 알아야 하는 API에만 `X-User-Id` 헤더가 필요합니다. 실제 로그인 연동 전까지 사용하는 mock 인증이며, 헤더 값은 `users` 테이블에 존재하는 사용자 ID여야 합니다.

| API | 헤더 필요 여부 |
| --- | --- |
| `POST /api/auctions/{auctionId}/bids` (입찰하기) | **필요** — 누가 입찰하는지 식별해야 함 |
| 그 외 이 문서의 모든 API | 불필요 — 헤더 없이 호출 |

헤더가 없거나, 숫자가 아니거나, 존재하지 않는 사용자 ID인 경우 `401 Unauthorized`(`40101`)를 반환합니다.

### 에러 코드 정리

| HTTP 상태 | code | 상황 |
| --- | --- | --- |
| 400 | 40001 | 요청 필수값 누락 등 검증 실패 |
| 400 | 40002 | 이미지 파일 없음 |
| 400 | 40003 | 잘못된 분석 상태에서의 요청 |
| 401 | 40101 | `X-User-Id` 헤더 누락 / 형식 오류 / 존재하지 않는 사용자 |
| 403 | 40301 | 판매자 본인 입찰 시도 |
| 403 | 40302 | 입찰 제한(패널티) 기간 중인 사용자의 입찰 시도 |
| 404 | 40401 | 존재하지 않는 분석 세션 |
| 404 | 40402 | 존재하지 않는 경매 |
| 404 | 40403 | 존재하지 않는 사용자 |
| 409 | 40901 | 이미 현재 최고입찰자인 사용자의 추가 입찰 시도 |
| 409 | 40902 | 아직 시작되지 않은 경매에 대한 입찰 시도 |
| 409 | 40903 | 종료/취소된 경매에 대한 입찰 시도 |
| 409 | 40904 | 최소 입찰 금액 미만 입찰 시도 |
| 500 | 50001 | 서버 내부 오류 |
| 500 | 50002 | S3 이미지 업로드 실패 |
| 500 | 50003 | AI(OpenAI) 통신 오류 |
| 500 | 50004 | 분석 작업 큐 적재 실패 |

---

# API 상세 설명

`POST /api/products/analyze`

신발 이미지들을 업로드하면 S3에 저장하고 AI Vision 분석 작업을 비동기 큐에 적재하는 API입니다.

이 API는 분석 결과를 바로 주지 않습니다. **202 Accepted**와 함께 `analysisId`(taskId)를 즉시 반환하고, 실제 Vision 분석은 백그라운드에서 진행됩니다. 프론트는 반환받은 `analysisId`로 `GET /api/products/analyze/{taskId}`를 폴링해서 분석 결과를 확인합니다.

## Request ✔️

Request Header

```json
Content-Type: multipart/form-data
```

Request Body (multipart/form-data)

| 파트명 | 타입 | 설명 |
| --- | --- | --- |
| images | file (여러 개) | 분석할 신발 이미지 파일 목록 |

```
images: (신발 이미지 파일 1)
images: (신발 이미지 파일 2)
images: (신발 이미지 파일 3)
```

## Response ✔️

### Success ✅

Response Body

**202 Accepted**

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

`analysisId`를 저장해 두었다가 폴링 API의 `{taskId}`로 사용합니다.

### Failure ❌

Response Body

**400 Bad Request** - 이미지 파일 없음

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

**500 Internal Server Error** - S3 업로드 실패

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

**500 Internal Server Error** - 분석 작업 큐 적재 실패

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

---

# API 상세 설명

`GET /api/products/analyze/{taskId}`

`POST /api/products/analyze`에서 받은 `analysisId`(taskId)로 분석 진행 상태와 결과를 조회하는 API입니다.

분석이 끝날 때까지(상태가 `AWAITING_USER_CONFIRMATION` 또는 `*_FAILED`가 될 때까지) 주기적으로 폴링합니다.

상태(status) 흐름 :

```
CREATED → IMAGE_UPLOADED → QUEUED → VISION_PROCESSING → AWAITING_USER_CONFIRMATION
        → PRICING_PROCESSING → COMPLETED
```

| status | 의미 |
| --- | --- |
| CREATED | 분석 세션 생성됨 |
| IMAGE_UPLOADED | S3 이미지 업로드 완료 |
| QUEUED | 분석 작업 큐 적재 완료 (202 응답 시점의 상태) |
| VISION_PROCESSING | AI Vision 분석 진행 중 |
| AWAITING_USER_CONFIRMATION | **Vision 분석 완료.** 사용자가 결과를 확인/수정하는 화면을 띄우는 시점 |
| PRICING_PROCESSING | 가격 계산 진행 중 |
| COMPLETED | 가격 계산까지 완료 |
| IMAGE_UPLOAD_FAILED / QUEUE_FAILED / VISION_FAILED / PRICING_FAILED | 각 단계에서 실패 (`failureStage`, `failureMessage`가 채워짐) |

## Request ✔️

Path Variable

| 이름 | 타입 | 설명 |
| --- | --- | --- |
| taskId | Long | 분석 요청 시 받은 `analysisId` |

## Response ✔️

### Success ✅

Response Body

**200 OK** - 분석 진행 중 (VISION_PROCESSING)

아직 Vision 분석 결과가 없으므로 상품 정보 필드는 `null`, 리스트 필드는 빈 배열로 내려갑니다.

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

**200 OK** - 분석 완료 (AWAITING_USER_CONFIRMATION)

사용자 확인/수정 화면을 그리는 데 필요한 정보가 모두 내려갑니다. Vision이 근거를 찾지 못한 항목(예: 사이즈 라벨이 안 보임)은 `null`로 내려가고, 대신 `warnings`와 `needsUserConfirmation`으로 사용자 확인이 필요함을 알려줍니다.

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

응답 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| analysisId | Long | 분석 세션 ID |
| status | String | 분석 상태 (위 상태 표 참고) |
| imageUrls | String[] | S3에 업로드된 이미지 URL 목록 |
| brand / modelName / color | String | AI가 인식한 브랜드 / 모델명 / 컬러웨이 (근거 없으면 null) |
| size | Integer | 한국 사이즈 (mm 단위, 예: 270) |
| boxIncluded | Boolean | 박스 포함 여부 |
| conditionDescription | String | 상태 요약 설명 (한국어) |
| conditionGrade | String | 상태 등급: `DS`(새상품) / `A` / `B` / `C` / `UNKNOWN` |
| defects | 객체 배열 | 사진에서 발견된 하자 목록 (type / location / severity / description) |
| candidates | 객체 배열 | 모델 인식 후보 목록 (brand / modelName / color / confidence) |
| confidence | Double | 모델 인식 신뢰도 (0.0 ~ 1.0) |
| needsUserConfirmation | Boolean | 사용자 확인이 필요한 항목이 있는지 여부 |
| warnings | String[] | 사용자에게 확인을 요청해야 하는 항목의 안내 문구 |
| failureStage | String | 실패 단계: `IMAGE_UPLOAD` / `QUEUE` / `VISION` / `PRICING` (성공 시 null) |
| failureMessage | String | 실패 상세 메시지 (성공 시 null) |

**200 OK** - 분석 실패 (VISION_FAILED)

실패도 200으로 내려가며, `status`와 `failureStage` / `failureMessage`로 구분합니다.

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

### Failure ❌

Response Body

**404 Not Found** - 존재하지 않는 분석 세션

```json
{
	"success": false,
	"data": null,
	"error": {
		"code": 40401,
		"message": "분석 세션을 찾을 수 없습니다. analysisId: 999"
	}
}
```

---

# API 상세 설명

`POST /api/products/calculate-price`

사용자가 AI 분석 결과를 확인/수정한 최종 값(브랜드, 모델명, 색상, 사이즈, 상품 상태 등급, 구성품 상태)을 요청하면 KREAM/eBay 시세 데이터를 조회하여 추천 판매가를 계산하는 API입니다.

`analysisId`로 분석 세션과 연결되며, 계산 성공 시 세션 상태가 `AWAITING_USER_CONFIRMATION` → `PRICING_PROCESSING` → `COMPLETED`로 전환됩니다.

요청 필드 값 범위 :

| 필드 | 허용 값 |
| --- | --- |
| conditionGrade | `DS`(새상품) / `S` / `A` / `B` / `C` |
| componentStatus | `FULL`(모두 포함) / `PARTIAL`(일부 포함) / `NONE`(없음) |

## Request ✔️

Request Header

```json
Content-Type: application/json
```

Request Body

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

## Response ✔️

### Success ✅

Response Body

**200 OK** - 시세 데이터 조회 성공

```json
{
	"success": true,
	"data": {
		"recommendedPrice": 24000,
		"baseMarketPrice": 120984,
		"kreamAveragePrice": 114000,
		"ebayAveragePrice": 137280,
		"minRecommendedPrice": 23000,
		"maxRecommendedPrice": 25000,
		"priceRange": "23,000원 ~ 25,000원",
		"reason": "KREAM 유사 거래 1건의 평균가 114,000원과 eBay 유사 거래 50건의 평균가 137,280원을 각각 70%, 30% 비율로 반영해 기준 시세 120,984원을 계산했습니다. 상품 상태는 C(하자 있음)로 판단하여 20% 반영률을 적용했습니다. 구성품이 모두 포함되어 있어 100% 반영률을 적용했습니다. 이를 바탕으로 최종 추천가는 24,000원으로 산정했으며, 판매 권장 범위는 23,000원 ~ 25,000원입니다. 추천가는 KREAM 평균가 대비 약 79% 낮은 수준입니다.",
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

**200 OK** - 시세 데이터 없음

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

### Failure ❌

Response Body

**400 Bad Request** - 필수값 누락

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

필수값별 메시지 : `분석 세션 ID는 필수입니다.` / `브랜드는 필수입니다.` / `모델명은 필수입니다.` / `컬러웨이는 필수입니다.` / `한국 사이즈는 필수입니다.` / `상품 상태 등급은 필수입니다.` / `구성품 상태는 필수입니다.`

**400 Bad Request** - 잘못된 분석 상태

Vision 분석이 아직 끝나지 않은(= `AWAITING_USER_CONFIRMATION`이 아닌) 세션으로 요청한 경우입니다.

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

**404 Not Found** - 존재하지 않는 분석 세션

```json
{
	"success": false,
	"data": null,
	"error": {
		"code": 40401,
		"message": "분석 세션을 찾을 수 없습니다. analysisId: 999"
	}
}
```

---

# API 상세 설명

`GET /api/auctions/{auctionId}`

경매 ID로 경매 상세 정보(시작가, 현재가, 입찰 단위, 경매 기간, 상태, 총 입찰 수 등)를 조회하는 API입니다.

## Request ✔️

Path Variable

| 이름 | 타입 | 설명 |
| --- | --- | --- |
| auctionId | Long | 조회할 경매 ID |

## Response ✔️

### Success ✅

Response Body

**200 OK**

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

응답 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| id | Long | 경매 ID |
| productId | Long | 경매 대상 상품 ID |
| sellerId | Long | 판매자 ID |
| currentWinnerId | Long | 현재 최고입찰자 ID (입찰이 없으면 null) |
| startPrice | Long | 경매 시작가 |
| currentPrice | Long | 현재가 |
| bidIncrement | Long | 입찰 단위 (다음 최소 입찰가 = 현재가 + 입찰 단위) |
| startAt / endAt | LocalDateTime | 경매 시작/종료 시각 |
| status | String | 경매 상태: `SCHEDULED`(시작 전) / `LIVE`(진행 중) / `ENDED`(종료) / `CANCELED`(취소) |
| bidCount | Long | 총 입찰 수 |

### Failure ❌

Response Body

**404 Not Found** - 존재하지 않는 경매

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

# API 상세 설명

`GET /api/auctions/{auctionId}/bids`

경매의 입찰 이력을 페이지 단위로 조회하는 API입니다.

## Request ✔️

Path Variable

| 이름 | 타입 | 설명 |
| --- | --- | --- |
| auctionId | Long | 조회할 경매 ID |

Request Parameter

| 이름 | 타입 | 기본값 | 설명 |
| --- | --- | --- | --- |
| page | int | 0 | 페이지 번호 (0부터 시작) |
| size | int | 20 | 페이지 크기 |
| order | String | latest | 정렬 순서: `latest`(최신순) / `oldest`(오래된순) |

```
GET /api/auctions/1/bids?page=0&size=20&order=latest
```

## Response ✔️

### Success ✅

Response Body

**200 OK**

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

응답 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| bids | 객체 배열 | 입찰 목록 |
| bids[].bidId | Long | 입찰 ID |
| bids[].bidderId | Long | 입찰자 ID |
| bids[].amount | Long | 입찰 금액 |
| bids[].bidType | String | 입찰 유형: `MANUAL`(직접 입찰) / `AUTO`(자동 입찰) |
| bids[].bidAt | LocalDateTime | 입찰 시각 |
| page / size | int | 요청한 페이지 번호 / 크기 |
| hasNext | boolean | 다음 페이지 존재 여부 |

### Failure ❌

Response Body

**404 Not Found** - 존재하지 않는 경매

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

# API 상세 설명

`POST /api/auctions/{auctionId}/bids`

진행 중인 경매에 직접 입찰하는 API입니다. 입찰 금액은 `현재가 + 입찰 단위(bidIncrement)` 이상이어야 합니다.

## Request ✔️

Path Variable

| 이름 | 타입 | 설명 |
| --- | --- | --- |
| auctionId | Long | 입찰할 경매 ID |

Request Header

```json
Content-Type: application/json
X-User-Id: 2
```

`X-User-Id`는 입찰자를 식별하는 mock 인증 헤더입니다. `users` 테이블에 존재하는 사용자 ID여야 합니다.

Request Body

```json
{
	"amount": 27000
}
```

## Response ✔️

### Success ✅

Response Body

**201 Created**

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

### Failure ❌

Response Body

**400 Bad Request** - 입찰 금액 누락

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

**401 Unauthorized** - 인증 헤더 누락 / 존재하지 않는 사용자

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

`X-User-Id 헤더 형식이 올바르지 않습니다: abc`, `존재하지 않는 사용자입니다: 999`도 같은 코드로 반환됩니다.

**403 Forbidden** - 판매자 본인 입찰

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

**403 Forbidden** - 입찰 제한 기간 중인 사용자

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

**404 Not Found** - 존재하지 않는 경매 / 사용자

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

**409 Conflict** - 이미 최고입찰자

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

**409 Conflict** - 아직 시작되지 않은 경매

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

**409 Conflict** - 종료/취소된 경매

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

**409 Conflict** - 최소 입찰 금액 미만

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
