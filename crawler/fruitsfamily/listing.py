import json
import logging
import random
import time
from contextlib import contextmanager

from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
from playwright.sync_api import sync_playwright

from . import config
from .errors import BlockedError

logger = logging.getLogger(__name__)

LOAD_MORE_SELECTOR = "button.InfiniteScroll-more"
PRODUCT_LINK_SELECTOR = "a[href^='/product/']"


@contextmanager
def open_brand_listing(slug: str, headless: bool = True):
    """브랜드 목록 페이지를 열어둔 Playwright page를 컨텍스트로 제공한다.

    브랜드 하나당 브라우저를 한 번만 띄우고, 이 안에서 필요한 만큼만
    "더보기"를 눌러가며 상품을 늘려나간다 (iter_product_urls).
    """
    subcategory_ids = ",".join(str(i) for i in config.SHOE_SUBCATEGORY_IDS)
    url = f"{config.SITE_BASE_URL}/brand/{slug}?subcategoryIds={subcategory_ids}&sort=POPULAR"

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=headless)
        try:
            page = browser.new_page(user_agent=config.USER_AGENT)
            response = page.goto(url, wait_until="load", timeout=45000)
            _check_response(response, url)
            page.wait_for_timeout(2000)
            yield page
        finally:
            browser.close()


def iter_product_urls(page, max_pages, delay_min_seconds, delay_max_seconds):
    """상품 상세 페이지 경로(/product/xxx)를 새로 발견하는 순서대로 yield한다.

    같은 브랜드 페이지 안에서 이미 본 링크는 다시 내보내지 않는다.
    "더보기" 버튼이 사라지거나 max_pages에 도달하면 멈춘다.
    """
    seen = set()

    for href in _extract_links(page):
        seen.add(href)
        yield href

    pages_loaded = 1
    while max_pages is None or pages_loaded < max_pages:
        button = page.locator(LOAD_MORE_SELECTOR)
        if button.count() == 0:
            return

        previous_count = len(_extract_links(page))

        try:
            button.first.click(timeout=5000)
        except Exception as error:
            logger.info("더보기 버튼 클릭 불가, 목록 종료로 간주: %s", error)
            return

        # 클릭 직후 새 상품 DOM이 실제로 늘어날 때까지 기다린다. 이건 요청
        # 지연(delay_min/max, 정중한 지연 목적)과는 별개의 "정확성"을 위한
        # 대기라 delay를 0으로 줘도 반드시 지켜야 한다 — 안 그러면 아직 로딩
        # 안 된 상태를 "더 이상 상품 없음"으로 오판해 목록을 조기 종료한다.
        try:
            page.wait_for_function(
                f"prev => document.querySelectorAll({json.dumps(PRODUCT_LINK_SELECTOR)}).length > prev",
                arg=previous_count,
                timeout=10000,
            )
        except PlaywrightTimeoutError:
            logger.info("더보기 클릭 후 새 상품 로딩 확인 안 됨, 목록 종료로 간주")
            return

        time.sleep(random.uniform(delay_min_seconds, delay_max_seconds))
        pages_loaded += 1

        found_new = False
        for href in _extract_links(page):
            if href not in seen:
                seen.add(href)
                found_new = True
                yield href

        if not found_new:
            return


def _extract_links(page):
    hrefs = page.eval_on_selector_all(
        PRODUCT_LINK_SELECTOR, "els => els.map(e => e.getAttribute('href'))"
    )
    ordered_unique = []
    seen_this_call = set()
    for href in hrefs:
        if href and href not in seen_this_call:
            seen_this_call.add(href)
            ordered_unique.append(href)
    return ordered_unique


def _check_response(response, url: str):
    if response is None:
        return
    status = response.status
    if status == 403:
        raise BlockedError(f"403 Forbidden: {url}")
    if status == 429:
        raise BlockedError(f"429 Too Many Requests: {url}")
    if status >= 400:
        logger.warning("목록 페이지 응답 상태 이상: %s (status=%d)", url, status)
