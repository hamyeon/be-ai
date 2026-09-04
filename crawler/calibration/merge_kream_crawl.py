# 크롤러가 수집한 KREAM 체결 거래를 참조 시세 CSV로 병합한다.
#
# crawler/data/kream_trades.jsonl -> backend/.../kream_normalized.csv
#
# 자동으로 전부 넣지 않고 상품 단위로 사람이 고른 것만 넣는다(INCLUDE).
# 비율의 분모가 되는 참조는 "그 모델의 보통 가격"이어야 하는데, 검색 상위에는
# 희소·프리미엄 컬러가 섞여 나온다(#61에서 에어맥스95 Neon으로 비율 0.14가 나온 사건).
# 컬러웨이 품질 판단은 자동화할 수 없어서 명시적 목록으로 남긴다.
#
# 여러 번 실행해도 같은 체결이 중복으로 쌓이지 않는다(내용 키로 제거).
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TRADES = ROOT / "crawler" / "data" / "kream_trades.jsonl"
PRODUCTS = ROOT / "crawler" / "data" / "kream_products.jsonl"
OUT = ROOT / "backend" / "src" / "main" / "resources" / "data" / "kream_normalized.csv"

# product_id -> (브랜드, 모델명, 컬러웨이). 표준(상시) 컬러웨이만 고른다.
INCLUDE = {
    30987: ("Adidas", "Superstar", "White Black"),         # 오리지널 기본 컬러
    28260: ("New Balance", "530", "Steel Grey"),           # 530 대표 상시 컬러
    385971: ("Nike", "Cortez", "White Varsity Red (W)"),   # 코르테즈 클래식 (W)
    270425: ("Nike", "Cortez", "White Black"),             # 코르테즈 클래식 (남성)
    273000: ("Nike", "Cortez", "White Black (W)"),         # 코르테즈 클래식 (W)
    65216: ("New Balance", "574", "Legacy Navy"),          # 574 대표 상시 라인
}

# 수집됐지만 참조로 쓰지 않는 상품과 이유. 실수로 INCLUDE에 옮기지 않도록 기록한다.
EXCLUDE = {
    406041: "슈퍼스타 2 - 별개 모델(저가 라인). 슈퍼스타 참조에 섞으면 분모가 왜곡된다",
    26344: "슈퍼스타 코어 블랙 화이트 - 체결가 10~24만원으로 기본 컬러의 약 2배. 희소 컬러로 판단",
}


def main():
    products = {}
    with PRODUCTS.open(encoding="utf-8") as f:
        for line in f:
            p = json.loads(line)
            products[p["product_id"]] = p

    include = dict(INCLUDE)

    existing = OUT.read_text(encoding="utf-8-sig")
    seen = set()
    for line in existing.splitlines()[1:]:
        c = line.split(",")
        if len(c) >= 6:
            seen.add((c[7] if len(c) > 7 else "", c[4], c[5], (c[11] if len(c) > 11 else "")))

    added, skipped = [], 0
    with TRADES.open(encoding="utf-8") as f:
        for line in f:
            t = json.loads(line)
            pid = t["product_id"]
            if pid not in include:
                continue
            brand, model, colorway = include[pid]
            p = products.get(pid, {})
            url = f"https://kream.co.kr/products/{pid}"
            memo = f"거래시점: {(t.get('traded_at') or '')[:10]}"
            key = (url, str(t.get("size")), str(t.get("price_krw")), memo)
            if key in seen:
                skipped += 1
                continue
            seen.add(key)
            added.append(",".join([
                f"AUTO-{pid}", brand, model, colorway,
                str(t.get("size")), str(t.get("price_krw")), "체결 거래",
                url, (p.get("name_ko") or "").replace(",", " "),
                p.get("collected_at") or "", "DS", memo,
            ]))

    if added:
        if not existing.endswith("\n"):
            existing += "\n"
        OUT.write_text(existing + "\n".join(added) + "\n", encoding="utf-8-sig")

    print(f"추가 {len(added)}행 / 중복 제외 {skipped}행")
    for row in added:
        print("  " + row)


if __name__ == "__main__":
    main()
