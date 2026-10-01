import unittest

from crawler.calibration import component_status


class ComponentStatusTests(unittest.TestCase):
    def classify(self, text):
        return component_status.classify(text)

    def test_박스_포함_표현은_PARTIAL(self):
        self.assertEqual(self.classify("박스 포함"), "PARTIAL")
        self.assertEqual(self.classify("박스도 같이 드려요 사이즈 235입니다"), "PARTIAL")
        self.assertEqual(self.classify("상자와 포장 그대로 담아드립니다"), "PARTIAL")

    def test_박스_없음_표현은_NONE(self):
        self.assertEqual(self.classify("신발 박스 없습니다"), "NONE")
        self.assertEqual(self.classify("박스는 없고 구매인증 해드립니다"), "NONE")
        self.assertEqual(self.classify("무박스입니다"), "NONE")
        self.assertEqual(self.classify("박스 X"), "NONE")

    def test_신발만_판다는_표현은_NONE(self):
        self.assertEqual(self.classify("구성품은 신발 단품입니다"), "NONE")
        self.assertEqual(self.classify("끈 없고 신발 단품만 드릴수 있습니다"), "NONE")

    def test_박스와_구성품이_함께_있으면_FULL(self):
        self.assertEqual(self.classify("박스와 구성품 모두 그대로 있어요"), "FULL")
        self.assertEqual(self.classify("박스,더스트백 포함입니다"), "FULL")
        self.assertEqual(self.classify("풀박스 새상품입니다"), "FULL")
        self.assertEqual(self.classify("미개봉 새제품"), "FULL")

    def test_없음이_구성품_언급을_이긴다(self):
        # "박스, 더스트백 분실하였지만 다른 더스트백 같이 드릴게요" - 박스가 없는 것은 사실이다
        self.assertEqual(
            self.classify("박스, 더스트백 분실하였지만 다른 로에베 더스트백 같이 드릴게요"),
            "NONE",
        )

    def test_택배_안내문을_구성품_택으로_읽지_않는다(self):
        # '택'은 13,388건에 나오는데 거의 전부 택배다. 이걸 tag로 읽으면
        # 배송 안내문이 전부 FULL로 분류된다.
        self.assertIsNone(self.classify("반값택배 2500원 가능합니다"))
        self.assertIsNone(self.classify("편의점 택배 가능합니다(택배비 별도)"))
        self.assertIsNone(self.classify("문고리,직거래,택배 다 가능 직거래 설벤역 택+2400"))

    def test_구성품과_무관한_문구는_판정하지_않는다(self):
        self.assertIsNone(self.classify("택을 떼버려서 반품을 못해 당근합니다"))
        self.assertIsNone(self.classify("신발끈 판매합니다"))
        self.assertIsNone(self.classify(""))
        self.assertIsNone(self.classify(None))

    def test_구성품_일부만_언급되면_PARTIAL(self):
        self.assertEqual(self.classify("여분 끈이 포함되어 있어요"), "PARTIAL")
        self.assertEqual(self.classify("더스트백은 분실했어요"), "PARTIAL")


if __name__ == "__main__":
    unittest.main()
