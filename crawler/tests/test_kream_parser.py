import json
import unittest

from crawler.kream import nuxt, parser, storage


def nuxt_html(payload) -> str:
    return (
        "<html><body>"
        f'<script type="application/json" id="__NUXT_DATA__">{json.dumps(payload, ensure_ascii=False)}</script>'
        "</body></html>"
    )


# 실제 상품 페이지의 __NUXT_DATA__ 구조를 본뜬 최소 페이로드.
# 컨테이너 안의 값은 전부 배열 인덱스 참조다 (Nuxt 3 직렬화 방식).
def product_payload():
    return [
        {"data": 1},                                        # 0 (루트)
        {"tabs": 2, "meta": 14},                            # 1
        {"sales": 3, "asks": 13, "bids": 13, "login_info": 13},  # 2
        {"items": 4},                                       # 3  sales
        [5, 9],                                             # 4  items
        {"product_option": 6, "price": 8, "date_created": 7, "is_immediate_delivery_item": 12},  # 5
        {"name": 10},                                       # 6
        "2026-08-18T13:59:19Z",                             # 7
        320000,                                             # 8
        {"product_option": 6, "price": 11, "date_created": 7, "is_immediate_delivery_item": 12},  # 9
        "270",                                              # 10
        297000,                                             # 11
        False,                                              # 12
        {},                                                 # 13
        {"name": 15, "translated_name": 16, "style_code": 17, "image_urls": 18, "brand_name": 21},  # 14
        "Jordan 1 Retro High OG Chicago 2022",              # 15
        "조던 1 레트로 하이 OG 시카고 2022",                  # 16
        "DZ5485-612",                                       # 17
        [19, 20],                                           # 18
        "https://kream-img.example.net/p1.webp",            # 19
        "https://kream-img.example.net/p2.webp",            # 20
        "Jordan",                                           # 21
    ]


class NuxtResolveTests(unittest.TestCase):
    def test_인덱스_참조를_따라가_구조를_복원한다(self):
        payload = product_payload()
        resolved = nuxt.resolve(payload, 5)
        self.assertEqual(resolved["price"], 320000)
        self.assertEqual(resolved["product_option"]["name"], "270")
        self.assertEqual(resolved["date_created"], "2026-08-18T13:59:19Z")

    def test_순환_참조에서도_멈추지_않는다(self):
        payload = [{"self": 0}]
        self.assertIsNotNone(nuxt.resolve(payload, 0))  # 예외 없이 깊이 제한으로 끊긴다

    def test_페이로드가_없으면_None이다(self):
        self.assertIsNone(nuxt.extract_payload("<html>no data</html>"))


class ProductDetailTests(unittest.TestCase):
    def test_체결_거래를_사이즈_가격_시각으로_뽑는다(self):
        info, trades = parser.extract_product_detail(nuxt_html(product_payload()), 83900)

        self.assertEqual(len(trades), 2)
        self.assertEqual(trades[0]["size"], 270)
        self.assertEqual(trades[0]["price_krw"], 320000)
        self.assertEqual(trades[0]["traded_at"], "2026-08-18T13:59:19Z")
        self.assertEqual(trades[0]["product_id"], 83900)

    def test_상품_정보와_이미지를_뽑는다(self):
        info, _ = parser.extract_product_detail(nuxt_html(product_payload()), 83900)

        self.assertEqual(info["style_code"], "DZ5485-612")
        self.assertEqual(info["name_ko"], "조던 1 레트로 하이 OG 시카고 2022")
        self.assertEqual(len(info["image_urls"]), 2)
        self.assertEqual(info["item_url"], "https://kream.co.kr/products/83900")

    def test_페이로드가_없으면_ParseError다(self):
        with self.assertRaises(parser.ParseError):
            parser.extract_product_detail("<html></html>", 1)


class SearchProductsTests(unittest.TestCase):
    def test_트래킹_JSON_문자열에서_상품을_뽑고_중복을_없앤다(self):
        item = json.dumps({
            "product_id": 25673, "product_name_en": "Salomon XT-6 ADV Black",
            "product_name_ko": "살로몬 XT-6 ADV 블랙", "product_style_code": "L41086600",
            "brand_name": "Salomon", "shop_category_name_1d": "신발",
        }, ensure_ascii=False)
        html = nuxt_html([{"a": 1}, item, item])  # 같은 상품이 두 번 들어 있어도

        products = parser.extract_search_products(html)

        self.assertEqual(len(products), 1)
        self.assertEqual(products[0]["product_id"], 25673)
        self.assertEqual(products[0]["category"], "신발")


class BrandNormalizationTests(unittest.TestCase):
    def test_Jordan은_Nike로_통일된다(self):
        self.assertEqual(parser.normalize_brand("Jordan"), "Nike")

    def test_표기_변형도_흡수한다(self):
        self.assertEqual(parser.normalize_brand("ASICS"), "Asics")
        self.assertEqual(parser.normalize_brand("HOKA"), "Hoka")

    def test_수집_대상이_아니면_None이다(self):
        self.assertIsNone(parser.normalize_brand("Gucci"))


class TradeSizeTests(unittest.TestCase):
    def test_옵션명에서_mm_사이즈를_뽑는다(self):
        self.assertEqual(parser._to_size("270"), 270)
        self.assertEqual(parser._to_size("270 (US 9)"), 270)

    def test_신발_사이즈가_아니면_None이다(self):
        self.assertIsNone(parser._to_size("L"))
        self.assertIsNone(parser._to_size(None))


class TradeKeyTests(unittest.TestCase):
    def test_같은_거래는_같은_키다(self):
        trade = {"product_id": 1, "size": 270, "traded_at": "2026-08-18T13:59:19Z", "price_krw": 320000}
        self.assertEqual(storage.trade_key(trade), storage.trade_key(dict(trade)))

    def test_가격이_다르면_다른_거래다(self):
        a = {"product_id": 1, "size": 270, "traded_at": "2026-08-18T13:59:19Z", "price_krw": 320000}
        b = dict(a, price_krw=321000)
        self.assertNotEqual(storage.trade_key(a), storage.trade_key(b))


if __name__ == "__main__":
    unittest.main()
