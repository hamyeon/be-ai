# Purchase Agent - AI 파트 (#95)

Purchase Agent(사용자가 "뉴발 990, A급 이상, 15만원 이하로 하나"라고 등록하면 시스템이
여러 경매에 걸쳐 대신 탐색·입찰하는 기능)에서 AI 트랙이 맡는 부분의 구현 기록이다.
설계안은 팀 노션의 "Purchase Agent 설계안(2026-09-09)"이고, 이 문서는 그 6-1/6-2/6-4를
코드로 옮기며 결정한 것과 측정한 것을 남긴다.

AI 담당은 셋이다. (1) 자연어 → Goal 초안 파싱, (2) 매물 적합도 판정, (3) AI 시세 제공.
이 문서는 (1)을 먼저 다룬다. (2)와 (3)은 같은 브랜치에서 이어서 붙이고 이 문서에 추가한다.

**관통하는 원칙**: LLM은 해석만 하고 서버가 검증한다. LLM이 낸 값이 사람 확인 없이 cap이나
입찰 금액에 닿는 경로는 없다. 못 알아본 필드는 null이고 이유가 warnings에 실린다.

## 갱신 로그

| 날짜 | 내용 |
| --- | --- |
| 2026-09-10 | Goal 파서(규칙 기반 + OpenAI) · 모델 별칭 표 · 하네스 50케이스 · `POST /api/purchase-goals/parse` |

---

## 1. 무엇을 만들었나

```
POST /api/purchase-goals/parse  { "text": "뉴발 990, A급 이상, 15만원 이하로 하나" }
        │
        ▼
GoalParser (인터페이스, 설계안 6-4)
  ├ OpenAiGoalParser   시스템 프롬프트에 시세 카탈로그(45개 모델·별칭)를 실어 Structured Outputs로 받음
  │     └ GoalDraftValidator   카탈로그 밖 키·비정상 금액·사이즈를 걷어내고 warnings 부착
  ├ RuleBasedGoalParser 별칭 표 + 정규식. API 없음. 결정적
  └ FallbackGoalParser  OpenAI 실패 시 규칙 기반으로 대체 (경고 + confidence ≤ 0.5)
        │
        ▼
GoalDraft (초안) → 프론트 확인·수정 화면 → POST /api/purchase-goals (백엔드 담당, 미구현)
```

`purchase-agent.parser.provider`(application.yml)로 `openai`(기본) / `rule`을 고른다.
하네스에서 LLM이 규칙 기반보다 낫다는 게 확인되기 전이거나 OpenAI 장애 때 `rule`로 내린다.

### 1-1. GoalDraft 필드

설계안 6-1 계약 필드 다섯 개에 네 개를 **추가**했다(전부 nullable, 기존 필드 의미는 그대로).

| 필드 | 출처 | 왜 추가했나 |
| --- | --- | --- |
| `modelQuery`, `minCondition`, `hardMaxAmount`, `freeTextConditions`, `confidence` | 계약 6-1 | - |
| `brand` | 추가 | 모델을 못 특정해도 브랜드로 pre-filter가 가능하게 |
| `modelKey` | 추가 | 시세 CSV의 `model_key`. pre-filter·시세 조회·Matcher가 문자열 비교 대신 이 키로 만난다. null이면 시세 카탈로그 밖 모델이라 v1 Agent는 후보를 못 찾는다 |
| `sizeKr` | 추가 | **계약에 사이즈 칸이 없었다.** 신발은 사이즈 없이 살 수 없는데 자유 조건(soft)에 섞이면 다른 사이즈를 사게 된다. 백엔드 pre-filter가 hard 조건으로 써야 한다 - 팀 합의 필요 |
| `warnings` | 추가 | 확인 화면 안내. "사이즈 없음", "'박스 필수'는 v1에서 참고 사항", "AI 실패 → 규칙 기반 초안" |

`minCondition`은 DS/S/A/B/C 5단계(`GoalCondition`)다. Vision의 `ConditionGrade`에는 S가 없지만
시세 계수(condition_rates.csv)와 사용자 표현("거의 새것")에는 S가 있다. 백엔드 pre-filter는
`GoalCondition.satisfiedBy()`로 서열 비교하고, 매물 등급이 UNKNOWN이면 비교하지 말고 제외해야 한다.

### 1-2. 모델 별칭 표 (`data/model_aliases.csv`, `ModelAliases`)

`Product.model`은 Vision 초안 기반 자유 텍스트라 정확 표기율이 22%다(vision-agent.md). 설계안
5-1의 "모델·브랜드 구조화 필드 pre-filter"를 문자열 동등 비교로 하면 후보 대부분이 탈락한다.
그래서 "뉴발 990" / "990v6" / "NB990" / "New Balance 990"을 전부 `nb990`으로 접는 표를
백엔드에 두었다. 키 체계는 `used_market_prices.csv`의 `model_key`를 그대로 따른다 -
파서가 고른 키로 바로 시세를 찾을 수 있어야 하기 때문이다(`ModelAliasesTest`가 두 CSV의 키
집합이 같은지 검사한다).

매칭 규칙은 크롤러의 `model_aliases.py`와 같다. 소문자 + 영숫자/한글만 남긴 뒤 긴 별칭부터
포함 검사한다("덩크하이"가 "덩크"에 먹히지 않게). 숫자만 있는 모델(530/990/1461)은 브랜드
표기가 붙은 별칭만 둔다 - 가격·사이즈·품번 숫자에 붙는 오탐 방지.

이 표는 파서만이 아니라 (2) Matcher의 규칙 기반 구현과 백엔드 pre-filter가 같이 쓴다.

### 1-3. 서버 검증 (`GoalDraftValidator`)

Structured Outputs는 형태만 보장한다. 값은 서버가 본다.

| 검증 | 처리 |
| --- | --- |
| `modelKey`가 카탈로그에 없음 | null로 비우고 "시세 없는 모델" 경고, confidence ≤ 0.6 |
| `modelKey`가 카탈로그에 있음 | `brand`/`modelQuery`는 LLM 값이 아니라 카탈로그 값으로 덮어씀 |
| `hardMaxAmount` < 1만 또는 > 1억 | null + 경고 |
| `sizeKr` 200~330 밖 | null |
| `minCondition` 미지 표기 | null |
| `confidence` 0~1 밖 | 잘라냄 |

### 1-4. 관측성

`AiCallType.CHAT`을 추가했다(VISION/EMBEDDING과 비용 단가가 달라 분리). 파싱 호출은
stage=`goal-parse`, promptVersion=`v1`로 `AiCallLog`에 남는다. Vision 클라이언트를 재사용하므로
429 재시도·토큰 집계는 그대로 적용된다.

### 1-5. 벤더 교체 가능성

`ChatCompletionClient` 인터페이스를 뽑고 `OpenAiVisionClient`가 구현하게 했다. 요청/응답 값
객체(`VisionChatRequest`/`VisionChatResponse`)에는 벤더 wire format이 없다(모델명, 프롬프트,
텍스트, 이미지 URL, JSON Schema, 토큰 수). 다른 벤더로 바꾸려면 이 인터페이스 구현체를 하나
더 만들고 빈만 바꾸면 된다. 응답 스키마 파일은 표준 JSON Schema라 그대로 쓰고, 프롬프트는
모델마다 반응이 달라 하네스로 다시 잰다.

---

## 2. 측정

### 2-1. 하네스

`src/test/resources/purchase/goal-parse-fixtures.json` - 50케이스. 사용자 표현을 흉내 낸 문장에
기대값을 사람이 라벨링했다. brand/modelKey/minCondition/hardMaxAmount/sizeKr 5필드는 정확
일치(null도 값), 자유 조건은 키워드 재현율로 잰다. 모델 혼동(990/993, 덩크로우/덩크하이),
최소/최대 금액, 만원대, 사이즈와 품번 숫자 혼동, 카탈로그 밖 모델, 브랜드만 있는 경우를
고르게 넣었다.

- 규칙 기반: `RuleBasedGoalParserHarnessTest` - 매 빌드에서 돌고 `build/goal-parse-harness/rule.txt`에 남긴다. 기준선 회귀 하한(modelKey·금액 85%, 5필드 60%)을 어기면 실패한다.
- OpenAI: `GoalParsePromptHarnessTest` - `OPENAI_API_KEY` + `-Dgoal.harness=true`일 때만. `build/goal-parse-harness/openai-{model}.txt`.

```
./gradlew test --tests '*GoalParsePromptHarnessTest' -Dgoal.harness=true -Dgoal.harness.model=gpt-4o-mini
```

### 2-2. 결과

**규칙 기반 파서 (2026-09-10, 픽스처 50건, `RuleBasedGoalParserHarnessTest`)**

| 항목 | 결과 |
| --- | --- |
| brand / modelKey / minCondition / hardMaxAmount / sizeKr | 각 50/50 (100%) |
| 5필드 전부 일치 | 50/50 (100%) |
| 자유조건 키워드 재현율 | 100% (키워드 있는 케이스 34건) |
| confidence 평균 (5필드 정답) | 0.77 (상한 0.85) |
| 지연 | 평균 1ms, 최대 25ms |
| 예외 | 0건 |

100%라는 숫자를 그대로 믿으면 안 된다. **픽스처를 만든 사람이 규칙도 만들었다** - 규칙이 아는
표현으로 문장을 썼을 가능성이 높다. 첫 실행에서는 49/50이었고, 어절 끝 "이"를 조사로 잘라
"그레이"가 "그레"가 되는 버그와 "이지 350"의 브랜드를 모르는 문제를 고쳐 100%가 됐다.
이 수치는 "규칙이 픽스처를 다 처리한다"이지 "실사용 입력을 다 처리한다"가 아니다.
실서비스 입력으로 픽스처를 늘리기 전까지 규칙 기반의 진짜 정확도는 모른다.

**OpenAI 파서**: 미측정. 유료 호출이라 자동으로 돌리지 않았다 - `GoalParsePromptHarnessTest`를
키가 있는 환경에서 명시적으로 실행해야 한다. 규칙 기반이 픽스처를 다 맞추므로 LLM의 가치는 픽스처 밖 표현(오타, 신조어, 카탈로그 밖
모델의 브랜드 추론, 복합 조건)에서 나와야 하고, 그걸 재려면 픽스처에 그런 케이스를 더 넣어야 한다.

### 2-3. 아직 못 잰 것

- OpenAI 파서의 정확도·지연·토큰. 키가 있는 환경에서 위 명령으로 재고 이 표에 채운다.
  규칙 기반보다 나은 게 없으면 provider 기본값을 `rule`로 바꾼다.
- 실제 사용자 입력 분포. 픽스처는 작성자가 만든 문장이라 실서비스 표현과 다를 수 있다.
  서비스가 열리면 파싱 요청 원문(AiCallLog requestSummary)에서 오답을 모아 픽스처에 추가한다.

---

## 3. 백엔드에 넘길 것 / 팀 결정 필요

1. **`sizeKr`를 hard 조건으로.** pre-filter에 `product.sizeKr == goal.sizeKr` (goal.sizeKr가 null이면 무시).
2. **모델 pre-filter는 `modelKey`로.** `Product.model` 문자열 대신 `ModelAliases.find(product.brand + " " + product.model)`의 키와 `goal.modelKey`를 비교. 키가 null인 Goal은 브랜드만 비교.
3. **등급 비교는 `GoalCondition.satisfiedBy()`로.** 매물 `conditionGrade`가 UNKNOWN이면 제외.
4. **6-2 Matcher 요청에서 `imageKeys` 제거 제안.** v1은 텍스트만(Vision 케이스당 12.6초).
5. **`aiEstimatedPrice` 시점.** 등록 시점 `Product.recommendedPrice`(판매자용 추천가, stale)를 쓸지 ENGAGE 시점 재계산할지. (3) 시세 제공에서 provider를 만들어 두고 Day 0에 결정.

## 4. 코드 지도

| 위치 | 내용 |
| --- | --- |
| `ai/purchase/dto/` | `GoalDraft`, `GoalCondition` |
| `ai/purchase/model/` | `ModelAliases`, `BrandAliases` |
| `ai/purchase/parser/` | `GoalParser`, `RuleBasedGoalParser`, `OpenAiGoalParser`, `GoalDraftValidator`, `FallbackGoalParser`, `GoalParserConfig`, `GoalParserProperties` |
| `ai/purchase/api/` | `GoalParseController`, `GoalParseRequest` |
| `ai/vision/client/ChatCompletionClient` | 텍스트/이미지 Structured Outputs 호출 인터페이스 |
| `resources/prompts/purchase/goal-parse-v1.{md,schema.json}` | 프롬프트·응답 스키마 |
| `resources/data/model_aliases.csv` | 모델 별칭 표 |
| `test/.../ai/purchase/harness/` | 픽스처 로더·채점·리포트·하네스 테스트 |
