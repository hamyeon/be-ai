# 희소성 <-> 가격 프리미엄 상관 검증 (#93).
#
# 질문: "매물이 드문 색일수록 실제로 비싼가?"
# #27에서 희소성 지표(매물 수)를 만들고도 가격 공식에 넣지 않은 이유가
# "근거 없이 반영하면 안 된다"였다. 색상 버킷이 생겨 잴 수 있게 됐으니 잰다.
#
# 방법:
#   희소성 = 그 색 매물 수 / 그 모델 전체 매물 수 (점유율이 낮을수록 희소)
#   프리미엄 = 그 색 중앙값 / 그 모델 중앙값
#   (모델, 색) 버킷들에서 점유율과 프리미엄의 스피어만 순위상관을 본다.
#
# 알려진 한계(결과 해석 시 명심):
#   표본 5건 미만의 진짜 희소 컬러는 버킷 자체가 없어 분석에 안 보인다.
#   즉 이 측정은 "관측 가능한 범위 안에서의" 희소성-가격 관계다.
import statistics
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from build_used_market_prices import find_model, iter_listings, PRICE_MIN, PRICE_MAX, color_key  # noqa: E402
from listing_filters import exclusion_reason  # noqa: E402

# 가격용 기준(10)보다 낮게 잡는다 - 측정은 표본을 넓게 보고, 가격에는 안 쓴다
MIN_BUCKET = 5


def spearman(xs, ys):
    def ranks(vs):
        order = sorted(range(len(vs)), key=lambda i: vs[i])
        r = [0.0] * len(vs)
        i = 0
        while i < len(order):
            j = i
            while j + 1 < len(order) and vs[order[j + 1]] == vs[order[i]]:
                j += 1
            avg = (i + j) / 2 + 1
            for k in range(i, j + 1):
                r[order[k]] = avg
            i = j + 1
        return r
    rx, ry = ranks(xs), ranks(ys)
    mx, my = statistics.mean(rx), statistics.mean(ry)
    num = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
    den = (sum((a - mx) ** 2 for a in rx) * sum((b - my) ** 2 for b in ry)) ** 0.5
    return num / den if den else 0.0


def main():
    color_prices = defaultdict(list)
    model_prices = defaultdict(list)

    for source, title, description, raw_price in iter_listings():
        found = find_model(title, description)
        if not found:
            continue
        if exclusion_reason(f"{title or ''} {description or ''}"):
            continue
        try:
            price = int(raw_price or 0)
        except (TypeError, ValueError):
            continue
        if not (PRICE_MIN <= price <= PRICE_MAX):
            continue
        model_prices[found].append(price)
        key = color_key(title)
        if key:
            color_prices[(found, key)].append(price)

    shares, premiums, rows = [], [], []
    for (model, color), prices in color_prices.items():
        if len(prices) < MIN_BUCKET or len(model_prices[model]) < 30:
            continue
        share = len(prices) / len(model_prices[model])
        premium = statistics.median(prices) / statistics.median(model_prices[model])
        shares.append(share)
        premiums.append(premium)
        rows.append((model[1], color, len(prices), share, premium))

    rho = spearman(shares, premiums)
    print(f"버킷 {len(rows)}개 (색 표본 {MIN_BUCKET}건+, 모델 표본 30건+)")
    print(f"점유율 <-> 프리미엄 스피어만 상관: {rho:+.3f}")
    print("  (음수 = 점유율이 낮을수록[희소할수록] 비싸다는 뜻)")

    rows.sort(key=lambda r: r[3])
    print(f"\n{'모델':<16}{'색':<14}{'n':>4}{'점유율':>7}{'프리미엄':>8}")
    print("--- 가장 희소한 10개 ---")
    for model, color, n, share, premium in rows[:10]:
        print(f"{model:<16}{color:<14}{n:>4}{share:>6.0%}{premium:>8.2f}")
    print("--- 가장 흔한 10개 ---")
    for model, color, n, share, premium in rows[-10:]:
        print(f"{model:<16}{color:<14}{n:>4}{share:>6.0%}{premium:>8.2f}")


if __name__ == "__main__":
    main()
