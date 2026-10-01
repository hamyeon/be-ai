import json
import re

LD_JSON_PATTERN = re.compile(
    r'<script type="application/ld\+json">(.*?)</script>', re.S
)


def extract_ld_json_items(html: str) -> list:
    match = LD_JSON_PATTERN.search(html)
    if not match:
        return []

    try:
        data = json.loads(match.group(1))
    except json.JSONDecodeError:
        return []

    items = []
    for element in data.get("itemListElement", []):
        item = element.get("item")
        if item:
            items.append(item)
    return items


def is_crawl_disallowed(html: str, item_url: str) -> bool:
    anchor = _unique_anchor(item_url)
    if not anchor:
        return False

    for pos in _find_all(html, anchor):
        window = html[pos: pos + 3000]
        if '"webCrawlNotAllowed":true' in window:
            return True
    return False


def _unique_anchor(item_url: str) -> str:
    slug = item_url.rstrip("/").rsplit("/", 1)[-1]
    return slug.rsplit("-", 1)[-1]


def _find_all(text: str, sub: str):
    start = 0
    while True:
        idx = text.find(sub, start)
        if idx == -1:
            return
        yield idx
        start = idx + 1
