# KREAM 체결가에서 색상 프리미엄 계수를 산출한다 (#93).
#
# 왜 KREAM인가: KREAM은 상품 = 컬러웨이 구조라 색상별 체결가가 원래 형태다.
# 당근은 매물을 색으로 쪼개면 표본이 부서지는데, KREAM은 상품 페이지 하나가
# 그 컬러의 체결 5건을 통째로 준다.
#
# 산출: premium(모델, 색계열) = 그 색 체결 중앙값 / 그 모델 전체 체결 중앙값
# 쓰임: 당근에 (모델, 색상) 버킷이 없을 때, 당근 모델 시세 x 이 프리미엄으로
# 색상을 반영한다. "새제품 시장에서 이 색이 비싸면 중고에서도 비싸다"는 가정이며,
# 당근 색상 버킷이 있는 모델에서 교차 검증한다.
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from build_used_market_prices import find_model  # noqa: E402
from color_families import families  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
PRODUCTS = ROOT / "crawler" / "data" / "kream_products.jsonl"
TRADES = ROOT / "crawler" / "data" / "kream_trades.jsonl"
OUT = ROOT / "backend" / "src" / "main" / "resources" / "data" / "kream_color_premiums.csv"

# 체결 5건(상품 페이지 한 번)이 최소 단위다. 그보다 적으면 버킷을 만들지 않는다.
MIN_TRADES = 5
# 별개 모델이 섞이면 프리미엄이 아니라 모델 차이를 재게 된다 (merge_kream_crawl과 동일)
EXCLUDE_PRODUCT_IDS = {406041, 34573, 369, 329970}  # 슈퍼스타2(별도 라인), 앰부쉬 덩크(콜라보), SB 하이네켄(콜라보 한정), 스탠스미스 데콘(변형 라인)


def color_key(name):
    fams = families(name)
    if not fams or len(fams) > 2:
        return None
    return "+".join(sorted(fams))


def main():
    products = {}
    with PRODUCTS.open(encoding="utf-8") as f:
        for line in f:
            p = json.loads(line)
            products[p["product_id"]] = p

    trades_by_product = defaultdict(list)
    with TRADES.open(encoding="utf-8") as f:
        for line in f:
            t = json.loads(line)
            try:
                trades_by_product[t["product_id"]].append(int(t["price_krw"]))
            except (TypeError, ValueError, KeyError):
                continue

    # (모델, 색계열) -> 체결가들 / 모델 -> 전체 체결가들
    by_model_color = defaultdict(list)
    by_model = defaultdict(list)
    for pid, p in products.items():
        if pid in EXCLUDE_PRODUCT_IDS:
            continue
        name = p.get("name_ko") or p.get("name_en") or ""
        # (W) 여성판은 제외한다. 남녀판 가격차가 색상 차이보다 커서, 섞으면
        # 색상 프리미엄이 아니라 성별 가격차를 재게 된다(코르테즈 W 0.73 실측).
        if name.strip().lower().startswith("(w)"):
            continue
        found = find_model(name)
        if not found:
            continue
        key = color_key(name)
        prices = trades_by_product.get(pid, [])
        if not prices:
            continue
        brand, model_key = found
        by_model[(brand, model_key)].extend(prices)
        if key:
            by_model_color[(brand, model_key, key)].extend(prices)

    lines = ["brand,model_key,color_family,premium,trade_count,color_median,model_median"]
    kept = 0
    print(f"{'모델':<14}{'색계열':<14}{'체결':>4}{'색 중앙값':>10}{'모델 중앙값':>10}{'프리미엄':>8}")
    for (brand, model_key, color), prices in sorted(by_model_color.items()):
        model_prices = by_model[(brand, model_key)]
        # 색이 하나뿐인 모델은 프리미엄이 정의상 1.0이라 내보낼 정보가 없다
        distinct_colors = {c for (b, m, c) in by_model_color if (b, m) == (brand, model_key)}
        if len(prices) < MIN_TRADES or len(distinct_colors) < 2:
            continue
        color_median = statistics.median(prices)
        model_median = statistics.median(model_prices)
        premium = round(color_median / model_median, 3)
        lines.append(f"{brand},{model_key},{color},{premium},{len(prices)},"
                     f"{int(color_median)},{int(model_median)}")
        kept += 1
        print(f"{model_key:<14}{color:<14}{len(prices):>4}{int(color_median):>10,}"
              f"{int(model_median):>10,}{premium:>8.2f}")

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\n{OUT.relative_to(ROOT)} 작성 - 프리미엄 버킷 {kept}개")


if __name__ == "__main__":
    main()
