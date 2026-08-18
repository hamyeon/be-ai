"""시세 대상 15개 모델이 크롤링 매물에서 몇 건씩 잡히는지 집계한다. (#27 희소성 지표)

목적: "매물 수"를 희소성 근거로 쓸 수 있는지 판단하는 것. 가격 공식에 바로 반영하지 않는다 -
근거 없이 반영하면 Vision 쪽의 "근거 없는 희소성 설명 차단" 정책(#21)과 모순되기 때문이다.

실행:
    python -m crawler.rarity_report
"""
import json
import re
from pathlib import Path

BASE = Path(__file__).resolve().parent

# 시세 데이터(ebay_normalized.csv)의 15개 target 모델.
# 크롤링 매물 제목은 한국어라, 모델을 식별할 수 있는 한/영 키워드 그룹으로 매칭한다.
# 그룹 안은 OR(하나라도), 그룹 사이는 AND(전부)다. 예: 조던1은 ("조던"|"jordan") AND ("1").
TARGET_MODELS = [
    ("SH-01", "Nike", "Air Jordan 1 Retro High", [["조던", "jordan"], ["1"]]),
    ("SH-10", "Nike", "Air Jordan 4 Retro", [["조던", "jordan"], ["4"]]),
    ("SH-13", "Nike", "Air Max 95", [["에어맥스", "air max", "airmax"], ["95"]]),
    ("SH-04", "Nike", "Dunk Low", [["덩크", "dunk"]]),
    ("SH-05", "Nike", "Air Force 1", [["에어포스", "air force", "airforce"]]),
    ("SH-02", "Adidas", "Samba OG", [["삼바", "samba"]]),
    ("SH-09", "Adidas", "Gazelle", [["가젤", "gazelle"]]),
    ("SH-14", "Adidas", "Yeezy Boost 350", [["이지", "yeezy"], ["350"]]),
    ("SH-03", "New Balance", "993", [["993"]]),
    ("SH-12", "New Balance", "990", [["990"]]),
    ("SH-08", "New Balance", "2002", [["2002"]]),
    ("SH-06", "Asics", "Gel-Kayano 14", [["카야노", "kayano"]]),
    ("SH-07", "Salomon", "XT-6", [["xt-6", "xt6"]]),
]


def load_listings():
    listings = []
    daangn = BASE / "output" / "daangn_shoes_raw.jsonl"
    fruits = BASE / "data" / "products.jsonl"
    for path, source, brand_field in [(daangn, "daangn", "brand_guess"), (fruits, "fruitsfamily", "brand")]:
        if not path.exists():
            print(f"경고: {path} 없음, 건너뜀")
            continue
        for line in path.open(encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            listings.append({
                "source": source,
                "brand": (row.get(brand_field) or "").strip(),
                "text": f"{row.get('item_title') or ''} {row.get('description') or ''}".lower(),
            })
    return listings


def matches(text: str, keyword_groups) -> bool:
    for group in keyword_groups:
        if not any(keyword_hit(text, kw) for kw in group):
            return False
    return True


def keyword_hit(text: str, keyword: str) -> bool:
    if keyword.isdigit():
        # 숫자 키워드는 단어 경계로 본다. "1"이 "150000원"에 걸리면 안 된다.
        return re.search(rf"(?<![0-9]){keyword}(?![0-9])", text) is not None
    return keyword in text


def brand_matches(listing_brand: str, target_brand: str) -> bool:
    return listing_brand.lower().replace(" ", "") == target_brand.lower().replace(" ", "")


def main():
    listings = load_listings()
    print(f"크롤링 매물 전체: {len(listings)}건 (당근 + 후르츠패밀리)\n")
    print(f"{'모델':<28}{'매물 수':>8}{'  당근':>7}{'  후르츠':>8}")
    print("-" * 55)

    counts = []
    for target_id, brand, model, groups in TARGET_MODELS:
        hits = [l for l in listings if brand_matches(l["brand"], brand) and matches(l["text"], groups)]
        by_source = {"daangn": 0, "fruitsfamily": 0}
        for h in hits:
            by_source[h["source"]] += 1
        counts.append((model, len(hits)))
        print(f"{brand + ' ' + model:<28}{len(hits):>8}{by_source['daangn']:>7}{by_source['fruitsfamily']:>8}")

    values = sorted(c for _, c in counts)
    print("-" * 55)
    print(f"분포: 최소 {values[0]} / 중앙값 {values[len(values) // 2]} / 최대 {values[-1]}")


if __name__ == "__main__":
    main()
