import unittest

from crawler import detail_images

CDN = "https://img.example-cdn.net/origin/article/202608"


def detail_html(carousel_hashes, recommendation_hashes, og_hash):
    """실측한 상세 페이지 구조를 본뜬 합성 HTML.

    캐러셀 이미지는 서로 800바이트 안팎으로 붙어 있고,
    추천 매물 이미지는 카드 마크업 때문에 3,000바이트 이상 벌어진다.
    """
    head = (
        '<html><head>'
        f'<meta property="og:image" content="{CDN}/{og_hash}.webp?f=webp&s=1200x630"/>'
        + "x" * 500 + "</head><body>" + "y" * 50000
    )
    carousel = ""
    for h in carousel_hashes:
        carousel += f'<img src="{CDN}/{h}.webp?s=1440x1440"/>' + "c" * 700
    spacer = "z" * 5000
    recommendations = ""
    for h in recommendation_hashes:
        recommendations += f'<a><img src="{CDN}/{h}.webp?s=300x300"/></a>' + "r" * 3000
    return head + carousel + spacer + recommendations + "</body></html>"


class ExtractListingImagesTests(unittest.TestCase):
    def test_캐러셀_군집만_뽑고_추천_매물은_제외한다(self):
        html = detail_html(
            carousel_hashes=["own0", "own1", "own2", "own3"],
            recommendation_hashes=["other1", "other2", "other3"],
            og_hash="own0",
        )

        images = detail_images.extract_listing_images(html)

        self.assertEqual(images, [f"{CDN}/own{i}.webp" for i in range(4)])

    def test_사진이_한_장인_매물은_그_한_장이다(self):
        html = detail_html(["solo"], ["other1", "other2"], og_hash="solo")

        self.assertEqual(detail_images.extract_listing_images(html), [f"{CDN}/solo.webp"])

    def test_구조를_못_알아보면_og_이미지_한_장이라도_돌려준다(self):
        # 본문에 og 이미지가 아예 없는 비정상 구조
        html = f'<html><head><meta property="og:image" content="{CDN}/ghost.webp"/></head><body>없음</body></html>'

        self.assertEqual(detail_images.extract_listing_images(html), [f"{CDN}/ghost.webp"])

    def test_og_이미지가_없으면_빈_목록이다(self):
        self.assertEqual(detail_images.extract_listing_images("<html><body/></html>"), [])

    def test_쿼리스트링을_뗀_원본_URL을_돌려준다(self):
        html = detail_html(["a0", "a1"], [], og_hash="a0")

        for url in detail_images.extract_listing_images(html):
            self.assertNotIn("?", url)


if __name__ == "__main__":
    unittest.main()
