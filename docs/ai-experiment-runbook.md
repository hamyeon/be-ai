# AI 실험 실행 목록 (#106)

크레딧을 충전하면 이 순서대로 돌린다. 코드와 스위치는 전부 들어가 있어서 명령만 실행하면 된다.
결과는 `backend/build/vision-harness/`에 리포트(`.txt`)와 호출 원자료(`-calls.csv`)로 쌓이고,
실행 조건이 파일명에 들어가 서로 덮어쓰지 않는다.

> 2026-10-01 기준: 이 목록은 2026-09-29 벤더 비교 실측(`ai-vision-agent.md` "벤더 비교 실측")으로 대체됐다.
> E1(세 단계 동시)·E2(v3)·E5(claude-sonnet-5)와 768px이 운영 기본값(`application.yml`)으로 반영됐다.
> 하네스 기본값은 여전히 openai / v2 / sequential이라, 아래 E0 명령은 그대로 돌리면 전환 전 기준선이 된다.

목적은 두 가지다.

1. **분석 시간을 실제로 줄일 설정을 고른다.** 지금 사진을 올리고 추천가까지 약 20초이고, 가격 계산은 ms라
   거의 전부가 Vision이다(`ai-vision-agent.md` 마지막 절).
2. **발표용 전후 비교 표를 만든다.** 교수님 피드백: "토큰/속도를 얼마나 줄였는지 전후 비교를 보여주면 좋겠다."

## 0. 준비

```powershell
$env:OPENAI_API_KEY = "sk-..."          # 필수
$env:ANTHROPIC_API_KEY = "sk-ant-..."   # E5(벤더 비교)만
cd backend
```

- PowerShell에서는 `-D` 인자를 따옴표로 감싼다: `'-Dvision.harness=true'`
- **분당 토큰 한도(TPM)를 먼저 확인한다.** 기록된 한도는 30,000이다. 아래 토큰 수를 한도로 나눈 값이 한 번
  실행에 걸리는 최소 시간이다. 한도에 걸리면 하네스가 기다렸다 재시도하므로 실패하지는 않고 느려진다.
- **실험 사이에 코드를 바꾸지 않는다.** 모든 스위치의 기본값은 기존 동작이라 E0이 그대로 "개선 전"이 된다.

## 1. 실행 목록

순서가 중요하다. E0은 반드시 먼저 돌린다.

| ID | 무엇을 보나 | 셋 | 예상 토큰 | 명령에 붙일 인자 |
| --- | --- | --- | --- | --- |
| **E0-a** | **기준선** (사진 1장) | daangn 18건 | 약 10.6만 | `-Dvision.harness.fixtures=daangn` |
| **E0-b** | **기준선** (사진 3~6장, 실제 앱에 가까움) | daangn-multi 14건 | 약 17만 | `-Dvision.harness.fixtures=daangn-multi` |
| E1-a | 2·3단계 동시 실행 | daangn-multi | 약 17만 | E0-b + `-Dvision.harness.execution=parallel_label_condition` |
| E1-b | 세 단계 동시 실행 | daangn-multi | 약 17만 | E0-b + `-Dvision.harness.execution=all_parallel` |
| E2 | 출력 줄인 프롬프트 v3 | daangn-multi | 약 15만 이하 | E0-b + `-Dvision.harness.prompt-version=v3` |
| E3 | 해상도 곡선 512 / 768 / 1024 | daangn-multi | 약 30만 (3회분) | E0-b + `-Dvision.harness.variants=RESIZED_512,RESIZED_768,RESIZED_1024` |
| E4 | E1~E3 중 통과한 것 조합 | daangn-multi | 약 15만 | 통과한 인자를 모두 붙인다 |
| E5 | 벤더·모델 비교 (선택) | daangn-multi | 모델별 약 17만 | `-Dvision.harness.provider=claude -Dvision.harness.model=claude-sonnet-5` 등 |
| E6 | 구매 목표 파서·Matcher (#95, 미측정) | 50건 / 59건 | 수만 | 아래 별도 명령 |

공통 명령(E0~E5). 모두 `-Dvision.harness.agents=V2`로 3단계 방식만 돈다.

```powershell
./gradlew test --tests '*VisionPromptHarnessTest' '-Dvision.harness=true' '-Dvision.harness.agents=V2' <표의 인자>
```

E6:

```powershell
./gradlew test --tests '*GoalParsePromptHarnessTest' '-Dgoal.harness=true' '-Dgoal.harness.model=gpt-4o-mini'
./gradlew test --tests '*ListingMatchPromptHarnessTest' '-Dgoal.harness=true' '-Dgoal.harness.model=gpt-4o-mini'
```

> 2026-10-01 기준: E6 명령은 지금 조용히 skip된다. `build.gradle`이 테스트 JVM에 `vision.harness*` 프로퍼티만
> 넘겨 `-Dgoal.harness=true`가 전달되지 않는다. 돌리려면 `build.gradle`에 `goal.harness*` 전달을 추가해야 한다.

토큰 수는 기존 실측(daangn 케이스당 5,873, daangn-multi 케이스당 12,036)에서 계산한 값이다. 비용은 이 토큰 수에
그때의 모델 단가를 곱해 계산한다. E0~E4만 돌리면 약 110만 토큰이다.

## 2. 판정 기준

`ai-vision-agent.md` 4단계와 같은 순서로 본다.

1. **`CONDITION_GRADE` 오답 수가 늘면 탈락.** 등급은 가격 계수에 직접 들어간다.
2. 브랜드·모델 응답 정확도가 떨어지면 탈락. 브랜드 오독은 시세 조회 키를 통째로 틀리게 한다.
3. 1·2를 통과한 것 중 **케이스당 시간**이 짧은 것, 그다음 토큰이 적은 것.

표본이 14~18건이라 1건 차이로 뒤집힐 수 있다. 1건 차이로 갈리면 "차이 없음"으로 읽고, 시간이 줄었으면 채택한다.

리포트에서 볼 곳:

- 상단 `케이스당 평균 응답시간`, `토큰 ... 케이스당`
- `단계별` 표의 평균 지연·출력 토큰·비중 - 병목이 출력(출력 토큰이 많은 단계가 느림)인지 사진인지
- `-calls.csv`를 스프레드시트에 넣고 `completion_tokens` - `latency_ms` 산점도를 그리면 출력 병목 여부가 바로 보인다

## 3. 통과하면 바꿀 설정

| 실험 | 운영 설정 (application.yml / 환경변수) |
| --- | --- |
| E1 | `VISION_STAGE_EXECUTION_MODE=parallel-label-condition` 또는 `all-parallel` |
| E2 | `VISION_PROMPT_VERSION=v3` |
| E3 | `VISION_IMAGE_MAX_EDGE=512` 또는 `1024` (운영은 긴 변 기준 리사이즈라 CDN 크롭 변형과 조금 다르다) |
| E5 | `VISION_PROVIDER=claude`, `VISION_MODEL=...` |

`analysis.stream.concurrency`는 실험과 별개로 **조직 TPM을 확인한 뒤** 올린다(`ai-async-analysis.md` #106 절).

## 4. 발표용 전후 비교 표 (채울 자리)

| | 개선 전 (E0-b) | 개선 후 (E4) | 변화 |
| --- | --- | --- | --- |
| 케이스당 분석 시간 | | | |
| 케이스당 토큰 | | | |
| 등급 오답 / 근사 | | | |
| 브랜드 응답 정확도 | | | |
| 사진 업로드 (3장) | 순차 6회 | 사진 단위 동시 | 실제 앱에서 측정 |

사용자 입장 시간은 하네스가 아니라 실제 앱에서 잰다: 업로드 요청 시각부터 상태 조회가 `AWAITING_USER_CONFIRMATION`이
되는 시각까지. `ai_call_logs`의 `latency_ms`를 분석 ID로 묶으면 그중 Vision 몫이 나온다.

## 5. 크레딧만으로는 못 하는 것

- **사진 역할별 전송**(앞면·옆면·밑창·태그, 태그만 고화질): 프론트 촬영 슬롯과 역할이 표시된 평가 셋이 먼저 필요하다.
  크롤링 사진에는 역할 정보가 없어서, 팀원 신발을 슬롯대로 직접 찍어 15~20켤레를 모아야 한다.
- **Redis 재시작 재현**(`ai-async-analysis.md` #106 절): 크레딧이 아니라 Docker가 필요하다.
