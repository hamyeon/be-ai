from datetime import datetime, timedelta, timezone

from . import config

KST = timezone(timedelta(hours=9))


def normalize_item(raw_item: dict, matched_keyword: str, region_name: str, source_html: str, disallow_checker) -> dict:
    url = raw_item.get("url")
    if not url:
        return None

    if disallow_checker(source_html, url):
        return None

    offers = raw_item.get("offers") or {}
    price = _parse_price(offers.get("price"))
    if price is None:
        return None

    title = raw_item.get("name") or ""
    description = raw_item.get("description") or ""
    text = f"{title} {description}".lower()

    return {
        "source": "DAANGN",
        "region_query": region_name,
        "matched_keyword": matched_keyword,
        "brand_guess": _guess_brand(text),
        "price_krw": price,
        "condition_grade_guess": _guess_condition_grade(text),
        "box_included_guess": _guess_box_included(text),
        "item_url": url,
        "item_title": title,
        "description": description,
        "collected_at": datetime.now(KST).date().isoformat(),
    }


def _parse_price(raw_price):
    if raw_price is None:
        return None
    try:
        return int(float(raw_price))
    except (TypeError, ValueError):
        return None


def _guess_brand(text: str):
    for brand in config.BRANDS:
        for token in brand["tokens"]:
            if token.lower() in text:
                return brand["canonical"]
    return None


def _guess_box_included(text: str):
    idx = text.find("박스")
    if idx == -1:
        idx = text.find("box")
    if idx == -1:
        return None

    window = text[max(0, idx - 4): idx + 22]
    if any(neg in window for neg in ["없", "무박", "미포함", "x"]):
        return False
    if any(pos in window for pos in ["있", "포함", "동봉", "풀박", "o"]):
        return True
    return None


def _guess_condition_grade(text: str):
    for keyword in config.CONDITION_DS_KEYWORDS:
        if keyword in text:
            return "DS"
    for keyword in config.CONDITION_S_KEYWORDS:
        if keyword in text:
            return "S"
    return "UNKNOWN"
