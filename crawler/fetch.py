import time

import requests

from . import config


def fetch_search_html(keyword: str, session: requests.Session) -> str:
    params = {"search": keyword}
    headers = {"User-Agent": config.USER_AGENT}

    last_error = None
    for attempt in range(1, config.MAX_RETRIES + 1):
        try:
            response = session.get(
                config.SEARCH_URL,
                params=params,
                headers=headers,
                timeout=config.REQUEST_TIMEOUT_SECONDS,
            )
            response.raise_for_status()
            response.encoding = "utf-8"
            return response.text
        except requests.RequestException as error:
            last_error = error
            time.sleep(config.REQUEST_DELAY_SECONDS * attempt)

    raise RuntimeError(f"'{keyword}' 검색 페이지 요청 실패") from last_error
