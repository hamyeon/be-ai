import hashlib
import logging
import re
from urllib.parse import urlsplit

import requests

from . import config

logger = logging.getLogger(__name__)

ALLOWED_CONTENT_TYPES = {
    "image/jpeg": "jpg",
    "image/png": "png",
    "image/webp": "webp",
}
ALLOWED_EXTENSIONS = {"jpg", "jpeg", "png", "webp"}


def brand_dir_name(canonical_brand: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", canonical_brand.lower()).strip("_")


def stable_key(source_product_id, item_url: str) -> str:
    if source_product_id:
        return str(source_product_id)
    return hashlib.sha256(item_url.encode("utf-8")).hexdigest()[:16]


def download_images(session: requests.Session, brand_dir: str, key: str, image_urls, max_images: int):
    """이미지를 최대 max_images장까지 내려받는다.

    실패한 이미지는 건너뛰고 성공한 것만 로컬 경로 리스트로 반환한다 —
    이미지 다운로드 실패가 상품 전체 수집 실패로 이어지지 않게 하기 위함.
    """
    saved_paths = []
    target_dir = config.IMAGES_DIR / brand_dir

    for index, image_url in enumerate(image_urls[:max_images], start=1):
        try:
            response = session.get(
                image_url,
                headers={"User-Agent": config.USER_AGENT},
                timeout=config.REQUEST_TIMEOUT_SECONDS,
            )
            response.raise_for_status()
        except requests.RequestException as error:
            logger.warning("이미지 다운로드 실패 (%s): %s", image_url, error)
            continue

        ext = _resolve_extension(response, image_url)
        if ext is None:
            logger.warning("지원하지 않는 이미지 형식이라 저장하지 않음: %s", image_url)
            continue

        target_dir.mkdir(parents=True, exist_ok=True)
        path = target_dir / f"{key}_{index:02d}.{ext}"
        try:
            path.write_bytes(response.content)
        except OSError as error:
            logger.warning("이미지 저장 실패 (%s): %s", path, error)
            continue

        saved_paths.append(str(path.relative_to(config.BASE_DIR)).replace("\\", "/"))

    return saved_paths


def _resolve_extension(response: requests.Response, image_url: str):
    content_type = response.headers.get("Content-Type", "").split(";")[0].strip().lower()
    ext = ALLOWED_CONTENT_TYPES.get(content_type)
    if ext:
        return ext

    path = urlsplit(image_url).path.lower()
    for candidate in ALLOWED_EXTENSIONS:
        if path.endswith(f".{candidate}"):
            return "jpg" if candidate == "jpeg" else candidate
    return None
