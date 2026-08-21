"""당근 매물의 나머지 사진을 상세 페이지 없이 알아내는 모듈.

당근 크롤러는 검색 결과 페이지만 읽어서 매물당 대표 이미지 1장만 갖는다. 그런데 이미지
파일명이 두 체계로 갈린다.

  {해시}_0.webp   인덱스 체계 (전체의 약 40%) - _1, _2...를 404가 날 때까지 HEAD로
                  찔러보면 같은 매물의 나머지 사진 URL을 알 수 있다. 전부 원본 해상도다.
  {해시}.webp     비인덱스 체계 - 이 방법이 안 통한다. 상세 페이지를 열어야 하는데,
                  매물당 페이지 요청 1번 + 예의상 지연이 필요해 이 모듈 범위 밖이다.

HEAD 요청이라 이미지 본문을 내려받지 않고, 페이지 크롤링이 아니라 CDN 확인이라 부담이 작다.
"""
import re

import requests

# "..._0.webp?쿼리" 에서 (앞부분, 인덱스, 확장자)를 뽑는다
INDEXED_PATTERN = re.compile(r"^(?P<prefix>.+)_(?P<index>\d+)\.(?P<ext>webp|jpe?g|png)$", re.IGNORECASE)

REQUEST_TIMEOUT_SECONDS = 10
# 사진이 10장을 넘는 매물은 드물고, 넘어가면 요청 수만 는다
MAX_EXTRA_IMAGES = 9


def indexed_base(image_url: str):
    """인덱스 체계 URL이면 (prefix, ext)를, 아니면 None을 돌려준다. 쿼리스트링은 무시한다."""
    match = INDEXED_PATTERN.match(image_url.split("?")[0])
    if not match:
        return None
    return match.group("prefix"), match.group("ext")


def probe_images(first_image_url: str, session: requests.Session):
    """_0부터 시작해 존재하는 이미지 URL 목록을 돌려준다.

    인덱스 체계가 아니거나 _0 자체가 없으면(삭제된 매물) 원본 URL 1장을 그대로 돌려준다 -
    호출부가 실패 때문에 기존 데이터를 잃으면 안 된다.
    """
    base = indexed_base(first_image_url)
    if base is None:
        return [first_image_url]

    prefix, ext = base
    urls = []
    for index in range(MAX_EXTRA_IMAGES + 1):
        candidate = f"{prefix}_{index}.{ext}"
        if not _exists(candidate, session):
            break
        urls.append(candidate)

    return urls if urls else [first_image_url]


def _exists(url: str, session: requests.Session) -> bool:
    try:
        response = session.head(url, timeout=REQUEST_TIMEOUT_SECONDS, allow_redirects=False)
        return response.status_code == 200
    except requests.RequestException:
        # 네트워크 오류는 "없음"과 구분되지 않지만, 여기서 멈추면 그 매물은 _0까지만 남는다.
        # 백필은 재실행 가능하므로 보수적으로 없다고 본다.
        return False
