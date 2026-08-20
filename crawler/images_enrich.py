"""매물 레코드의 이미지 목록을 "게시물의 사진 전체"로 채우는 공용 로직.

검색 결과(JSON-LD)에는 대표 이미지 1장만 실린다. 나머지 사진을 얻는 경로는 파일명 체계에
따라 다르다.

  {해시}_0.webp 인덱스 체계  → CDN에 _1, _2...를 HEAD로 찔러본다 (multi_images).
                              상세 페이지를 열지 않아 빠르고 부담이 작다.
  그 외 체계               → 상세 페이지를 열어 캐러셀 군집에서 뽑는다 (detail_images).
                              페이지 요청이라 예의상 지연이 필요하다.

크롤러 본 단계와 백필 스크립트가 같은 로직을 쓴다.
"""
import time

import requests

from . import config, detail_images, multi_images


def enrich_record_images(record: dict, session: requests.Session) -> bool:
    """이미지가 1장뿐인 레코드를 전체 사진으로 확장한다. 확장됐으면 True.

    실패해도 기존 이미지 1장은 그대로 남긴다.
    """
    urls = record.get("image_urls") or []
    if len(urls) != 1:
        return False

    base = urls[0].split("?")[0]

    if multi_images.indexed_base(base) is not None:
        full = multi_images.probe_images(base, session)
    else:
        full = _from_detail_page(record.get("item_url"), session)
        # 상세 페이지 요청은 검색 요청과 같은 예의를 지킨다
        time.sleep(config.REQUEST_DELAY_SECONDS)

    if len(full) > 1:
        record["image_urls"] = full
        return True
    return False


def _from_detail_page(item_url, session: requests.Session):
    if not item_url:
        return []
    try:
        response = session.get(
            item_url,
            headers={"User-Agent": config.USER_AGENT},
            timeout=config.REQUEST_TIMEOUT_SECONDS,
        )
        response.raise_for_status()
        response.encoding = "utf-8"
    except requests.RequestException:
        # 삭제된 매물 등. 기존 대표 이미지를 유지하기 위해 빈 목록을 돌려준다
        return []
    return detail_images.extract_listing_images(response.text)
