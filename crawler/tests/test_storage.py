import json
import tempfile
import unittest
from pathlib import Path

from crawler.fruitsfamily import storage


def _record(product_id, price=100000, url="https://fruitsfamily.com/product/abc/"):
    return {
        "source": "FRUITSFAMILY",
        "source_product_id": product_id,
        "brand": "Nike",
        "original_brand_text": "Nike",
        "item_title": "테스트 상품",
        "price_krw": price,
        "original_price": None,
        "size": "270",
        "description": None,
        "is_sold": False,
        "item_url": url,
        "collected_at": "2026-08-03",
        "image_urls": [],
        "local_image_paths": [],
    }


class StorageDedupTests(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = Path(tempfile.mkdtemp())
        self.jsonl_path = self.tmp_dir / "products.jsonl"
        self.csv_path = self.tmp_dir / "products.csv"

    def test_normalize_url_ignores_trailing_slash(self):
        a = storage.normalize_url("https://fruitsfamily.com/product/abc/")
        b = storage.normalize_url("https://fruitsfamily.com/product/abc")
        self.assertEqual(a, b)

    def test_identity_key_prefers_product_id_over_url(self):
        record = _record("123", url="https://fruitsfamily.com/product/whatever")
        self.assertEqual(storage.identity_key(record), "id:123")

    def test_identity_key_falls_back_to_url_when_no_id(self):
        record = _record(None, url="https://fruitsfamily.com/product/no-id-here")
        self.assertTrue(storage.identity_key(record).startswith("url:"))

    def test_same_product_id_upserts_instead_of_duplicating(self):
        records = {}
        first = _record("123", price=100000)
        records[storage.identity_key(first)] = first

        updated = _record("123", price=90000)  # 가격이 바뀐 동일 상품
        records[storage.identity_key(updated)] = updated

        storage.write_all(records, {"jsonl"}, jsonl_path=self.jsonl_path, csv_path=self.csv_path)

        with self.jsonl_path.open(encoding="utf-8") as f:
            lines = [json.loads(line) for line in f if line.strip()]

        self.assertEqual(len(lines), 1)
        self.assertEqual(lines[0]["price_krw"], 90000)

    def test_load_records_roundtrip(self):
        records = {storage.identity_key(_record("999")): _record("999")}
        storage.write_all(records, {"jsonl"}, jsonl_path=self.jsonl_path, csv_path=self.csv_path)

        loaded = storage.load_records(self.jsonl_path)
        self.assertIn("id:999", loaded)


if __name__ == "__main__":
    unittest.main()
