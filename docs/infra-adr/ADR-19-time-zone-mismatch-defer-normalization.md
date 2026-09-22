# ADR-19. 애플리케이션·JVM·DB의 시각 기준 불일치

## Decision
이번 스프린트에서는 코드와 설정을 바꾸지 않고, 현상·영향·선택지를 기록한다.
운영 전 정식 결정과 데이터 이행 계획이 필요하다.

## Problem
실험 환경의 시각 설정이 계층마다 다르다.
| 계층 | 설정 |
|---|---|
| 앱 Clock 빈 | Asia/Seoul (ClockConfig) |
| 컨테이너 JVM 기본 시간대 | UTC (TZ 미설정, java -jar app.jar) |
| JDBC | serverTimezone=Asia/Seoul |
| RDS MySQL | UTC |
| 로컬 테스트 / CI | JVM이 Asia/Seoul (Windows 기본, 워크플로 TZ=Asia/Seoul) |

## 실측 (Day 11, Day 12 동일)
- 시간대 probe: end_at을 세 가지 해석으로 넣은 경매 중 어떤 것이 종료되는지
  실제 Scheduler로 관찰 → 앱이 쓰는 LocalDateTime이 DB에 +9시간으로 저장됨을 확인.
- 같은 DB 안에서 컬럼마다 기준이 다르다.
  | 컬럼 | 앱의 값 출처 | DB에 보이는 값 |
  |---|---|---|
  | auctions.end_at | Clock (KST) | KST + 9h (UTC+18처럼 보임) |
  | bids.created_at | LocalDateTime.now() (JVM 기본 = UTC) | UTC + 9h (KST처럼 보임) |
- 앱 안에서는 쓰기와 읽기의 변환이 대칭이라 동작은 정상이다(마감·입찰 판정 모두 정상).

## 원인 (추정, 추가 확인 필요)
JVM 기본 시간대(UTC)와 JDBC 연결 시간대(Asia/Seoul)가 달라,
LocalDateTime 바인딩 과정에서 드라이버가 시간대 변환을 적용하는 것으로 보인다.
정확한 경로(Hibernate 바인딩 방식, Connector/J 옵션)는 재현 테스트로 확인하지 않았다.

## 영향
- SQL로 직접 조회하거나 컬럼끼리 비교하면 9시간씩 어긋난다
  (정산 배치, 데이터 분석, 수동 장애 조사).
- 테스트 환경(JVM KST)과 운영 컨테이너(JVM UTC)의 조건이 달라,
  시간대 관련 결함이 테스트에서 드러나지 않을 수 있다.
- 코드 곳곳에 Clock 대신 LocalDateTime.now()를 쓰는 지점이 있어 컬럼마다 기준이 갈린다.

## Alternatives
1. 현상 유지 + 문서화 (이번 스프린트)
2. 컨테이너 JVM 시간대를 Asia/Seoul로 설정 (TZ 또는 -Duser.timezone)
    - 장점: 설정 한 줄, 로컬/CI와 조건 일치
    - 단점: 이미 저장된 시각 데이터의 해석이 바뀐다 → 데이터 이행 필요
3. 전 계층 UTC 통일 (Clock·JDBC·DB), 표시 시점에만 KST 변환
    - 장점: 표준적, 서머타임·다중 지역에 안전
    - 단점: 마감 판정 등 Clock에 의존하는 도메인 로직과 테스트 전반 수정
4. hibernate.jdbc.time_zone 등 ORM 수준 명시

## Choice
이번 스프린트는 1번. 운영 전 2번 또는 3번을 데이터 이행 계획과 함께 결정한다.

## Reason
- 앱 동작은 현재 정상이고, 변경 시 기존 데이터 해석이 바뀌어 영향 범위가 넓다.
- Dockerfile·JDBC 설정은 팀 공유 코드라 인프라 실험 범위를 넘는다.

## Residual Risk
- 직접 SQL 비교 시 9시간 오차. 실험 스크립트는 오프셋을 측정해 보정했다
  (bids_created_at_utc_offset_h=9, end_at_utc_offset_h=18).
- 운영에서만 드러나는 시간대 결함 가능성.

## Future
- CI에 JVM UTC 조건 테스트를 추가해 운영 조건과 맞춘다.
- LocalDateTime.now() 직접 호출을 주입된 Clock으로 통일한다.
- 선택지 2 또는 3을 결정하고 기존 데이터 이행 스크립트를 준비한다.