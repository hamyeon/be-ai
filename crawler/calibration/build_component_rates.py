# 당근 실거래 매물로 구성품(박스·더스트백·여분끈) 계수를 산출한다.
#
# 왜 필요한가:
#   PriceCalculationService의 구성품 계수(FULL 1.00 / PARTIAL 0.97 / NONE 0.95)에
#   근거가 없다. 상태 등급은 #61에서 실측으로 교체했지만 구성품은 그대로 남았다.
#
# 방법론에서 주의한 것 - 전부 실측에서 문제가 드러나 고친 것들이다:
#
#   1) KREAM을 분모로 쓰지 않는다. 상태 계수(build_condition_rates.py)는 "중고 ÷ 정가"라
#      KREAM 참조가 필요했지만, 구성품 계수는 "중고 ÷ 중고"다. KREAM을 끼우면 참조가
#      있는 모델로 표본이 46,146건 -> 1,884건으로 줄어드는데 얻는 것이 없다.
#      같은 셀 안에서 나누므로 모델·브랜드의 가격 수준은 어차피 약분된다.
#
#   2) 상태 등급을 통제한다. 통제 없이 재면 무박스/풀박스 중앙값비가 0.77로 나오는데
#      이는 구성품 값이 아니라 상태 값이다. 박스 있는 매물은 45%가 DS(새상품)인데
#      박스 없는 매물은 19%다. 통제하지 않으면 상태 계수를 두 번 곱하게 된다.
#
#   3) 셀은 (브랜드, 상태등급)이다. 모델 단위가 이상적이지만 model_aliases가 잡는 매물이
#      46,146건 중 3,437건(7.5%)뿐이라 셀이 서지 않는다(모델 셀로는 FULL이 7셀 70건).
#      브랜드로 완화해도 셀 안에서 정규화하므로 브랜드 간 가격 수준 차이는 약분된다.
#      실측: 모델 셀·브랜드 셀·등급만 세 방법의 결과가 NONE 0.90~0.91로 일치했다.
#
#   4) 기준선은 "그 셀에서 구성품이 판정된 매물 전체"다. 전체 매물(미판정 포함)을
#      기준선으로 쓰면 NONE이 1.26으로 나온다 - 구성품을 설명에 적은 매물은 애초에
#      비싼 매물이기 때문이다(판정군 비율 중앙값 0.506 vs 미판정군 0.333).
#      그래서 이 계수는 "구성품 상태 간 상대 보정"이지 가격 수준을 올리는 값이 아니다.
#      미판정 매물의 구성품 분포는 알 수 없으므로 수준은 건드리지 않는다.
import hashlib
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from listing_filters import exclusion_reason  # noqa: E402
from fine_condition import classify as classify_condition  # noqa: E402
from component_status import classify as classify_component  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
DAANGN = ROOT / "crawler" / "output" / "daangn_shoes_raw.jsonl"
OUT = ROOT / "backend" / "src" / "main" / "resources" / "data" / "component_rates.csv"

PRICE_MIN, PRICE_MAX = 10_000, 3_000_000
STATUSES = ("FULL", "PARTIAL", "NONE")

# 셀(브랜드x등급) 하나가 계수에 기여하려면 이만큼은 있어야 한다.
MIN_CELL = 30
MIN_PER_STATUS = 8

# 최종 계수로 내보내려면 구성품 상태 하나당(셀 합산) 이만큼은 필요하다.
MIN_SAMPLE_TOTAL = 200

# 셀별 계수가 이보다 흩어지면 채택하지 않는다.
#
# 표본 수만으로는 부족하다. FULL은 26셀 778건으로 표본 기준을 넉넉히 넘기지만
# 셀별 값이 0.76~2.27로 흩어져 있다(상대IQR 0.394). 그 중앙값 1.379를 계수로 쓰면
# 풀박스 매물 추천가가 38% 오르는데, 그 숫자를 우리가 설명할 수 없다.
# PARTIAL(0.151)·NONE(0.215)은 이 기준을 통과한다.
MAX_RELATIVE_IQR = 0.30

# 구성품이 많을수록 비싸야 한다. 서열이 뒤집히면 표본 노이즈로 보고 버린다.
# 상태 계수의 GRADE_ORDER 가드와 같은 원칙이다.
STATUS_ORDER = ["FULL", "PARTIAL", "NONE"]

NOTE = "당근 실거래 중앙값 / 같은 (브랜드·상태등급) 셀의 판정 매물 중앙값"


def collect():
    """(브랜드, 상태등급) 셀별로 구성품 상태와 가격을 모은다."""
    cells = defaultdict(list)
    seen = classified = no_brand = 0

    with DAANGN.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                d = json.loads(line)
            except json.JSONDecodeError:
                continue
            blob = f"{d.get('item_title') or ''} {d.get('description') or ''}"
            try:
                price = int(d.get("price_krw") or 0)
            except (TypeError, ValueError):
                continue
            # 신발 한 켤레 가격으로 볼 수 있는 범위. 부품·사은품·오타를 제외한다.
            if not (PRICE_MIN <= price <= PRICE_MAX):
                continue
            # 협업·묶음·아동용은 같은 셀 안에서도 가격대가 달라 중앙값을 흔든다.
            if exclusion_reason(blob):
                continue
            seen += 1

            brand = d.get("brand_guess")
            if not brand:
                no_brand += 1
                continue
            grade = d.get("condition_grade_guess") or "UNKNOWN"
            # 크롤링 시점 guess는 새상품 신호(DS/S)만 잡고 나머지를 UNKNOWN으로 뭉갠다.
            if grade == "UNKNOWN":
                grade = classify_condition(blob) or "UNKNOWN"
            status = classify_component(blob)
            if status is None:
                continue
            classified += 1
            # 분할 검증용 꼬리표. 매물 URL 해시라 실행할 때마다 같은 편에 들어간다.
            half = hash_half(d.get("item_url") or blob)
            cells[(brand, grade)].append((status, price, half))

    return cells, seen, classified, no_brand


def hash_half(key):
    """매물을 두 편으로 가르는 결정적 해시. random을 쓰면 실행마다 결과가 달라진다."""
    return hashlib.md5(key.encode("utf-8")).digest()[0] % 2


def factors_from(cells, half=None):
    """셀별 계수와 표본 수를 낸다. half를 주면 그 편의 매물만 쓴다."""
    factors = defaultdict(list)
    samples = defaultdict(int)
    per_cell = {}
    for key, rows in cells.items():
        values = [r for r in rows if half is None or r[2] == half]
        if len(values) < MIN_CELL:
            continue
        base = statistics.median([v[1] for v in values])
        cell_row = {}
        for status in STATUSES:
            prices = [v[1] for v in values if v[0] == status]
            if len(prices) < MIN_PER_STATUS:
                cell_row[status] = (None, len(prices))
                continue
            factor = statistics.median(prices) / base
            factors[status].append(factor)
            samples[status] += len(prices)
            cell_row[status] = (factor, len(prices))
        per_cell[key] = (len(values), cell_row)
    return factors, samples, per_cell


def validate(cells):
    """표본을 반으로 갈라 각각 산출한다.

    두 편의 값이 크게 다르면 그 계수는 표본에 우연히 실린 값이다. 채택 기준(표본 수,
    셀별 편차)은 한 표본 안에서만 보는 검사라, 다른 표본에서 재현되는지는 따로 봐야 한다.
    가르는 기준이 랜덤이면 실행마다 결론이 달라지므로 매물 URL 해시로 고정한다.
    """
    print("\n[분할 검증]  표본을 절반씩 나눠 각각 산출")
    halves = [factors_from(cells, half) for half in (0, 1)]
    for status in STATUSES:
        parts = []
        for factors, samples, _ in halves:
            values = factors[status]
            parts.append(f"{statistics.median(values):.3f}(셀{len(values)},n{samples[status]})"
                         if values else "셀 부족")
        medians = [statistics.median(f[status]) for f, _, _ in halves if f[status]]
        gap = f"  차이 {abs(medians[0] - medians[1]):.3f}" if len(medians) == 2 else ""
        print(f"  {status:<8} A={parts[0]}  B={parts[1]}{gap}")


def relative_iqr(values):
    ordered = sorted(values)
    n = len(ordered)
    q1 = statistics.median(ordered[: n // 2])
    q3 = statistics.median(ordered[(n + 1) // 2:])
    return (q3 - q1) / statistics.median(ordered)


def main():
    cells, seen, classified, no_brand = collect()
    print(f"필터 통과 {seen}건 / 브랜드 미상으로 제외 {no_brand}건 / 구성품 판정 {classified}건")

    factors, samples, per_cell = factors_from(cells)

    print(f"\n[셀별 계수]  셀 최소 {MIN_CELL}건, 상태별 최소 {MIN_PER_STATUS}건")
    for key, (size, cell_row) in sorted(per_cell.items(), key=lambda kv: -kv[1][0]):
        row = []
        for status in STATUSES:
            factor, count = cell_row[status]
            row.append(f"{status}=n{count}" if factor is None
                       else f"{status}={factor:.3f}(n={count})")
        brand, grade = key
        print(f"  {brand:<12} {grade:<8} n={size:>4}  " + "  ".join(row))
    print(f"  -> 사용된 셀 {len(per_cell)}개")

    validate(cells)

    print("\n[채택 판정]")
    measured = {}
    for status in STATUSES:
        values = factors[status]
        if not values:
            print(f"  {status:<8} 셀이 하나도 안 서서 제외, 기본값 유지")
            continue
        median = statistics.median(values)
        spread = relative_iqr(values)
        if samples[status] < MIN_SAMPLE_TOTAL:
            print(f"  {status:<8} 표본 부족 (n={samples[status]} < {MIN_SAMPLE_TOTAL}), 기본값 유지")
            continue
        if spread > MAX_RELATIVE_IQR:
            print(f"  {status:<8} 셀별 편차 과다 (상대IQR {spread:.3f} > {MAX_RELATIVE_IQR}, "
                  f"중앙값 {median:.3f}, 범위 {min(values):.2f}~{max(values):.2f}), 기본값 유지")
            continue
        print(f"  {status:<8} 채택  {median:.3f}  (셀 {len(values)}개, n={samples[status]}, "
              f"상대IQR {spread:.3f})")
        measured[status] = (round(median, 3), samples[status])

    # 서열 가드: FULL >= PARTIAL >= NONE
    previous = None
    for status in STATUS_ORDER:
        if status not in measured:
            # 채택되지 않은 상태는 기본값(FULL 1.00 / PARTIAL 0.97 / NONE 0.95)으로
            # 떨어진다. 서열 비교의 기준으로 쓰면 측정값과 기본값을 섞게 되므로 건너뛴다.
            continue
        rate = measured[status][0]
        if previous is not None and rate > previous:
            print(f"  {status:<8} 서열 역전({rate:.3f} > {previous:.3f})으로 제외, 기본값 유지")
            del measured[status]
            continue
        previous = rate

    write_csv(measured, classified)


def write_csv(measured, classified):
    """실측 계수를 CSV로 내보낸다.

    rate는 "같은 셀에서 구성품이 판정된 매물 전체 대비 몇 배"다. 절대 계수가 아니다.
    중고 시세 경로는 이 값을 그대로 곱하고, KREAM 경로는 FULL로 나눠 쓴다
    (KREAM은 새제품이라 기준 모집단이 풀박스다).
    """
    print(f"\n{OUT.relative_to(ROOT)} 작성")
    if not measured:
        print("  실측 계수 없음 - CSV를 쓰지 않는다(백엔드는 기본값으로 동작)")
        return
    lines = ["component_status,rate,sample_size,source_note",
             f"ALL,1.0,{classified},{NOTE}"]
    print(f"    {'ALL':<8} 1.000  (n={classified}, 기준선)")
    for status in STATUS_ORDER:
        if status not in measured:
            print(f"    {status:<8} 미채택 - 백엔드 기본값 사용")
            continue
        rate, n = measured[status]
        lines.append(f"{status},{rate},{n},{NOTE}")
        print(f"    {status:<8} {rate:.3f}  (n={n})")
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
