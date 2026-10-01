# 당근·후르츠패밀리 매물에서 모델별 중고 실거래 시세를 산출한다.
#
# 왜 필요한가 (#86):
#   추천가가 "KREAM 새제품가 x 상태계수"로 중고가를 추정하는 구조였다. 우리 서비스는
#   중고 경매인데, 같은 모델의 중고 실거래가 있으면 그게 더 곧은 근거다. 그리고 이
#   방식은 KREAM 참조가 필요 없어서, 참조 커버리지(13개 모델)라는 병목 자체가 사라진다.
#
# build_condition_rates.py(#61)와의 관계:
#   그쪽은 "중고가 / 새제품가" 비율(상태 계수)을 만들고, 이쪽은 중고가 자체를 만든다.
#   별칭 사전을 공유하지 않는 이유: #61 별칭은 KREAM 참조의 정확한 모델명
#   (airforce1low 등)에 묶여 있고, 여기는 참조와 무관한 자체 키를 쓴다.
import json
import io
import statistics
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from listing_filters import exclusion_reason  # noqa: E402
from color_families import families  # noqa: E402
from shoe_models import MODELS, find_model  # noqa: E402
import re  # noqa: E402

# 새상품 신호 키워드 (crawler/config.py CONDITION_DS_KEYWORDS와 같은 계열)
NEW_PAT = re.compile(r"새상품|새제품|미착용|미개봉|택\s*(포함|달림|있)|한\s*번도\s*안\s*신")

ROOT = Path(__file__).resolve().parents[2]
DAANGN = ROOT / "crawler" / "output" / "daangn_shoes_raw.jsonl"
FRUITS = ROOT / "crawler" / "data" / "products.jsonl"
OUT = ROOT / "backend" / "src" / "main" / "resources" / "data" / "used_market_prices.csv"
# 색상별 시세 (#93). 모델 시세와 파일을 나눈 이유: 색상 시세는 있는 버킷만 싣는
# 부분 데이터라, 한 파일에 섞으면 "색상 행이 없는 모델"과 "모델 자체가 없는 것"이
# 구분되지 않는다.
OUT_BY_COLOR = ROOT / "backend" / "src" / "main" / "resources" / "data" / "used_market_prices_by_color.csv"

# 신발 한 켤레 가격으로 볼 수 있는 범위 (부품·사은품·오타 제외)
PRICE_MIN, PRICE_MAX = 10_000, 2_000_000
# 이보다 표본이 적으면 중앙값을 시세라 부르기 어렵다
MIN_LISTINGS = 10

# 모델 표는 shoe_models.py에 있다 - 구성품 계수 산출(build_component_rates.py)도
# 같은 표를 쓴다.

def iter_listings():
    for line in io.open(DAANGN, encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        try:
            d = json.loads(line)
        except json.JSONDecodeError:
            continue
        yield "DAANGN", d.get("item_title"), d.get("description"), d.get("price_krw")
    for line in io.open(FRUITS, encoding="utf-8"):
        d = json.loads(line)
        yield "FRUITS", d.get("item_title"), d.get("description"), d.get("price_krw")


def color_key(title):
    """제목의 색 계열을 안정된 키로 접는다. 1~2계열만 인정 - 3계열 이상은 판독 혼란."""
    fams = families(title)
    if not fams or len(fams) > 2:
        return None
    return "+".join(sorted(fams))


# 색상 버킷의 새상품 비중이 모델 평균에서 이만큼(퍼센트포인트) 벗어나면 버킷을 버린다.
# 실측(#93): DS 편중과 프리미엄의 상관 +0.218 - 편중이 극단인 버킷(올드스쿨 그레이
# DS 60% vs 모델 18%)은 "색상 프리미엄"이 아니라 "상태 구성 차이"를 재고 있었다.
# 30%p는 사람이 정한 선이며, 걸린 버킷은 산출 로그에 이유와 함께 남는다.
MAX_NEW_SHARE_BIAS = 0.30


def main():
    prices = defaultdict(list)
    color_prices = defaultdict(list)
    new_counts = defaultdict(int)         # (모델,색) -> 새상품 키워드 매물 수
    model_new_counts = defaultdict(int)   # 모델 -> 새상품 키워드 매물 수
    sources = defaultdict(lambda: defaultdict(int))
    excluded = defaultdict(int)

    for source, title, description, raw_price in iter_listings():
        found = find_model(title, description)
        if not found:
            continue
        blob = f"{title or ''} {description or ''}"
        reason = exclusion_reason(blob)
        if reason:
            excluded[reason] += 1
            continue
        try:
            price = int(raw_price or 0)
        except (TypeError, ValueError):
            continue
        if not (PRICE_MIN <= price <= PRICE_MAX):
            continue
        prices[found].append(price)
        sources[found][source] += 1
        is_new = bool(NEW_PAT.search(blob))
        if is_new:
            model_new_counts[found] += 1
        # 색상은 제목에서만 읽는다. 설명에는 "검정 끈으로 교체" 같은 노이즈가 많다.
        # 제목에 색이 없는 매물(약 43%)은 색상 시세에서 빠질 뿐, 모델 시세에는 남는다.
        key = color_key(title)
        if key:
            color_prices[(found, key)].append(price)
            if is_new:
                new_counts[(found, key)] += 1

    display = {(b, k): d for b, k, d, _, _ in MODELS}
    lines = ["brand,model_key,model_display,listing_count,median_price,q1_price,q3_price,daangn_count,fruits_count"]
    kept = 0
    for (brand, key), values in sorted(prices.items(), key=lambda kv: -len(kv[1])):
        if len(values) < MIN_LISTINGS:
            continue
        values.sort()
        median = int(statistics.median(values))
        q1, q3 = values[len(values) // 4], values[3 * len(values) // 4]
        lines.append(f"{brand},{key},{display[(brand, key)]},{len(values)},{median},{q1},{q3},"
                     f"{sources[(brand, key)]['DAANGN']},{sources[(brand, key)]['FRUITS']}")
        kept += 1
        print(f"  {brand:<12} {display[(brand, key)]:<18} n={len(values):>4}  "
              f"중앙값 {median:>9,}  IQR {q1:,}~{q3:,}")

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    total = sum(len(v) for (bk, v) in prices.items() if len(v) >= MIN_LISTINGS)
    print(f"\n{OUT.relative_to(ROOT)} 작성 - 모델 {kept}개, 매물 {total:,}건")
    print("제외:", ", ".join(f"{k} {v}건" for k, v in sorted(excluded.items())) or "없음")

    # --- 색상별 시세 (#93). 모델 시세가 있는 모델의, 표본이 서는 색상 버킷만. ---
    color_lines = ["brand,model_key,color_family,listing_count,median_price,q1_price,q3_price"]
    color_kept = 0
    print(f"\n[색상별 시세] 표본 {MIN_LISTINGS}건 이상 버킷만")
    for ((brand, key), color), values in sorted(color_prices.items(), key=lambda kv: -len(kv[1])):
        if len(values) < MIN_LISTINGS:
            continue
        if len(prices[(brand, key)]) < MIN_LISTINGS:
            continue  # 모델 시세 자체가 없는 모델의 색상 행은 내보내지 않는다
        bucket_new = new_counts[((brand, key), color)] / len(values)
        model_new = model_new_counts[(brand, key)] / len(prices[(brand, key)])
        if abs(bucket_new - model_new) > MAX_NEW_SHARE_BIAS:
            print(f"  {key:<16} {color:<14} 제외 - 새상품 비중 편중 "
                  f"(버킷 {bucket_new:.0%} vs 모델 {model_new:.0%}): 색이 아니라 상태 구성을 재게 된다")
            continue
        values.sort()
        median = int(statistics.median(values))
        q1, q3 = values[len(values) // 4], values[3 * len(values) // 4]
        color_lines.append(f"{brand},{key},{color},{len(values)},{median},{q1},{q3}")
        color_kept += 1
        model_median = int(statistics.median(prices[(brand, key)]))
        premium = (median - model_median) / model_median * 100
        print(f"  {key:<16} {color:<14} n={len(values):>3}  중앙값 {median:>9,}"
              f"  (모델 전체 대비 {premium:+.0f}%)")
    OUT_BY_COLOR.write_text("\n".join(color_lines) + "\n", encoding="utf-8")
    print(f"{OUT_BY_COLOR.relative_to(ROOT)} 작성 - 버킷 {color_kept}개")


if __name__ == "__main__":
    main()
