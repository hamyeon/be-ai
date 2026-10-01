import csv
import json
from urllib.parse import urlsplit, urlunsplit

from . import config

# 상품 식별 우선순위: 1) 사이트 상품 ID 2) 정규화된 상품 URL.
# 두 경우 모두 이미 그 자체로 유일한 문자열이라 별도 해시가 필요 없다
# (SHA-256 해시는 상품 ID가 없을 때의 "로컬 파일명"용으로만 쓴다 — images.py 참고).


def normalize_url(url: str) -> str:
    parts = urlsplit(url)
    return urlunsplit((parts.scheme, parts.netloc, parts.path.rstrip("/"), "", ""))


def identity_key(record: dict) -> str:
    if record.get("source_product_id"):
        return f"id:{record['source_product_id']}"
    return f"url:{normalize_url(record['item_url'])}"


def load_records(path=config.PRODUCTS_JSONL_PATH) -> dict:
    """기존에 저장된 상품을 identity_key -> record dict로 불러온다.

    이 dict를 기준으로 이번 실행에서 새로 만든 record를 덮어쓰면(upsert),
    같은 상품을 다시 만났을 때 중복 저장 없이 가격/판매상태 변경을
    반영할 수 있다.
    """
    records = {}
    if not path.exists():
        return records

    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            records[identity_key(record)] = record
    return records


def write_all(records: dict, formats: set, jsonl_path=None, csv_path=None) -> None:
    jsonl_path = jsonl_path or config.PRODUCTS_JSONL_PATH
    csv_path = csv_path or config.PRODUCTS_CSV_PATH
    jsonl_path.parent.mkdir(parents=True, exist_ok=True)

    values = list(records.values())

    if "jsonl" in formats:
        with jsonl_path.open("w", encoding="utf-8") as f:
            for record in values:
                f.write(json.dumps(record, ensure_ascii=False))
                f.write("\n")

    if "csv" in formats and values:
        fieldnames = list(values[0].keys())
        with csv_path.open("w", encoding="utf-8", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            for record in values:
                row = dict(record)
                row["image_urls"] = "|".join(row.get("image_urls") or [])
                row["local_image_paths"] = "|".join(row.get("local_image_paths") or [])
                writer.writerow(row)
