# 2순위 가격(KREAM/eBay)의 당근 실거래 예측력을 측정한다 (#89).
#
# 질문: 당근 중고 시세(ground truth)를 가장 잘 맞추는 조합은?
#   A) KREAM 중앙값 x ALL계수(0.444)          - "새제품가 x 실측 감가율"
#   B) (0.7 KREAM + 0.3 eBay) x ALL계수        - 현행 공식
#   C) eBay 중앙값 그대로                       - eBay가 이미 중고 호가라면 계수 불필요
import csv
import re
import statistics
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "backend" / "src" / "main" / "resources" / "data"
ALL_RATE = 0.444


def norm(s):
    return re.sub(r"[^a-z0-9]", "", (s or "").lower())


def load_used():
    out = {}
    with (DATA / "used_market_prices.csv").open(encoding="utf-8") as f:
        for r in csv.DictReader(f):
            out[norm(r["model_key"])] = (r["brand"], r["model_display"],
                                         int(r["median_price"]), int(r["listing_count"]))
    return out


def load_ref(path, model_col, price_col):
    prices = defaultdict(list)
    with path.open(encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            m = norm(r.get(model_col))
            digits = re.sub(r"[^0-9]", "", r.get(price_col) or "")
            if m and digits:
                prices[m].append(int(digits))
    return {m: int(statistics.median(v)) for m, v in prices.items()}


def match(refs, used_key):
    for m, price in refs.items():
        if m and (m in used_key or used_key in m):
            return price
    return None


def main():
    used = load_used()
    kream = load_ref(DATA / "kream_normalized.csv", "모델명", "KREAM 가격")
    ebay = load_ref(DATA / "ebay_normalized.csv", "model", "ebay_price_krw")

    rows = []
    for key, (brand, display, truth, n) in used.items():
        k = match(kream, key)
        e = match(ebay, key)
        rows.append((display, n, truth, k, e))

    print(f"{'모델':<22}{'당근n':>5}{'당근중앙값':>10}{'KREAM':>9}{'eBay':>9}"
          f"{'A:K×.44':>9}{'B:혼합×.44':>10}{'C:eBay그대로':>11}")
    errs = {"A": [], "B": [], "C": []}
    for display, n, truth, k, e in sorted(rows, key=lambda r: -r[1]):
        a = int(k * ALL_RATE) if k else None
        b = int((0.7 * k + 0.3 * e) * ALL_RATE) if (k and e) else None
        c = e
        def pct(p):
            return f"{(p - truth) / truth * 100:+.0f}%" if p else "-"
        if k or e:
            print(f"{display:<22}{n:>5}{truth:>10,}{k or 0:>9,}{e or 0:>9,}"
                  f"{pct(a):>9}{pct(b):>10}{pct(c):>11}")
        if a:
            errs["A"].append(abs(a - truth) / truth)
        if b:
            errs["B"].append(abs(b - truth) / truth)
        if c:
            errs["C"].append(abs(c - truth) / truth)

    print("\n[모델별 절대 오차율 중앙값]")
    for name, label in (("A", "KREAM x 0.444"), ("B", "혼합(0.7/0.3) x 0.444 (현행)"),
                        ("C", "eBay 그대로")):
        v = errs[name]
        if v:
            print(f"  {label:<28} n={len(v):>2}  {statistics.median(v) * 100:.0f}%")


if __name__ == "__main__":
    main()
