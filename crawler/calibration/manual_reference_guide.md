# KREAM 참조 시세 수동 보강 가이드

크롤러가 소프트 차단으로 막혀 있어(2026-09-02 재확인, `docs/troubleshooting.md` 4번),
참조 시세는 브라우저로 직접 보고 옮긴다. KREAM이 프로그램 접근을 명확히 막고 있으므로
탐지를 우회하는 방향으로는 가지 않는다 — 사람이 앱/웹에서 보는 것은 정상 사용이다.

## 왜 이 작업인가

가격 계수(`#61`)의 병목은 당근 데이터(22,543건)가 아니라 **참조 커버리지(13개 모델)**다.
당근 제목을 분석해 "참조에 없어서 버려지는 모델"을 빈도순으로 뽑았다. 아래 18개만
추가하면 **약 822건이 새로 매칭**된다(현재 643건).

## 우선순위 (당근 매물 빈도순)

| 순위 | 브랜드 | 모델 | 당근 매물 | 표준 컬러웨이 예시 |
| --- | --- | --- | --- | --- |
| 1 | Adidas | Superstar | 167 | Core White |
| 2 | New Balance | 530 | 72 | White Silver |
| 3 | Nike | Cortez | 59 | White/Varsity Red |
| 4 | New Balance | 574 | 53 | Grey |
| 5 | Dr. Martens | 1461 | 50 | Black Smooth |
| 6 | Nike | Air Max 97 | 48 | Silver Bullet 제외, 일반 Triple White 등 |
| 7 | New Balance | 327 | 44 | White |
| 8 | Nike | Blazer Mid 77 | 43 | White Black |
| 9 | Nike | Vomero 5 | 37 | 일반 상시 컬러 |
| 10 | Adidas | Handball Spezial | 36 | Light Blue |
| 11 | New Balance | 1906R | 36 | Silver |
| 12 | Nike | Pegasus 41 | 36 | 일반 상시 컬러 |
| 13 | Nike | Dunk High | 31 | Panda |
| 14 | Nike | Air Max 90 | 26 | White |
| 15 | Nike | V2K Run | 23 | Summit White |
| 16 | Adidas | Stan Smith | 21 | White Green |
| 17 | Converse | Chuck 70 High | 20 | Black |
| 18 | Dr. Martens | 1460 | 20 | Black Smooth |

## 옮길 때 규칙 (지난 산출에서 배운 것)

1. **표준(상시 판매) 컬러웨이를 고른다.** 한정판을 고르면 비율이 왜곡된다 —
   Air Max 95를 Neon으로 넣었다가 비율 0.14가 나온 게 그 사례다
   (`reference_quality.py`). 그 모델의 가장 흔한 기본 컬러를 쓴다.
2. **성인 사이즈만.** 240~280에서 3~5개 사이즈. Samba OG를 아동 사이즈로 넣었다가
   전부 버려진 사례가 있다.
3. **체결 거래 가격을 쓴다.** 판매 호가(팔고 싶은 값)가 아니라 시세 탭의 최근
   체결가. `가격 유형` 칸에 `체결 거래`라고 적는다.
4. **사이즈는 숫자만.** `270`처럼. `200(US 1.5)` 같은 병기는 파서가 걸러내지만
   애초에 안 만드는 게 낫다.

## 입력 방법

`crawler/calibration/manual_kream_input.csv`에 한 줄씩 채운다. 형식은 기존
`kream_normalized.csv`와 같다. 다 채우면(일부만이라도) 말해주면:

1. 기존 CSV와 병합 (Claude)
2. `model_aliases.py`에 한글 별칭 추가 (Claude)
3. `build_condition_rates.py` 재실행 → 계수 갱신 (Claude)
4. 전후 비교 수치와 함께 커밋 (Claude)

모델 하나당 5분 정도 걸린다고 보면, 상위 6개만 해도 약 30분에 449건이 열린다.
