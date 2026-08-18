"""KREAM 신발 시세 크롤러.

키워드(브랜드)로 검색 -> 신발 카테고리 상품만 골라 -> 상품 페이지에서
상품 정보 + 이미지 + 체결 거래(사이즈/가격/시각)를 수집한다.

robots.txt(User-agent: * Allow: /, 개인 페이지만 금지) 범위 안의 공개 페이지만 읽고,
로그인/비공개 API 호출 없이 서버 렌더링 HTML만 파싱한다. 요청 간 2~5초 랜덤 지연.

실행:
    python -m crawler.kream.main                          # 전체 키워드
    python -m crawler.kream.main --keywords 살로몬 --max-products-per-keyword 3
"""
import argparse
import logging
import random
import time
from datetime import datetime, timedelta, timezone
from urllib.parse import quote

import requests

from . import config, parser, storage

KST = timezone(timedelta(hours=9))

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)


class BlockedError(Exception):
    """403/429 감지 시 실행 전체를 중단시키기 위한 예외."""


def parse_args(argv=None):
    p = argparse.ArgumentParser(description="KREAM 신발 시세 크롤러")
    p.add_argument("--keywords", nargs="*", default=None, help="검색 키워드 (미지정시 전체 브랜드)")
    p.add_argument("--max-products-per-keyword", type=int, default=config.MAX_PRODUCTS_PER_KEYWORD_DEFAULT)
    p.add_argument("--request-delay-min", type=float, default=config.REQUEST_DELAY_MIN_SECONDS)
    p.add_argument("--request-delay-max", type=float, default=config.REQUEST_DELAY_MAX_SECONDS)
    return p.parse_args(argv)


def fetch_html(session: requests.Session, url: str) -> str:
    last_error = None
    for attempt in range(1, config.MAX_RETRIES + 2):
        try:
            response = session.get(url, timeout=config.REQUEST_TIMEOUT_SECONDS)
            if response.status_code in (403, 429):
                raise BlockedError(f"HTTP {response.status_code}: {url}")
            response.raise_for_status()
            return response.text
        except BlockedError:
            raise
        except requests.RequestException as error:
            last_error = error
            wait = 5 * attempt
            logger.warning("요청 실패(%d/%d), %d초 후 재시도: %s", attempt, config.MAX_RETRIES + 1, wait, error)
            time.sleep(wait)
    raise RuntimeError(f"요청이 계속 실패했습니다: {url} ({last_error})")


def polite_sleep(args):
    time.sleep(random.uniform(args.request_delay_min, args.request_delay_max))


def run(argv=None) -> None:
    args = parse_args(argv)
    targets = config.SEARCH_KEYWORDS
    if args.keywords:
        wanted = set(args.keywords)
        targets = [t for t in targets if t["keyword"] in wanted]
        if not targets:
            raise SystemExit(f"알 수 없는 키워드: {args.keywords}")

    session = requests.Session()
    session.headers.update({"User-Agent": config.USER_AGENT, "Accept-Language": "ko-KR,ko;q=0.9"})

    totals = {"products": 0, "trades": 0, "skipped_category": 0, "skipped_brand": 0}
    collected_at = datetime.now(KST).date().isoformat()

    try:
        for target in targets:
            keyword = target["keyword"]
            logger.info("[%s] 검색 중...", keyword)
            search_url = f"{config.SITE_BASE_URL}/search?keyword={quote(keyword)}&tab=products"
            found = parser.extract_search_products(fetch_html(session, search_url))
            polite_sleep(args)

            # 신발 카테고리 + 수집 대상 브랜드만
            shoes = []
            for item in found:
                if item.get("category") != config.SHOE_CATEGORY:
                    totals["skipped_category"] += 1
                    continue
                if parser.normalize_brand(item.get("site_brand_name")) is None:
                    totals["skipped_brand"] += 1
                    continue
                shoes.append(item)
            logger.info("[%s] 검색 결과 %d건 중 신발 %d건, 상세 수집 %d건",
                        keyword, len(found), len(shoes), min(len(shoes), args.max_products_per_keyword))

            products_batch, trades_batch = [], []
            for item in shoes[: args.max_products_per_keyword]:
                product_id = item["product_id"]
                detail_url = f"{config.SITE_BASE_URL}/products/{product_id}"
                try:
                    info, trades = parser.extract_product_detail(fetch_html(session, detail_url), product_id)
                except parser.ParseError as error:
                    logger.warning("파싱 실패(product_id=%s): %s", product_id, error)
                    polite_sleep(args)
                    continue

                # 검색 결과 쪽 정보로 빈 곳을 메운다 (상세 파싱이 일부 실패해도 식별은 가능하게)
                info["name_en"] = info.get("name_en") or item.get("name_en")
                info["name_ko"] = info.get("name_ko") or item.get("name_ko")
                info["style_code"] = info.get("style_code") or item.get("style_code")
                info["site_brand_name"] = info.get("site_brand_name") or item.get("site_brand_name")
                info["brand"] = parser.normalize_brand(info["site_brand_name"])
                info["source"] = "KREAM"
                info["collected_at"] = collected_at

                products_batch.append(info)
                trades_batch.extend(trades)
                logger.info("  product_id=%s %s | 체결 %d건 | 이미지 %d장",
                            product_id, (info.get("name_ko") or "")[:30], len(trades), len(info["image_urls"]))
                polite_sleep(args)

            totals["products"] += storage.save_products(products_batch)
            totals["trades"] += storage.save_trades(trades_batch)
    except BlockedError as error:
        logger.error("차단이 감지되어 중단합니다 (수집분은 저장됨): %s", error)

    logger.info("--- 수집 결과 ---")
    logger.info("신규 상품 %d / 신규 체결 %d / 카테고리 제외 %d / 브랜드 제외 %d",
                totals["products"], totals["trades"], totals["skipped_category"], totals["skipped_brand"])


if __name__ == "__main__":
    run()
