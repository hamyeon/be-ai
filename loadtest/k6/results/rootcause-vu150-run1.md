# rootcause - vu150-run1

- bid_system_failure(k6 카운트) = 41
- backend-live.log 슬라이스 존재 여부 = YES
- k6-console 로그에서 파싱된 SYS_FAIL 라인 수 = 41

## httpStatus / errorCodeK6(=k6 net-level 실패 사유 코드) 분포
httpStatus 분포:
  - status=0: 41건
res.error / res.error_code 분포 (k6이 net-level에서 직접 분류한 실패 사유):
  - 1212 (dial: connection refused): 41건

SYS_FAIL 요청들의 durationMs 분포: min=0.0 med=0.0 max=0.0
(HikariCP connectionTimeout=30000ms 근처에 몰려 있으면 pool 고갈 대기 후 실패했다는 뜻이고, 0ms대에 몰려 있으면 즉시 거부/리셋된 것이라 pool 대기와는 다른 원인이다 - 실제 값은 아래를 보고 판단, 추정하지 않는다.)

## backend-live.log 시간대 상관분석 (±1000ms 윈도우, request 단위 매칭 아님)
- SYS_FAIL 시각 ±1000ms 안에 backend-live.log 라인이 존재: 0건
- SYS_FAIL 시각 ±1000ms 안에 backend-live.log 라인이 전혀 없음: 41건 (서버 프로세스가 이 요청의 흔적을 전혀 안 남겼다는 뜻 - JVM 도달 이전 실패 가능성)

backend-live.log 이번 run 구간에서 printStackTrace() 형태의 스택트레이스를 찾지 못함.
주의: 40909(PessimisticLockingFailureException)는 GlobalExceptionHandler가 어떤 로그도 남기지 않으므로(소스 확인됨),
system_failure 중 lock-related(40909) 비중이 크면 이 결과는 "원인 불명"이 아니라 "애초에 로그가 없다"는 뜻이다.
실제 DB 레벨 lock wait timeout(1205)/deadlock(1213) 여부는 같은 run의 observability-*.json의 mysqlLockWaitTimeout1205Delta/mysqlDeadlock1213Delta를 봐라.