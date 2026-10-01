# AI 인프라 기술 의사결정 및 서비스 경계

`feature/#14-ai-infra-design` — Buyer Agent 재정의, Vector DB 후보 결정, 프롬프트 관리 방식을 정리한 문서.
실제 코드 변경은 Vision 프롬프트 외부화(`PromptTemplateLoader`)만 포함하며, 나머지는 결정 사항 기록임.

## 1. Buyer Agent 재정의

기존에는 "Vision/Pricing/Buyer Agent"를 같은 층위의 AI 에이전트로 묶어서 설계하려 했으나,
실제 Buyer 흐름을 확인한 결과 다음과 같이 일반적인 거래/경매 도메인에 가깝다는 것을 확인함.

```
상품 조회 → 구매 또는 경매 참여 → 입찰 → 낙찰 → 거래
```

이 흐름에는 AI가 개입하지 않으므로 "Buyer Agent"라는 이름 자체를 사용하지 않는다.
대신 아래처럼 일반 거래 도메인과 향후 AI 추천 기능을 분리한다.

### 일반 거래 도메인 (AI 아님)

| 패키지 | 책임 |
|---|---|
| `buyer/` | 구매자 상품 조회, 구매 요청 |
| `auction/` | 경매 등록, 입찰, 낙찰 처리 |
| `transaction/` | 거래 상태 관리, 결제 및 거래 완료 처리 |

### 향후 AI 기능

| 패키지 | 책임 |
|---|---|
| `ai/recommendation/` (미구현) | 구매자 취향 기반 상품 추천, 유사 상품 추천, 자연어 조건 기반 상품 탐색 — `BuyerRecommendationService` 인터페이스로 향후 설계 |

**이번 이슈 범위**: 위 경계를 문서화하는 것까지만 진행. `buyer/`, `auction/`, `transaction/`, `ai/recommendation/` 어느 것도 실제로 구현하지 않음.

---

## 2. Vector DB 후보 결정 (pgvector vs Redis Vector)

### 현재 상태

- 메인 DB: MySQL (RDS), `ddl-auto: update`로 스키마 관리
- 크롤링 데이터 약 6천 건 + 카탈로그 15개 모델
- 가격 계산(`PriceCalculationService`)은 브랜드/모델명 문자열 매칭 + KREAM/eBay 시세 조회 + 상태·구성품 보정으로 동작 — **벡터 검색을 쓰는 코드는 현재 어디에도 없음**

### 벡터 검색 예상 사용처 (실제 구현 전 정의만)

- 크롤링 상품 ↔ 기준 카탈로그 상품 매칭 (표기가 다른 동일 모델 후보 탐색)
- 유사 상품 검색
- 상품 이미지/설명 기반 유사도 검색
- 구매자 취향 기반 상품 추천, 자연어 상품 검색

벡터 DB는 가격을 직접 계산하지 않고, **가격 계산에 쓸 유사 상품 후보를 찾는 용도**로만 쓴다.

```
벡터 검색 → 유사 상품 후보 조회 → 실제 시세 데이터 확인 → Pricing 계산
```

### 비교

| | pgvector | Redis Vector |
|---|---|---|
| 장점 | 관계형 데이터와 벡터를 한 DB에서 관리, SQL 필터링과 결합 쉬움, 별도 전문 Vector DB 불필요 | 기존 MySQL 구조 유지, 캐시 기능과 함께 활용 가능, 실시간 추천/유사 검색에 적합, 향후 확장성 좋음 |
| 단점 | 메인 DB가 MySQL이라 PostgreSQL을 추가 운영하거나 이전 필요, 두 DB 동시 운영 부담 | MySQL-Redis 간 동기화 필요, 원본/임베딩 데이터가 다른 저장소, Redis 운영·장애 대응 추가 필요 |

### 최종 결정

**향후 우선 후보: Redis Vector.**

- 메인 DB가 MySQL이라 pgvector는 PostgreSQL 추가 운영이 필요해 인프라 부담이 큼
- Redis는 기존 MySQL과 병행하기 쉽고, 캐시·유사 검색·추천에 함께 활용 가능

**현재(MVP) 단계에서는 Vector DB 도입 자체를 보류한다.** 실제 사용처가 코드에 없고, 데이터 규모(6천여 건)에서는 문자열 매칭이나 인메모리 방식으로도 충분하기 때문.

### 도입 검토 조건 (아래 중 하나라도 충족 시)

- 문자열 매칭 방식의 카탈로그 매칭 정확도가 부족해질 때
- 유사 상품 검색 기능을 실제로 구현할 때
- 구매자 취향 기반 추천 기능을 구현할 때
- 자연어 상품 검색 기능을 구현할 때
- 데이터 증가로 인메모리/일반 DB 검색 성능이 부족해질 때

**이번 이슈 범위**: Redis 설치, 임베딩 생성, 인덱스 생성, 실제 유사도 검색 — 전부 미구현.

---

## 3. 프롬프트 템플릿 관리 방식

### 문제

기존에는 Vision 프롬프트가 `ai/prompt/ProductAnalysisPrompt.java`에 Java 문자열로 하드코딩되어 있었음.

- 프롬프트 변경 시 Java 코드도 같이 수정해야 함
- 버전 구분이 안 됨 (어떤 프롬프트로 분석했는지 추적 불가)
- 프롬프트 내용과 실행 코드의 책임이 섞임
- 프롬프트 비교/롤백이 어려움

### MVP 결정: 파일 기반 관리

```
src/main/resources/
└─ prompts/
   └─ vision/
      └─ product-analysis-system-v1.md
```

- **저장**: `src/main/resources/prompts/{category}/{name}-{version}.md`
- **버전 관리**: 파일명에 `-v1`, `-v2` 명시 (내용이 아닌 파일명으로 구분)
- **변경 이력**: Git
- **변경 검토**: PR 코드 리뷰
- **배포 방식**: 프롬프트 변경 시 재빌드/재배포 (런타임 실시간 수정 없음)

`OpenAiVisionAnalysisService`가 기동 시 `PromptTemplateLoader`로 프롬프트를 한 번 읽어 캐시해두고, 매 요청마다 재사용한다.

```
OpenAiVisionAnalysisService
        ↓ (기동 시 1회)
PromptTemplateLoader.load("vision", "product-analysis-system", "v1")
        ↓
resources/prompts/vision/product-analysis-system-v1.md
```

파일이 없으면 `PromptTemplateNotFoundException`을 던진다. 프롬프트 로딩이 빈 생성자에서 즉시 일어나므로,
파일이 없으면 요청 처리 중이 아니라 **애플리케이션 기동 시점에 바로 실패**한다 (fail-fast).

**현재 user 메시지에는 고정 텍스트가 없음** (이미지 목록만 동적으로 전달) — 그래서 `product-analysis-user-v1.md`는 만들지 않았다.
추후 이미지와 함께 전달할 고정 안내문이나 변수 템플릿이 실제로 생기면 그때 추가한다.

### 향후 확장 (미구현)

운영 중 실시간 수정이나 A/B 테스트가 필요해지면 DB 기반 관리로 전환 검토:

```
PromptTemplate
├─ name
├─ version
├─ content
├─ modelName
├─ temperature
├─ active
├─ createdAt
└─ updatedAt
```

**이번 이슈 범위**: DB 기반 프롬프트 관리자, A/B 테스트 — 미구현.

---

## 4. 프롬프트 메타데이터 관리

분석 결과 재현·원인 추적을 위해 최소한 다음을 확인할 수 있어야 한다: `promptName`, `promptVersion`, `modelName`.

**결정**: `ProductAnalysisSession`에 별도 컬럼을 추가하지 않는다. 이미 `visionResultJson`에 분석 결과 전체가
저장되고 있고, 세션 스키마를 또 넓히는 것보다는 `OpenAiVisionAnalysisService`가 분석 요청 시점에
`promptName`/`promptVersion`/`modelName`을 로그로 남기는 방식으로 최소 침습적으로 확인 가능하게 했다.

```java
log.info("Vision 분석 요청 - promptName={}, promptVersion={}, modelName={}", ...);
```

추후 이 메타데이터를 세션에도 영속화해야 할 필요가 생기면(예: 분석 이력 조회 API 등) 그때 컬럼 추가를 검토한다.

---

## 5. 전체 서비스 경계

```
ai/
├─ vision/
│  ├─ dto/
│  └─ service/            (OpenAiService, VisionAnalysisService, OpenAiVisionAnalysisService)
│
├─ prompt/
│  ├─ PromptTemplate
│  └─ PromptTemplateLoader
│
└─ recommendation/         (향후 구현)
   └─ BuyerRecommendationService

analyze/
├─ domain/
│  └─ ProductAnalysisSession
│
└─ service/
   ├─ ProductAnalyzeService       (Vision 오케스트레이터)
   ├─ ProductPricingService       (Pricing 오케스트레이터)
   └─ AnalysisFailureRecorder     (실패 상태 REQUIRES_NEW 기록)

product/
└─ pricing/
   ├─ PricingService
   └─ RuleBasedPricingService

buyer/         (향후 구현) — 구매자 상품 조회 및 구매
auction/       (향후 구현) — 경매, 입찰 및 낙찰
transaction/   (향후 구현) — 거래 상태 및 결제
```

## 포함 / 제외 범위 요약

**포함(문서화 + 일부 코드)**
- Buyer Agent 역할 재정의 및 서비스 경계 문서화
- pgvector vs Redis Vector 비교, Redis Vector 우선 후보 선정, 도입 보류 근거·조건 작성
- Vision 프롬프트 resources 외부화 + `PromptTemplateLoader` 구현 + 기존 API 동작 유지 + 회귀 테스트

**제외(미구현)**
- Redis 설치/운영, 상품 임베딩 생성, 벡터 인덱스, 실제 유사 상품 검색
- `buyer/`, `auction/`, `transaction/` 실제 구현
- Buyer AI 추천 기능 구현
- DB 기반 프롬프트 관리자, 프롬프트 A/B 테스트
