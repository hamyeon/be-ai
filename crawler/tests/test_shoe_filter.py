import unittest
from pathlib import Path

from crawler.fruitsfamily import parser, shoe_filter

FIXTURES_DIR = Path(__file__).parent / "fixtures"


def _load(name: str) -> str:
    return (FIXTURES_DIR / name).read_text(encoding="utf-8")


class ShoeFilterTests(unittest.TestCase):
    def test_official_shoe_category_included(self):
        result = parser.parse_product(
            _load("product_shoe_basic.html"), "https://fruitsfamily.com/product/1vd0v"
        )
        self.assertTrue(shoe_filter.is_shoe(result["category"], result["item_title"], result["description"]))

    def test_outerwear_excluded(self):
        result = parser.parse_product(
            _load("product_nonshoe_outer.html"), "https://fruitsfamily.com/product/63ycd"
        )
        self.assertFalse(shoe_filter.is_shoe(result["category"], result["item_title"], result["description"]))

    def test_bag_excluded(self):
        result = parser.parse_product(
            _load("product_nonshoe_bag.html"), "https://fruitsfamily.com/product/4818e"
        )
        self.assertFalse(shoe_filter.is_shoe(result["category"], result["item_title"], result["description"]))

    def test_official_category_overrides_ambiguous_title(self):
        # 공식 category가 "신발"이면 제목이 애매해도 포함해야 한다.
        self.assertTrue(shoe_filter.is_shoe("신발", "아무 의미 없는 제목", None))

    def test_official_category_overrides_shoe_keyword_in_title(self):
        # 공식 category가 신발이 아니면, 제목에 신발 키워드가 있어도 제외한다.
        self.assertFalse(shoe_filter.is_shoe("액세서리", "나이키 운동화 모양 키링", None))

    def test_fallback_keyword_includes_shoe_when_category_missing(self):
        self.assertTrue(shoe_filter.is_shoe(None, "나이키 에어맥스 스니커즈", "편한 착화감"))

    def test_fallback_excludes_shoelace_only_product(self):
        self.assertFalse(shoe_filter.is_shoe(None, "나이키 신발끈만 팝니다", None))

    def test_fallback_excludes_clothing_without_shoe_keyword(self):
        self.assertFalse(shoe_filter.is_shoe(None, "나이키 후드티", "따뜻해요"))


if __name__ == "__main__":
    unittest.main()
