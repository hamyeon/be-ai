# Vision Agent + 평가 하네스 (#21)

Vision 분석을 "한 번에 다 물어보고 나온 JSON을 믿는" 구조에서,
단계별로 물어보고 근거를 요구하고 결과를 측정 가능한 구조로 바꾸는 작업.

## 왜

지금(`product-analysis-system-v1.md`) 구조의 한계는 세 가지다.

1. **측정이 없다.** 프롬프트를 고쳐도 좋아졌는지 나빠졌는지 판단할 근거가 없다.
   EC2에서 몇 건 돌려보고 "brand/modelName/color/conditionGrade는 잘 나오는데 size는 안 나온다"는
   인상만 있는 상태다.
2. **근거가 없다.** 모델이 왜 그렇게 판단했는지 응답에 남지 않는다. 그래서 희소성("희귀 모델입니다")이나
   연식("20년 전 제품") 같은 확인 불가능한 설명이 섞여 들어와도 걸러낼 방법이 없다.
3. **한 번에 다 묻는다.** 실루엣 추정 / 라벨 판독 / 컨디션 판정은 필요한 이미지 해상도도, 실패했을 때
   대처법도 다른데 한 프롬프트에 뭉쳐 있다.

그래서 **측정 도구(하네스)를 먼저 만들고, 그다음 프롬프트/스키마를 바꾼다.**
순서를 뒤집으면 개선인지 아닌지 증명할 수 없다.

## 진행 순서

| 단계 | 내용 | 상태 |
|---|---|---|
| 1 | 평가 하네스 + 정답 라벨 픽스처, 현재 v1 프롬프트 기준선 측정 | 구현 완료 (기준선 측정 대기) |
| 2 | 응답 JSON Schema 고정 (Structured Outputs) + 근거 필드 필수화 | 예정 |
| 3 | 3단계 프롬프트(전체 형태 → 라벨/로고 → 오염/마모)로 분리, 환각 차단 규칙 | 예정 |
| 4 | `detail: high` / 이미지 해상도 A/B를 하네스로 돌려 단계별 옵션 확정 | 예정 |

---

## 1단계: 평가 하네스

### 평가 셋

`backend/src/test/resources/vision/harness-fixtures.json` — 당근마켓 크롤링 매물 18건.

정답(ground truth)은 **판매자가 본문에 적어둔 값**을 라벨로 썼다.
사이즈는 `사이즈 260`, 박스는 `박스 없어요` / `박스도 같이 드려요`, 상태는 `실착 1회` / `사용감많습니다`
같은 문장에서 뽑았고, 근거 문장은 각 케이스의 `groundTruthSource`에 남겨뒀다.

브랜드 18건은 Nike / Adidas / New Balance / Dr. Martens / Vans / Asics / Puma / Onitsuka Tiger /
Hoka / Converse로 분산시켰다. 한 브랜드에 몰리면 그 브랜드에만 맞는 프롬프트를 만들게 된다.

라벨이 애매한 매물(`사이즈 245~250`, `M5, W7 (240)`처럼 범위로 적힌 것)은 채점이 불가능해서 제외했다.

### 채점 기준

핵심은 **"못 채운 것"과 "틀리게 채운 것"을 절대 같이 세지 않는 것**이다.

| 결과 | 의미 |
|---|---|
| `CORRECT` | 값을 채웠고 정답 |
| `NEAR` | 한 등급 차이 (conditionGrade 전용) |
| `WRONG` | 값을 채웠는데 오답 = **환각** |
| `ABSTAINED` | null 또는 UNKNOWN, 모르겠다고 기권 |
| `NOT_LABELED` | 픽스처에 정답이 없어 채점 제외 |

여기서 나오는 지표는 세 가지다.

- **응답률(fill rate)** = 값을 채운 비율
- **응답 정확도(precision)** = 채운 것 중 맞은 비율 → 낮으면 환각이 많다는 뜻
- **전체 정확도(accuracy)** = 채점 대상 전체 중 맞은 비율

`size` 응답률을 올리는 게 이번 이슈의 목표지만, **응답 정확도를 떨어뜨리면서 응답률만 올리는 변경은
개선이 아니다.** 없는 사이즈를 지어내느니 기권하고 사용자에게 확인받는 쪽이 낫다. 표를 두 열로 나눠
보는 이유가 이것이다.

브랜드와 모델명은 표기가 흔들려서 완전 일치로 채점할 수 없다.
- 브랜드: 허용 표기를 배열로 두고(조던 → `["Nike", "Jordan"]`) 정규화 후 포함 관계면 정답
- 모델명: 식별에 필요한 키워드가 전부 들어있으면 정답 (`["chuck", "70"]` → "Chuck Taylor All Star 70 Hi" 정답)

### 이미지 해상도가 변수라는 점

크롤러가 저장해 둔 이미지 URL에는 `?q=82&s=300x300&t=crop`이 붙어 있다.
**그대로 쓰면 300x300 썸네일(약 17KB)이 내려온다.** 쿼리를 떼면 원본(약 280KB)이다.

이건 4단계(`detail: high`)와 직결된다. 300x300 이미지에 `detail: high`를 켜봐야 얻을 게 없으므로,
해상도와 detail은 반드시 같이 놓고 비교해야 한다. 그래서 픽스처에는 쿼리 없는 원본 URL만 담고,
`VisionHarnessImageVariant`가 실행 시점에 변형을 붙인다.

> 참고: 실제 서비스 경로(사용자 업로드 → S3)는 이 CDN 리사이즈 문제와 무관하다.
> 다만 크롤링 데이터를 학습/평가에 쓸 때는 계속 걸리는 문제라 백필 스크립트 쪽도 한 번 봐야 한다.

### 구성

```
src/test/java/com/vintic/backend/ai/vision/harness/
  VisionHarnessCase.java          픽스처 1건 (이미지 + 정답 라벨)
  VisionHarnessFixtures.java      픽스처 로더
  VisionHarnessImageVariant.java  해상도 변형 (ORIGIN / THUMBNAIL_300)
  VisionHarnessScorer.java        채점 (순수 함수)
  VisionHarnessReport.java        집계 + 표 출력
  VisionHarnessScorerTest.java    채점 로직 단위 테스트 (CI에서 항상 실행)
  VisionHarnessFixturesTest.java  픽스처 무결성 테스트 (CI에서 항상 실행)
  VisionPromptHarnessTest.java    실제 API 호출 (OPENAI_API_KEY 있을 때만 실행)
```

채점 로직을 API 호출과 분리해 둬서, 키 없는 환경에서도 채점 규칙 자체는 계속 검증된다.

### 실행

```bash
export OPENAI_API_KEY=...
./gradlew test --tests '*VisionPromptHarnessTest' \
  -Dvision.harness.variants=ORIGIN,THUMBNAIL_300
```

결과는 콘솔과 `backend/build/vision-harness/v1-{variant}.txt`에 남는다.
프롬프트를 바꿀 때마다 돌려서 이 표를 비교한다.

### 기준선

> v1 프롬프트 측정 결과는 실제 키가 있는 환경에서 한 번 돌린 뒤 여기에 채운다.
> (이 세션에는 `OPENAI_API_KEY`가 없어 실제 호출은 하지 못했다.)
