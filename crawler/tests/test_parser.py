import unittest
from pathlib import Path

from crawler.fruitsfamily import parser

FIXTURES_DIR = Path(__file__).parent / "fixtures"


def _load(name: str) -> str:
    return (FIXTURES_DIR / name).read_text(encoding="utf-8")


class ParseProductTests(unittest.TestCase):
    def test_extracts_title(self):
        result = parser.parse_product(
            _load("product_shoe_basic.html"), "https://fruitsfamily.com/product/1vd0v"
        )
        self.assertEqual(
            result["item_title"], "(220)(PS)나이키 포스 1 로우 SE 서밋 화이트 메탈릭실버[대전]"
        )

    def test_price_is_converted_to_int(self):
        result = parser.parse_product(
            _load("product_shoe_basic.html"), "https://fruitsfamily.com/product/1vd0v"
        )
        self.assertEqual(result["price_krw"], 380000)
        self.assertIsInstance(result["price_krw"], int)

    def test_discounted_price_keeps_original_price(self):
        result = parser.parse_product(
            _load("product_shoe_discounted.html"), "https://fruitsfamily.com/product/84wf"
        )
        self.assertEqual(result["price_krw"], 59000)
        self.assertEqual(result["original_price"], 99000)

    def test_is_sold_false_when_status_is_selling(self):
        result = parser.parse_product(
            _load("product_shoe_basic.html"), "https://fruitsfamily.com/product/1vd0v"
        )
        self.assertFalse(result["is_sold"])

    def test_is_sold_true_when_status_is_not_selling(self):
        result = parser.parse_product(
            _load("product_sold.html"), "https://fruitsfamily.com/product/sold-fixture"
        )
        self.assertTrue(result["is_sold"])

    def test_extracts_image_urls(self):
        result = parser.parse_product(
            _load("product_shoe_basic.html"), "https://fruitsfamily.com/product/1vd0v"
        )
        self.assertGreater(len(result["image_urls"]), 0)
        self.assertTrue(all(url.startswith("https://") for url in result["image_urls"]))

    def test_extracts_source_product_id(self):
        result = parser.parse_product(
            _load("product_shoe_basic.html"), "https://fruitsfamily.com/product/1vd0v"
        )
        self.assertEqual(result["source_product_id"], "3142831")

    def test_missing_required_field_raises_parse_error(self):
        with self.assertRaises(parser.ParseError):
            parser.parse_product(
                _load("product_missing_required_field.html"),
                "https://fruitsfamily.com/product/missing",
            )

    def test_missing_apollo_state_raises_parse_error(self):
        with self.assertRaises(parser.ParseError):
            parser.parse_product(
                _load("product_no_apollo_state.html"),
                "https://fruitsfamily.com/product/broken",
            )


if __name__ == "__main__":
    unittest.main()
