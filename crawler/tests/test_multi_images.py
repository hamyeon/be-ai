import unittest
from unittest import mock

from crawler import multi_images


def response(status):
    r = mock.Mock()
    r.status_code = status
    return r


class IndexedBaseTests(unittest.TestCase):
    def test_인덱스_체계_URL을_인식한다(self):
        base = multi_images.indexed_base("https://img.example.net/origin/article/202604/abc123_0.webp")
        self.assertEqual(base, ("https://img.example.net/origin/article/202604/abc123", "webp"))

    def test_쿼리스트링은_무시한다(self):
        base = multi_images.indexed_base("https://img.example.net/a/b_0.webp?q=82&s=300x300&t=crop")
        self.assertEqual(base, ("https://img.example.net/a/b", "webp"))

    def test_비인덱스_체계는_None이다(self):
        self.assertIsNone(multi_images.indexed_base("https://img.example.net/a/1782047834084642a.webp"))

    def test_jpg와_png도_인식한다(self):
        self.assertIsNotNone(multi_images.indexed_base("https://img.example.net/a/b_3.jpg"))
        self.assertIsNotNone(multi_images.indexed_base("https://img.example.net/a/b_1.png"))


class ProbeImagesTests(unittest.TestCase):
    def test_404이_날_때까지의_이미지를_모은다(self):
        session = mock.Mock()
        # _0, _1, _2는 200이고 _3은 404
        session.head.side_effect = [response(200), response(200), response(200), response(404)]

        urls = multi_images.probe_images("https://img.example.net/a/b_0.webp", session)

        self.assertEqual(urls, [
            "https://img.example.net/a/b_0.webp",
            "https://img.example.net/a/b_1.webp",
            "https://img.example.net/a/b_2.webp",
        ])
        self.assertEqual(session.head.call_count, 4)

    def test_비인덱스_체계는_원본_한_장을_그대로_돌려준다(self):
        session = mock.Mock()

        urls = multi_images.probe_images("https://img.example.net/a/noindex.webp", session)

        self.assertEqual(urls, ["https://img.example.net/a/noindex.webp"])
        session.head.assert_not_called()

    def test_첫_장부터_없으면_원본을_잃지_않는다(self):
        # 삭제된 매물이어도 기존 데이터(대표 이미지 1장)는 남아야 한다
        session = mock.Mock()
        session.head.return_value = response(404)

        urls = multi_images.probe_images("https://img.example.net/a/b_0.webp", session)

        self.assertEqual(urls, ["https://img.example.net/a/b_0.webp"])

    def test_상한을_넘겨_찔러보지_않는다(self):
        session = mock.Mock()
        session.head.return_value = response(200)  # 전부 200이어도

        urls = multi_images.probe_images("https://img.example.net/a/b_0.webp", session)

        self.assertEqual(len(urls), multi_images.MAX_EXTRA_IMAGES + 1)


if __name__ == "__main__":
    unittest.main()
