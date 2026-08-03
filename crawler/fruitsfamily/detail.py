import logging
import time

import requests

from . import config
from .errors import BlockedError

logger = logging.getLogger(__name__)


def fetch_product_html(session: requests.Session, product_path: str) -> str:
    url = f"{config.SITE_BASE_URL}{product_path}"
    last_error = None

    for attempt in range(1, config.MAX_RETRIES + 1):
        try:
            response = session.get(
                url,
                headers={"User-Agent": config.USER_AGENT},
                timeout=config.REQUEST_TIMEOUT_SECONDS,
            )
        except requests.RequestException as error:
            last_error = error
            time.sleep(config.REQUEST_DELAY_MAX_SECONDS * attempt)
            continue

        if response.status_code == 429:
            logger.warning("429 Too Many Requests, 긴 대기 후 재시도: %s", url)
            time.sleep(60 * attempt)
            continue
        if response.status_code == 403:
            raise BlockedError(f"403 Forbidden: {url}")
        if response.status_code >= 400:
            last_error = RuntimeError(f"HTTP {response.status_code}: {url}")
            time.sleep(config.REQUEST_DELAY_MAX_SECONDS * attempt)
            continue

        response.encoding = "utf-8"
        return response.text

    raise RuntimeError(f"상품 상세 페이지 요청 실패: {url}") from last_error
