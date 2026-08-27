# 배포 환경 설정

배포할 때 주입해야 하는 환경변수와, 관리 엔드포인트 접근 방법을 정리한다.

## 포트 구성

애플리케이션이 포트 두 개를 연다.

| 포트 | 용도 | 외부 공개 |
| --- | --- | --- |
| 8080 | 서비스 API (`/api/*`) | 공개 |
| 8081 | 관리 엔드포인트 (`/actuator/*`) | **차단해야 함** |

앱 앞에 리버스 프록시가 없어 경로 단위로 접근을 막을 지점이 없다. 그래서 포트를 나누고
**보안그룹에서 8081 인바운드를 막는 방식**으로 격리한다.

> **보안그룹에서 8081을 막지 않으면 이 분리는 아무 의미가 없다.**
> 포트만 나뉘고 둘 다 외부에 열린 상태가 된다.

## 환경변수

### 관리 엔드포인트

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `MANAGEMENT_PORT` | `8081` | 관리 엔드포인트 포트. 빈 값이면 서비스 포트를 그대로 쓴다 |
| `MANAGEMENT_ENDPOINTS` | `health,info,metrics` | 노출할 엔드포인트 목록 |
| `MANAGEMENT_HEALTH_DETAILS` | 배포 `never` / 로컬 `always` | health 상세 정보 노출 조건 |

`MANAGEMENT_HEALTH_DETAILS`는 배포 프로필에서 `never`로 둔다. health 상세에는 DB·Redis
연결 상태가 담겨 내부 구성이 드러난다. **8081 차단이 확인된 뒤에** `always`로 올린다.

### AI 호출 기록

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `AI_CALL_LOG_RETENTION_DAYS` | `30` | 호출 기록 보관 기간 |
| `AI_CALL_LOG_CLEANUP_ENABLED` | `true` | 정리 배치 on/off |
| `AI_CALL_LOG_CLEANUP_CRON` | `0 0 4 * * *` | 정리 배치 실행 시각 |

응답 본문을 통째로 저장하므로 행이 크다. 보관 기간을 늘리려면 테이블 증가 속도를
같이 봐야 한다.

### 추천

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `RECOMMENDATION_VECTOR_BACKFILL` | `false` | 기동 시 벡터 백필 여부 |

**켜면 벡터가 없는 상품 수만큼 임베딩을 호출한다(유료).** 이미 만들어진 벡터는 건너뛰므로
한 번 채운 뒤에는 켜둬도 추가 비용이 없다.

### Vision

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `VISION_DETAIL_SILHOUETTE` | `low` | 1단계(전체 형태) 이미지 해상도 |
| `VISION_DETAIL_LABEL` | `high` | 2단계(라벨/로고) 해상도 |
| `VISION_DETAIL_CONDITION` | `high` | 3단계(오염/마모) 해상도 |

해상도는 정확도와 비용을 맞바꾸는 값이라 재배포 없이 조정할 수 있게 빼뒀다.
측정 근거는 `docs/vision-agent.md` 참고.

## 지표 확인 방법

8081을 외부에서 막으므로, 지표를 보려면 인스턴스 안에서 접근해야 한다.

**EC2에 접속해 직접 호출**

```bash
curl -s localhost:8081/actuator/metrics | jq '.names[] | select(startswith("ai."))'
curl -s localhost:8081/actuator/metrics/ai.calls | jq
curl -s localhost:8081/actuator/metrics/ai.call.latency | jq
```

**로컬에서 보려면 SSH 터널**

```bash
ssh -L 8081:localhost:8081 <user>@<EC2>
# 이후 로컬 브라우저에서 http://localhost:8081/actuator/metrics
```

**노출되는 AI 지표**

| 지표 | 태그 | 내용 |
| --- | --- | --- |
| `ai.calls` | type, stage, prompt_version, outcome | 호출 수 (성공/실패 사유별) |
| `ai.call.latency` | type, stage, prompt_version | 응답 시간 분포 |
| `ai.tokens` | type, stage, prompt_version | 토큰 누적 |

`prompt_version` 태그로 쪼개면 "v2로 바꾼 뒤 label 단계가 느려졌나" 같은 질문에 답할 수 있다.

## 시계열이 필요하면

`/actuator/metrics`는 **현재 값만** 보여준다. 추세를 보려면 수집기가 필요한데 아직 도입하지 않았다.

당장은 같은 데이터가 `ai_call_logs` 테이블에 보관 기간만큼 쌓이므로 SQL로 집계할 수 있다.

```sql
-- 최근 7일 프롬프트 버전별 실패율과 토큰
SELECT prompt_version, stage,
       COUNT(*)                                   AS calls,
       SUM(CASE WHEN success THEN 0 ELSE 1 END)   AS failures,
       ROUND(AVG(latency_ms))                     AS avg_latency_ms,
       SUM(prompt_tokens + completion_tokens)     AS tokens
FROM ai_call_logs
WHERE created_at >= NOW() - INTERVAL 7 DAY
GROUP BY prompt_version, stage;

-- 특정 분석의 단계별 소요 시간
SELECT stage, latency_ms, prompt_tokens + completion_tokens AS tokens, success
FROM ai_call_logs
WHERE analysis_id = ?
ORDER BY created_at;
```

두 쿼리 모두 인덱스를 탄다(`idx_ai_call_prompt_version`, `idx_ai_call_analysis`).

## 차단되어 있는 엔드포인트

아래는 노출 목록에 없어 접근되지 않는다. 설정을 바꿀 때 **다시 열리지 않도록 주의한다.**

| 엔드포인트 | 열리면 생기는 문제 |
| --- | --- |
| `/actuator/env` | 환경변수를 그대로 반환 — **API 키가 읽힌다** |
| `/actuator/configprops` | 설정값을 그대로 반환 — 같은 문제 |
| `/actuator/beans` | 내부 구조 노출 |
| `/actuator/heapdump` | 힙 덤프 다운로드 — 메모리상의 모든 값 |

`MANAGEMENT_ENDPOINTS`에 `*`를 넣으면 이 넷이 전부 열린다. **`*`는 쓰지 않는다.**

## 배포 체크리스트

- [ ] 보안그룹에서 8081 외부 인바운드 차단
- [ ] `MANAGEMENT_ENDPOINTS`가 `*`가 아닌지 확인
- [ ] 외부에서 `http://<EC2>:8081/actuator/metrics`가 안 닿는지 확인
- [ ] 외부에서 `http://<EC2>:8080/api/...`는 정상인지 확인
- [ ] 로드밸런서가 있다면 헬스체크 대상 포트를 8081로 지정 (또는 `MANAGEMENT_PORT`를 비워 8080 통합)
- [ ] 8081 차단 확인 후 `MANAGEMENT_HEALTH_DETAILS=always`로 올릴지 결정
