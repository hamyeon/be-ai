# AI 데이터 파운데이션 — 검색/큐레이션 PoC

`GET /curations` 더미 API + Text RAG용 Document/Chunk/Embedding 구조 PoC 정리 문서.
Redis는 설치하지 않았고, `EmbeddingStore` 인터페이스 뒤에서 `InMemoryEmbeddingStore`로만 검증했다.

## 1. Curation 정의 및 응답 스키마

**정의**: 구매자에게 제공하는 추천 상품 묶음 또는 테마별 상품 컬렉션. (예: 최근 인기 상품, 20만원 이하 추천 상품, 경매 마감 임박 상품, 특정 브랜드 추천 상품)

**이번 구현 범위**: 실제 AI 추천이 아니라 프론트엔드 연동을 위한 더미 데이터. 응답 스키마는 실제 추천으로 교체될 때도 그대로 유지되도록 설계함.

```
GET /api/curations
→ ApiResponse<List<CurationResponse>>

CurationResponse
├─ curationId   (예: "recently-popular", "under-200000")
├─ title        (예: "최근 인기 상품")
├─ description
└─ items: List<CurationItem>
    ├─ productId
    ├─ brand
    ├─ modelName
    ├─ title
    ├─ price
    └─ imageUrl
```

향후 실제 추천 로직(`ai/recommendation` 등, #14 문서 참고)이 준비되면 `CurationService.getCurations()` 내부 구현만 교체하면 되고, 스키마 변경은 필요 없다.

## 2. Document / Chunk / Embedding 구조

```
Product (등록된 상품)
   ↓ ProductDocumentBuilder
SearchDocument (documentId, sourceType, sourceId, text, metadata)
   ↓ ChunkingStrategy
DocumentChunk (documentId, chunkIndex, text)
   ↓ EmbeddingClient
EmbeddingVector (chunkId, vector)
   ↓ EmbeddingStore.save()
검색 시 EmbeddingStore.search(queryVector, topK) → ScoredChunk 목록
```

**검색 대상 텍스트**: 상품 제목(브랜드+모델+컬러웨이), 상품 설명(`sellerDescription`), 사이즈, 상태 등급, 구성품 상태 — `ProductDocumentBuilder`가 이 필드들을 하나의 텍스트로 합친다.

**metadata**: 브랜드/사이즈/가격처럼 명확한 조건은 `SearchDocument.metadata()`에 구조화된 값으로 같이 들고 다닌다 (3번 참고).

## 3. Chunking 결정

- **상품 데이터(짧음)**: `AtomicChunkingStrategy` — 억지로 쪼개지 않고 상품 하나 = chunk 하나. 실제 프로덕션에 쓸 기본 전략.
- **긴 설명/가이드 문서**: 아직 실제 데이터가 없어서, 합성 예시(신발 관리 가이드 텍스트)로 `FixedSizeChunkingStrategy`(글자 수 기준, overlap 있음) vs `SentenceChunkingStrategy`(문장 경계 유지) 두 전략을 비교했다. (`ChunkingStrategyComparisonTest` 참고)
  - **관찰**: 글자 수 기준 자르기는 문장 중간에서 끊기는 chunk가 실제로 발생함(테스트로 확인). 문장 경계 전략은 항상 문장 단위로 끝나 문맥이 덜 끊긴다.
  - **결론**: 향후 실제로 긴 문서(구매 가이드, FAQ 등)를 다루게 되면 문장 경계 기반 chunking을 기본으로 검토할 것을 추천. 다만 지금은 실제 대상 데이터가 없어 확정하지 않음.

## 4. EmbeddingStore 추상화

```java
public interface EmbeddingStore {
    void save(EmbeddingVector embedding);
    List<ScoredChunk> search(float[] queryVector, int topK);
}
```

- 현재 구현체: `InMemoryEmbeddingStore` (코사인 유사도, 저장된 벡터 전체를 순회하는 O(n) 브루트포스)
- 향후 Redis Vector 도입이 실제로 결정되면 `RedisEmbeddingStore implements EmbeddingStore`만 추가하고, 이걸 사용하는 쪽(`ProductPricingService` 등 향후 검색 기능)은 인터페이스만 바라보므로 코드 변경이 최소화된다.
- `InMemoryEmbeddingStore`는 데이터가 많아지면(수만 건 이상) O(n) 브루트포스 비용이 커지므로, 그 시점이 Redis Vector 도입을 다시 검토할 신호 중 하나다 (#14 문서의 도입 조건과 동일).

## 5. 문자열 검색 vs 벡터 검색 — 실제 사용 시 결합 방침

**중요한 원칙: 명확한 조건(가격, 사이즈, 브랜드 등)은 벡터 검색에만 맡기지 않는다.**

```
사용자 질의
   ↓
1) 명확한 조건 추출 (가격 범위, 사이즈, 브랜드 등) → MySQL WHERE 필터로 먼저 후보군을 좁힘
2) 좁혀진 후보군 안에서만 벡터 유사도로 재정렬 (또는 자연어 취향 표현 매칭)
   ↓
최종 결과
```

이렇게 해야 하는 이유:
- "27cm 나이키 신발" 같은 질의에서 사이즈/브랜드는 임베딩 유사도로 걸러지는 것보다 SQL로 정확히 필터링하는 게 훨씬 안정적이고 빠름
- 벡터 검색은 "발볼 넓은", "편안한", "여름에 어울리는"처럼 **문자열 매칭으로 안 잡히는 의미/취향 기반 질의**에 쓸 때 실익이 큼
- 두 가지를 섞어 쓰는 게 순수 벡터 검색보다 실제 서비스에는 더 안정적

## 6. 테스트 질의 (품질 평가용)

`OpenAiEmbeddingPoCTest`에 실제로 구현되어 있는 10개 질의. `OPENAI_API_KEY`가 있는 환경에서 직접 실행해서 벡터 검색 1위 결과가 기대와 맞는지 확인해야 한다 (이 세션에는 키가 없어 직접 실행하지 못했음).

| 질의 | 기대 상품 | 의도 |
|---|---|---|
| 발볼 넓은 편한 신발 찾아요 | product-3 (New Balance 993) | "편한"→"쿠셔닝/발볼 넓음" 의미 매칭, 문자열엔 없는 표현 |
| 박스 있는 새 신발 | product-1 (Nike Dunk, DS+박스) | 구성품+상태 조합 |
| 아직 한 번도 안 신은 신발 | product-1 | "미착용"의 다른 표현 |
| 사용감 있고 저렴한 신발 | product-2 (Jordan 1, B등급) | 상태+가격 뉘앙스 |
| 하얀색 계열 신발 | product-5 (adidas Samba White) | 색상 |
| 빨간색 포인트 신발 | product-4 (Air Max 95 Neon) | 색상 포인트 |
| 가벼운 러닝화 | product-3 | 용도 표현 |
| 클래식한 가죽 스니커즈 | product-2 | 소재/스타일 표현 |
| 여름에 신기 좋은 신발 | product-5 | 계절 표현(문자열엔 없음) |
| 발이 편안한 쿠션 좋은 신발 | product-3 | 편안함 표현 |

각 질의는 의도적으로 **원본 텍스트에 없는 동의어/의미 표현**으로 만들었다 — 문자열 검색이 실패하고 벡터 검색만 성공하는 케이스가 있어야 벡터 검색의 실익을 판단할 수 있기 때문.

## 6-1. OpenAI Embedding 검색 결과 (실제 실행 완료)

`OPENAI_API_KEY` 설정 후 `./gradlew test --tests "*OpenAiEmbeddingPoCTest" --info`를 직접 실행해 확인함. BUILD SUCCESSFUL.

- 사용 모델: `text-embedding-3-small`
- 테스트 질의: 10개
- 검색 방식: cosine similarity, Top-3
- 저장 방식: `InMemoryEmbeddingStore`

| 질의 | 기대 상품 | Top-1 결과 | Top-3 포함 | 판단 |
| --- | --- | --- | --- | --- |
| 발볼 넓은 편한 신발 찾아요 | product-3 | product-3 | O | 성공 |
| 박스 있는 새 신발 | product-1 | product-1 | O | 성공 |
| 아직 한 번도 안 신은 신발 | product-1 | product-1 | O | 성공 (문자열 검색 실패, 벡터만 성공) |
| 사용감 있고 저렴한 신발 | product-2 | product-2 | O | 성공 |
| 하얀색 계열 신발 | product-5 | 기대 상품 아님 (product-5는 2위) | O | Top-1 미스 |
| 빨간색 포인트 신발 | product-4 | product-4 | O | 성공 |
| 가벼운 러닝화 | product-3 | 기대 상품 아님 (product-3는 2위) | O | Top-1 미스 |
| 클래식한 가죽 스니커즈 | product-2 | 기대 상품 아님 (product-2는 2위) | O | Top-1 미스 |
| 여름에 신기 좋은 신발 | product-5 | product-5 | O | 성공 (문자열 검색 실패, 벡터만 성공) |
| 발이 편안한 쿠션 좋은 신발 | product-3 | product-3 | O | 성공 (문자열 검색 실패, 벡터만 성공) |

**지표** (테스트 콘솔 출력 그대로)

- Hit@1: 7/10 (70%)
- Hit@3: 10/10 (100%)
- MRR: 0.850
- Top-1 미스: 3건, 전부 기대 상품이 2위로 밀린 경우였고 완전히 무관한 상품이 1위로 나온 "진짜 오검색"은 없었음

## 6-2. 문자열 검색 실패 → 벡터 검색 성공 사례

아래 3개 질의는 원본 텍스트에 없는 동의어/의미 표현으로 구성했는데, 실제로 문자열 검색은 실패하고 벡터 검색만 기대 상품을 1위로 찾아냈다.

- **"아직 한 번도 안 신은 신발"** → product-1 (실제 텍스트는 "미착용 새상품"이라고만 되어 있고 "안 신은"이라는 표현은 없음)
- **"여름에 신기 좋은 신발"** → product-5 (실제 텍스트는 "화이트 계열", "통풍" 등으로만 표현되고 "여름"이라는 단어는 없음)
- **"발이 편안한 쿠션 좋은 신발"** → product-3 (실제 텍스트의 "쿠셔닝이 좋고", "편해요" 표현과 의미적으로만 연결됨)

이 3개가 이번 PoC에서 벡터 검색의 실익을 가장 분명하게 보여준 사례다.

## 6-3. Top-1 미스 사례 (3건) 및 원인 분석

세 사례 모두 기대 상품이 완전히 밀려난 게 아니라 **2위**였고, 1위와의 유사도 점수 차이가 매우 작았다.

| 질의 | 실제 1위 | 1위 점수 | 기대 상품(2위) | 2위 점수 | 점수 차이 |
| --- | --- | --- | --- | --- | --- |
| 하얀색 계열 신발 | product-3 | 0.392 | product-5 | 0.379 | 0.013 |
| 가벼운 러닝화 | product-2 | 0.264 | product-3 | 0.261 | 0.003 |
| 클래식한 가죽 스니커즈 | product-5 | 0.309 | product-2 | 0.303 | 0.006 |

점수 차이가 0.003~0.013 수준으로 매우 근소해서, 특정 원인 하나로 확정하기는 어렵다. **소규모 샘플(상품 5개) 안에서 상품 설명들의 의미가 서로 겹치면서 근소한 순위 차이가 발생한 것으로 추정**되며, 상품 수가 늘어나면 이 정도의 근소한 역전은 자연스럽게 더 자주 나타날 수 있다.

세 경우 모두 확정적 원인 규명이라기보단 텍스트 내용에 근거한 추정이다 — 실제 1위로 나온 상품이 무엇이었는지 확인해야 정확한 원인을 알 수 있다.

## 6-4. 결론: Vector 검색만으로는 부족하고 MySQL 필터/재정렬이 필요함

이번 PoC 수치가 이 결론을 뒷받침한다: **Top-3까지는 100% 정확했지만 Top-1 단독으로는 70%에 그쳤다.** 즉 벡터 검색은 "관련 있는 후보군을 좁히는 데"는 확실히 쓸모 있지만, 그 결과를 그대로 최종 순위로 노출하기엔 아직 오차가 있다. 5번에서 이미 정리한 방침(가격/사이즈/브랜드 같은 명확한 조건은 MySQL로 먼저 필터링하고, 그 안에서만 벡터 유사도로 정렬)이 이번 결과로 다시 한번 뒷받침된다. 추가로, Top-3 안에서 텍스트 키워드 일치 여부로 재정렬(rerank)하는 후처리를 더하면 이번에 놓친 3건 같은 근소한 순위 역전도 보완할 수 있을 것으로 보인다 — 다만 이번 이슈 범위에는 포함하지 않는다.

## 7. Redis Vector 실제 도입 필요성 재평가 — 실행 방법

1. `OPENAI_API_KEY`를 실제 값으로 설정하고 `OpenAiEmbeddingPoCTest`를 실행한다. — **완료 (2026-07-27 실행, BUILD SUCCESSFUL)**
2. 콘솔에 출력되는 Hit@1/Hit@3/MRR과, 문자열 검색 결과가 실패하는 질의가 실제로 몇 개인지 확인한다. — **완료, 결과는 6-1~6-4 참고**
3. 아래 중 하나라도 확인되면 #14에서 정의한 도입 조건이 충족된 것이므로 Redis Vector 도입을 다시 논의한다:
   - 벡터 검색이 문자열 검색으로는 못 찾는 질의를 유의미하게 더 많이 찾아낸다 — **충족됨** (6-2의 3건)
   - 실제 상품 수가 늘어나 `InMemoryEmbeddingStore`의 O(n) 브루트포스 검색이 느려진다 (체감 지연 또는 실측 응답 시간 기준) — 아직 미충족 (실제 서비스 데이터로 검증 안 됨)
   - 자연어 검색/취향 기반 추천 기능을 실제로 만들기로 결정한다 — 아직 미충족 (기능 자체가 미구현)
4. 벡터 검색의 실익 자체는 이번 PoC로 확인됐지만, **아래 8번 결론에 따라 Redis 설치는 여전히 보류한다.**

## 8. 최종 결론

**PoC 결과가 좋게 나왔지만(Hit@3 100%, MRR 0.850), 지금 당장 Redis를 구축하지 않는다.**

```
OpenAI Embedding 및 Vector 검색 가능성 확인
  → 인메모리 PoC 성공 (Hit@1 70% / Hit@3 100% / MRR 0.850)
  → 운영 저장소는 아직 필요하지 않음
  → 실제 추천·유사 상품 검색 API를 구현하는 시점에 Redis Vector 재검토
```

이번 작업의 목표는 "Redis를 구축하는 것"이 아니라 **"벡터 검색이 이 프로젝트에서 실제로 의미가 있는지 확인하는 것"**이었다. PoC가 성공적이어도 실 사용처(추천 API, 유사 상품 검색 API)가 구현되기 전까지는 운영 인프라 도입을 보류한다.

## 포함 / 제외 범위

**포함**: curation 더미 API, `SearchDocument`/`DocumentChunk`/`ChunkingStrategy`(atomic+실험용 2종)/`EmbeddingStore`/`InMemoryEmbeddingStore`/`OpenAiEmbeddingClient` 구현, 테스트 10종 질의, 실제 OpenAI Embedding API로 PoC 실행 및 결과 검증(Hit@1 70%/Hit@3 100%/MRR 0.850) 완료, 위 비교/재평가 방법 문서화

**제외**: Redis 설치·운영, `RedisEmbeddingStore` 구현, 실제 서비스 API에 벡터 검색 연동, 실제 상품 데이터 대량 임베딩/색인
