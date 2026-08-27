# 당근 실거래 매물과 KREAM 참조 시세를 대조해 "중고/정가" 비율을 산출한다.
#
# 왜 필요한가:
#   PriceCalculationService의 상태 보정 계수(DS 0.80 / S 0.70 / A 0.60 ...)에 근거가 없다.
#   KREAM은 새제품 시세라 거기에 임의 계수를 곱해 중고가를 추정하는 구조인데,
#   그 계수가 측정된 값이 아니다.
#
# 방법론에서 주의한 것:
#   1) 사이즈를 맞춘다. 처음엔 사이즈를 무시하고 모델만 맞췄더니 Samba OG 비율이 0.95로 나왔다.
#      원인은 KREAM 참조의 Samba가 전부 아동화(190~210)였기 때문이다. 성인 중고가를
#      아동 정가로 나눈 값이라 의미가 없었다.
#   2) 참조 행의 사이즈가 정상 범위(150~350)를 벗어나면 버린다. "200(US 1.5)" 같은 표기가
#      숫자만 남기면 20015가 되어 어떤 요청과도 매칭되지 않는다.
#   3) 사이즈를 못 읽은 매물은 모델 전체 중앙값과 비교한다. 표본을 살리되 별도로 표시한다.
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from model_aliases import find_model  # noqa: E402
from reference_quality import quality, reason  # noqa: E402
from listing_filters import exclusion_reason  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
DAANGN = ROOT / "crawler" / "output" / "daangn_shoes_raw.jsonl"
KREAM_CSV = ROOT / "backend" / "src" / "main" / "resources" / "data" / "kream_normalized.csv"

# 한국 신발 사이즈로 볼 수 있는 범위. 아동화까지 포함하되 파싱 오류는 걸러낸다.
SIZE_MIN, SIZE_MAX = 150, 350
# 성인 사이즈. 아동화는 가격대가 완전히 달라 같이 묶으면 비율이 왜곡된다.
ADULT_MIN = 220
# 신발 한 켤레 가격으로 볼 수 있는 범위. 부품·사은품·오타를 제외한다.
PRICE_MIN, PRICE_MAX = 10_000, 3_000_000

SIZE_PATTERN = re.compile(r'(?<!\d)(1[5-9]\d|2[0-9]\d|3[0-4]\d)(?!\d)')


def parse_size(text):
    """제목/설명에서 한국 사이즈를 추출한다. 여러 개면 가장 흔한 신발 사이즈대를 고른다."""
    if not text:
        return None
    found = [int(m) for m in SIZE_PATTERN.findall(text)]
    found = [s for s in found if SIZE_MIN <= s <= SIZE_MAX and s % 5 == 0]
    return found[0] if found else None


def load_kream():
    import csv
    rows = []
    with KREAM_CSV.open(encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            size_raw = (r.get("한국 사이즈") or "").strip()
            digits = re.sub(r'[^0-9]', '', size_raw)
            size = int(digits) if digits else None
            price_digits = re.sub(r'[^0-9]', '', r.get("KREAM 가격") or "")
            price = int(price_digits) if price_digits else None
            model = re.sub(r'[^a-z0-9]', '', (r.get("모델명") or "").lower())
            if not model or price is None:
                continue
            # 사이즈 표기가 깨진 행은 버린다 (예: "200(US 1.5)" -> 20015)
            if size is None or not (SIZE_MIN <= size <= SIZE_MAX):
                rows.append((model, None, price, size_raw))
                continue
            rows.append((model, size, price, size_raw))
    return rows


def main():
    kream_rows = load_kream()
    dropped = [r for r in kream_rows if r[1] is None]
    usable = [r for r in kream_rows if r[1] is not None]
    adult = [r for r in usable if r[1] >= ADULT_MIN]

    print(f"KREAM 참조 {len(kream_rows)}행")
    print(f"  사이즈 표기 오류로 제외 : {len(dropped)}행 "
          f"({', '.join(sorted({r[3] for r in dropped}))})")
    print(f"  아동 사이즈로 제외      : {len(usable) - len(adult)}행")
    print(f"  사용                    : {len(adult)}행")

    by_model_size = defaultdict(list)
    by_model = defaultdict(list)
    for model, size, price, _ in adult:
        by_model_size[(model, size)].append(price)
        by_model[model].append(price)

    ratios_by_cond = defaultdict(list)
    ratios_by_model = defaultdict(list)
    # 참조가 표준 컬러웨이인 모델만 상태별 계수에 반영한다.
    # 한정 컬러 참조를 섞으면 S등급이 UNKNOWN보다 낮게 나오는 역전이 생긴다.
    standard_by_cond = defaultdict(list)
    excluded = defaultdict(int)
    matched = size_matched = 0

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
            model = find_model(blob)
            if not model or model not in by_model:
                continue
            try:
                price = int(d.get("price_krw") or 0)
            except (TypeError, ValueError):
                continue
            if not (PRICE_MIN <= price <= PRICE_MAX):
                continue

            # 분자와 분모가 같은 물건이어야 한다. 협업·묶음·아동용은 그 전제를 깬다.
            excluded_by = exclusion_reason(blob)
            if excluded_by:
                excluded[excluded_by] += 1
                continue

            size = parse_size(blob)
            if size is not None and size >= ADULT_MIN and (model, size) in by_model_size:
                reference = statistics.median(by_model_size[(model, size)])
                size_matched += 1
            else:
                reference = statistics.median(by_model[model])
            matched += 1
            ratio = price / reference
            grade = d.get("condition_grade_guess") or "UNKNOWN"
            ratios_by_cond[grade].append(ratio)
            ratios_by_model[model].append(ratio)
            if quality(model) == "STANDARD":
                standard_by_cond[grade].append(ratio)

    print(f"\n매칭된 당근 매물: {matched}건 (사이즈까지 일치 {size_matched}건)")
    if excluded:
        print("  제외: " + ", ".join(f"{k} {v}건" for k, v in sorted(excluded.items())))

    current = {"DS": 0.80, "S": 0.70, "UNKNOWN": 0.60}

    print(f"\n[전체 참조]   {'상태':<9}{'n':>6}{'중앙값':>10}{'현재 계수':>12}")
    for cond in ("DS", "S", "UNKNOWN"):
        vals = ratios_by_cond.get(cond, [])
        if vals:
            print(f"{'':<14}{cond:<9}{len(vals):>6}{statistics.median(vals):>10.2f}{current[cond]:>12}")

    # 한정 컬러 참조를 섞으면 S가 UNKNOWN보다 낮게 나오는 역전이 생긴다.
    print(f"\n[표준 참조만] {'상태':<9}{'n':>6}{'중앙값':>10}{'현재 계수':>12}")
    for cond in ("DS", "S", "UNKNOWN"):
        vals = standard_by_cond.get(cond, [])
        if vals:
            print(f"{'':<14}{cond:<9}{len(vals):>6}{statistics.median(vals):>10.2f}{current[cond]:>12}")

    print(f"\n{'모델':<24}{'n':>6}{'중앙값':>10}  {'참조품질':<9} 근거")
    trusted = []
    for model, vals in sorted(ratios_by_model.items(), key=lambda kv: -len(kv[1])):
        if len(vals) < 10:
            continue
        q = quality(model)
        if q == "STANDARD":
            trusted.extend(vals)
        print(f"{model:<24}{len(vals):>6}{statistics.median(vals):>10.2f}  {q:<9} {reason(model)[:38]}")

    if trusted:
        print(f"\n참조가 표준 컬러웨이인 모델만: n={len(trusted)}, "
              f"중앙값 {statistics.median(trusted):.2f}")
        print("  -> '일반 중고는 정가의 몇 배인가'에 대한 현재 최선의 추정")

    write_csv(standard_by_cond)


# 표본이 이보다 적으면 계수로 쓰지 않는다.
#
# S등급이 12건인데 그 숫자로 0.70을 0.55로 바꾸면, 근거 없는 계수를 근거 없는 계수로
# 바꾸는 것뿐이다. 표본이 쌓일 때까지는 기존 값을 그대로 두는 편이 정직하다.
MIN_SAMPLE = 50


def write_csv(standard_by_cond):
    """실측 계수만 CSV로 내보낸다. 기준 미달 등급은 넣지 않고 코드 기본값을 쓰게 둔다."""
    out = ROOT / "backend" / "src" / "main" / "resources" / "data" / "condition_rates.csv"
    lines = ["condition_grade,rate,sample_size,source_note"]
    kept, skipped = [], []
    for grade, vals in sorted(standard_by_cond.items()):
        if len(vals) < MIN_SAMPLE:
            skipped.append((grade, len(vals)))
            continue
        rate = round(statistics.median(vals), 3)
        lines.append(f"{grade},{rate},{len(vals)},"
                     f"당근 실거래 중앙값 / KREAM 표준 컬러웨이 참조 중앙값")
        kept.append((grade, rate, len(vals)))

    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\n{out.relative_to(ROOT)} 작성")
    for grade, rate, n in kept:
        print(f"  {grade:<9} {rate:.2f}  (n={n})")
    for grade, n in skipped:
        print(f"  {grade:<9} 제외    (n={n} < {MIN_SAMPLE}, 코드 기본값 유지)")


if __name__ == "__main__":
    main()
