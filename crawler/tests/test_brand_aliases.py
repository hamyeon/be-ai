import unittest

from crawler.fruitsfamily import brand_aliases


class BrandAliasesTests(unittest.TestCase):
    def test_korean_alias_resolves_to_canonical(self):
        self.assertEqual(brand_aliases.resolve_canonical("나이키"), "Nike")
        self.assertEqual(brand_aliases.resolve_canonical("아디다스"), "Adidas")
        self.assertEqual(brand_aliases.resolve_canonical("뉴발"), "New Balance")
        self.assertEqual(brand_aliases.resolve_canonical("닥터마틴"), "Dr. Martens")

    def test_cli_style_underscore_alias_resolves(self):
        self.assertEqual(brand_aliases.resolve_canonical("new_balance"), "New Balance")

    def test_unknown_brand_returns_none(self):
        self.assertIsNone(brand_aliases.resolve_canonical("리복"))

    def test_dr_martens_has_two_site_slugs(self):
        slugs = brand_aliases.brand_slugs("Dr. Martens")
        self.assertIn("Dr. Martens", slugs)
        self.assertIn("Doc Martens", slugs)

    def test_all_ten_brands_present(self):
        self.assertEqual(len(brand_aliases.all_canonical_brands()), 10)


if __name__ == "__main__":
    unittest.main()
