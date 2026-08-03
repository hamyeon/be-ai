import logging
from datetime import datetime, timedelta, timezone

from . import config

KST = timezone(timedelta(hours=9))
logger = logging.getLogger(__name__)

# 원본 HTML 전체를 남기면 디버깅 목적에 비해 용량이 커서, 사람이 눈으로
# 구조 변화를 확인하기 충분한 앞부분만 잘라 저장한다.
HTML_SNIPPET_LENGTH = 20000


def record_parse_failure(product_url: str, html: str, reason: str) -> None:
    config.DEBUG_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(KST).strftime("%Y%m%d_%H%M%S_%f")
    path = config.DEBUG_DIR / f"parse_failure_{timestamp}.html"

    try:
        path.write_text(
            f"<!-- url: {product_url} -->\n<!-- reason: {reason} -->\n{html[:HTML_SNIPPET_LENGTH]}",
            encoding="utf-8",
        )
    except OSError as error:
        logger.warning("디버깅 파일 저장 실패: %s", error)
        return

    logger.warning("파싱 실패 기록: %s (%s) -> %s", product_url, reason, path)
