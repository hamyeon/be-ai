"""수집 결과 저장. 상품은 product_id로 upsert, 체결 거래는 내용 키로 중복 제거 후 누적.

체결 거래는 상품 페이지가 최근 몇 건만 보여주므로, 주기적으로 다시 돌면
이력이 누적된다 - 같은 거래를 다시 만나면 건너뛴다.
"""
import json

from . import config


def _load_jsonl(path):
    if not path.exists():
        return []
    records = []
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records


def _write_jsonl(path, records):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for record in records:
            f.write(json.dumps(record, ensure_ascii=False))
            f.write("\n")


def trade_key(trade: dict) -> str:
    # 같은 사이즈가 같은 시각에 같은 가격으로 두 번 체결될 수는 없다
    return f"{trade['product_id']}|{trade.get('size')}|{trade['traded_at']}|{trade['price_krw']}"


def save_products(new_products: list) -> int:
    """product_id 기준 upsert. 새로 추가된 상품 수를 돌려준다."""
    existing = {r["product_id"]: r for r in _load_jsonl(config.PRODUCTS_JSONL_PATH)}
    added = sum(1 for p in new_products if p["product_id"] not in existing)
    for product in new_products:
        existing[product["product_id"]] = product
    _write_jsonl(config.PRODUCTS_JSONL_PATH, list(existing.values()))
    return added


def save_trades(new_trades: list) -> int:
    """중복을 제외하고 뒤에 붙인다. 새로 추가된 거래 수를 돌려준다."""
    existing = _load_jsonl(config.TRADES_JSONL_PATH)
    seen = {trade_key(t) for t in existing}
    fresh = [t for t in new_trades if trade_key(t) not in seen]
    _write_jsonl(config.TRADES_JSONL_PATH, existing + fresh)
    return len(fresh)
