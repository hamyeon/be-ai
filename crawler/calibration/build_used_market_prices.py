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

# (브랜드, 모델키, 표시명, 별칭들, 브랜드 힌트)
#
# 브랜드 힌트: 별칭이 숫자뿐인 모델(530, 574, 1461 ...)은 제목에 브랜드 단서가 같이
# 있어야 매칭한다. "530"만으로 잡으면 무관한 숫자를 끌어들인다.
#
# "이지"는 일부러 없다. 슬라이드·폼러너·350이 한 단어에 뒤섞여(실측: 482건, 범위
# 1.8만~7.7만) 어느 모델의 시세도 아니게 된다. "이지 350"처럼 특정되는 것만 둔다.
MODELS = [
    ("Nike", "airforce1", "Air Force 1", ["에어포스", "air force 1", "af1"], None),
    ("Nike", "dunklow", "Dunk Low", ["덩크 로우", "덩크로우", "덩크로", "dunk low"], None),
    ("Nike", "dunkhigh", "Dunk High", ["덩크 하이", "덩크하이", "dunk high"], None),
    ("Nike", "airmax90", "Air Max 90", ["에어맥스90", "에어맥스 90", "air max 90"], None),
    ("Nike", "airmax95", "Air Max 95", ["에어맥스95", "에어맥스 95", "air max 95"], None),
    ("Nike", "airmax97", "Air Max 97", ["에어맥스97", "에어맥스 97", "air max 97"], None),
    ("Nike", "jordan1", "Jordan 1", ["조던1", "조던 1", "에어조던1", "jordan 1"], None),
    ("Nike", "cortez", "Cortez", ["코르테즈", "cortez"], None),
    ("Nike", "blazer", "Blazer", ["블레이저", "blazer"], None),
    ("Nike", "vomero", "Vomero", ["보메로", "vomero"], None),
    ("Nike", "pegasus", "Pegasus", ["페가수스", "pegasus"], None),
    ("Nike", "v2krun", "V2K Run", ["v2k"], None),
    ("Adidas", "sambaog", "Samba", ["삼바", "samba"], None),
    ("Adidas", "gazelle", "Gazelle", ["가젤", "gazelle"], None),
    ("Adidas", "superstar", "Superstar", ["슈퍼스타", "superstar"], None),
    ("Adidas", "stansmith", "Stan Smith", ["스탠스미스", "스탠 스미스", "stan smith"], None),
    ("Adidas", "spezial", "Handball Spezial", ["스페지알", "spezial"], None),
    ("Adidas", "yeezy350", "Yeezy Boost 350", ["이지 350", "이지350", "yeezy 350"], None),
    ("New Balance", "nb327", "327", ["327"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb530", "530", ["530"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb574", "574", ["574"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb990", "990", ["990"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb993", "993", ["993"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb1906", "1906", ["1906"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb2002r", "2002R", ["2002r", "2002"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb9060", "9060", ["9060"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb550", "550", ["550"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("Dr. Martens", "dm1460", "1460", ["1460"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("Dr. Martens", "dm1461", "1461", ["1461"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("Asics", "gelkayano14", "Gel-Kayano 14", ["카야노14", "카야노 14", "kayano 14"], None),
    ("Asics", "gel1130", "Gel-1130", ["젤1130", "젤 1130", "gel-1130", "gel 1130"], None),
    ("Salomon", "xt6", "XT-6", ["xt-6", "xt6", "xt 6"], None),
    ("Converse", "chuck70", "Chuck 70", ["척70", "척 70", "chuck 70"], None),
    ("Crocs", "classicclog", "Classic Clog", ["클래식 클로그", "classic clog"], None),
    # --- #89 확대: 미매칭 매물 빈도 분석으로 추가 (scratchpad/coverage_candidates) ---
    # 기준: 표본 10건 이상 + 가격대 응집. "에어맥스"(번호 없음, 130건)와
    # "핏플랍"(브랜드 단위, 137건)은 여러 모델이 한 통에 섞여 제외 - "이지"와 같은 이유.
    ("Nike", "jordan4", "Jordan 4", ["조던4", "조던 4", "jordan 4"], None),
    ("Nike", "airmaxkoko", "Air Max Koko", ["에어맥스 코코", "맥스코코", "맥스 코코", "air max koko"], None),
    ("Dr. Martens", "dmadrian", "Adrian", ["아드리안", "adrian"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("Dr. Martens", "dm2976", "2976 Chelsea", ["2976", "첼시"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("UGG", "tasman", "Tasman", ["타스만", "태즈먼", "tasman"], None),
    ("Hunter", "hunteroriginal", "Original Rain Boot", ["헌터"], ["레인", "부츠", "장화"]),
    ("Rockfish", "rockfishrain", "Weatherwear Rain Boot", ["락피쉬"], ["레인", "부츠", "장화", "웨더웨어"]),
    ("Skechers", "gowalk", "Go Walk", ["고워크", "gowalk", "go walk"], None),
    # --- #89 2차 확대: 검색어 확대 재크롤링(+4,574건) 후 재분석으로 추가 ---
    ("Vans", "oldskool", "Old Skool", ["올드스쿨", "올드 스쿨", "old skool"], None),
    ("Vans", "authentic", "Authentic", ["어센틱", "authentic"], ["반스", "vans"]),
    ("Vans", "slipon", "Slip-On", ["슬립온", "슬립 온"], ["반스", "vans"]),
    ("Converse", "chucktaylor", "Chuck Taylor", ["척테일러", "척 테일러", "chuck taylor"], None),
    ("Birkenstock", "boston", "Boston", ["보스턴"], ["버켄", "birkenstock"]),
    ("Nike", "jordan3", "Jordan 3", ["조던3", "조던 3", "jordan 3"], None),
    # --- #97 3차 확대: 크롤링 원본 재분석(제목 기준 20건+ 후보). 새 크롤링 없이 별칭만 추가 ---
    # SB 덩크는 일반 덩크와 가격대가 다른 별개 라인이라 분리한다. "sb 덩크 로우"가 "덩크 로우"보다
    # 길어 먼저 검사되므로 SB 매물이 dunklow로 새지 않는다.
    ("Nike", "dunksb", "SB Dunk", ["sb 덩크 로우", "sb덩크로우", "sb 덩크 하이", "sb덩크하이", "덩크 로우 sb", "덩크로우 sb", "sb 덩크", "sb덩크", "덩크 sb", "sb dunk", "dunk sb"], None),
    ("Nike", "jordan11", "Jordan 11", ["조던11", "조던 11", "jordan 11"], None),
    ("Nike", "airmax1", "Air Max 1", ["에어맥스1 ", "에어맥스 1 ", "에어맥스1(", "air max 1 ", "에어맥스 1'", "에어맥스1'"], None),
    ("Adidas", "campus", "Campus", ["캠퍼스", "campus"], ["아디다스", "adidas"]),
    ("Adidas", "forum", "Forum", ["포럼", "forum"], ["아디다스", "adidas"]),
    ("Adidas", "sl72", "SL 72", ["sl72", "sl 72"], None),
    ("Adidas", "ozweego", "Ozweego", ["오즈위고", "ozweego"], None),
    ("Converse", "onestar", "One Star", ["원스타", "one star"], ["컨버스", "converse"]),
    ("Converse", "runstar", "Run Star", ["런스타", "run star"], None),
    ("Vans", "sk8hi", "Sk8-Hi", ["sk8-hi", "sk8 hi", "sk8hi", "스케이트 하이", "스케이트하이"], None),
    ("Asics", "novablast", "Novablast", ["노바블라스트", "novablast"], None),
    ("Salomon", "xt4", "XT-4", ["xt-4", "xt4", "xt 4"], None),
    ("Hoka", "bondi", "Bondi", ["본디", "bondi"], None),
    ("Puma", "speedcat", "Speedcat", ["스피드캣", "speedcat"], None),
    ("Puma", "palermo", "Palermo", ["팔레르모", "palermo"], None),
    ("Puma", "pumasuede", "Suede", ["푸마 스웨이드", "puma suede"], None),
    ("Onitsuka Tiger", "mexico66", "Mexico 66", ["멕시코66", "멕시코 66", "mexico 66"], None),
    ("Birkenstock", "arizona", "Arizona", ["아리조나", "arizona"], ["버켄", "birkenstock"]),
    ("UGG", "uggultramini", "Ultra Mini", ["울트라 미니", "울트라미니", "ultra mini"], ["어그", "ugg"]),
    ("UGG", "uggclassicmini", "Classic Mini", ["클래식 미니", "클래식미니", "classic mini"], ["어그", "ugg"]),
    ("New Balance", "nb480", "480", ["480"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb860", "860", ["860"], ["뉴발", "뉴 발", "new balance", "nb"]),
]

# 긴 별칭부터 검사해야 "에어맥스95"가 "에어맥스 9x" 계열끼리 먹히지 않는다
FLAT = sorted(
    ((alias.lower(), brand, key, hints) for brand, key, _, aliases, hints in MODELS for alias in aliases),
    key=lambda t: -len(t[0]),
)


def find_model(title, description=""):
    """제목(우선)에서 모델을 찾는다. 숫자 별칭은 브랜드 힌트가 함께 있어야 한다."""
    t = (title or "").lower()
    blob = t + " " + (description or "").lower()
    for alias, brand, key, hints in FLAT:
        if alias not in t:
            continue
        if hints and not any(h in blob for h in hints):
            continue
        return brand, key
    return None


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
