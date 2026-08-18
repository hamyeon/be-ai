"""KREAM 검색/상품 페이지에서 상품 정보와 체결 거래를 뽑는 파서.

두 페이지 모두 로그인 없이 서버 렌더링으로 내려오고, 데이터는 __NUXT_DATA__에 들어 있다.
"""
import json
import re

from . import config, nuxt


class ParseError(Exception):
    pass


def extract_search_products(html: str):
    """검색 결과 페이지에서 상품 목록을 뽑는다.

    검색 페이지의 Nuxt 페이로드에는 트래킹용으로 직렬화된 JSON "문자열"이 상품마다 들어 있다
    (product_id, product_name_ko, brand_name, shop_category_name_1d 등). 구조 참조를 따라가는 것보다
    이 문자열을 그대로 파싱하는 쪽이 프론트 개편에 덜 깨져서 이걸 쓴다.
    """
    payload = nuxt.extract_payload(html)
    if payload is None:
        raise ParseError("__NUXT_DATA__를 찾지 못했습니다 - 페이지 구조가 바뀌었을 수 있습니다.")

    products = {}
    for value in payload:
        if not isinstance(value, str) or '"product_id"' not in value or '"product_name_en"' not in value:
            continue
        try:
            item = json.loads(value)
        except json.JSONDecodeError:
            continue
        product_id = item.get("product_id")
        if not product_id:
            continue
        products[product_id] = {
            "product_id": product_id,
            "name_en": item.get("product_name_en"),
            "name_ko": item.get("product_name_ko"),
            "style_code": item.get("product_style_code"),
            "site_brand_name": item.get("brand_name"),
            "category": item.get("shop_category_name_1d"),
        }
    return list(products.values())


def extract_product_detail(html: str, product_id: int):
    """상품 상세 페이지에서 (상품 정보, 체결 거래 목록)을 뽑는다."""
    payload = nuxt.extract_payload(html)
    if payload is None:
        raise ParseError("__NUXT_DATA__를 찾지 못했습니다 - 페이지 구조가 바뀌었을 수 있습니다.")

    return _extract_product_info(payload, html, product_id), _extract_trades(payload, product_id)


def _extract_product_info(payload, html, product_id):
    # 메타 카탈로그용 상품 dict: 이름/스타일코드/이미지/브랜드가 한곳에 모여 있다
    candidates = nuxt.find_dict_with_keys(payload, ["style_code", "image_urls", "brand_name"])
    info = nuxt.resolve(payload, candidates[0]) if candidates else {}

    image_urls = info.get("image_urls") or []
    if not isinstance(image_urls, list):
        image_urls = []
    image_urls = [u for u in image_urls if isinstance(u, str)]
    if not image_urls:
        # 페이로드 구조가 바뀌어도 대표 이미지 하나는 og:image에서 건진다
        og = re.search(r'property="og:image" content="([^"]+)"', html)
        if og:
            image_urls = [og.group(1)]

    return {
        "product_id": product_id,
        "name_en": info.get("name"),
        "name_ko": info.get("translated_name"),
        "style_code": info.get("style_code"),
        "site_brand_name": info.get("brand_name"),
        "image_urls": image_urls,
        "item_url": f"{config.SITE_BASE_URL}/products/{product_id}",
    }


def _extract_trades(payload, product_id):
    # 체결 거래 탭: {sales, asks, bids, login_info} 묶음의 sales.items
    containers = nuxt.find_dict_with_keys(payload, ["sales", "asks", "bids"])
    for index in containers:
        resolved = nuxt.resolve(payload, index)
        sales = resolved.get("sales") or {}
        items = sales.get("items")
        if not isinstance(items, list):
            continue

        trades = []
        for item in items:
            if not isinstance(item, dict):
                continue
            option = item.get("product_option") or {}
            trades.append({
                "product_id": product_id,
                "size": _to_size(option.get("name")),
                "price_krw": item.get("price"),
                "traded_at": item.get("date_created"),  # ISO 8601 - 희소성 지표의 체결 속도 계산에 쓴다
                "is_immediate_delivery": item.get("is_immediate_delivery_item"),
            })
        return [t for t in trades if t["price_krw"] and t["traded_at"]]
    return []


def _to_size(raw):
    """옵션명("270", "270 (US 9)")에서 mm 사이즈 정수를 뽑는다. 신발이 아니면 None."""
    if raw is None:
        return None
    match = re.match(r"\s*(\d{3})", str(raw))
    return int(match.group(1)) if match else None


def normalize_brand(site_brand_name):
    """KREAM 표기 브랜드를 우리 표기로. 수집 대상이 아니면 None."""
    if not site_brand_name:
        return None
    return config.BRAND_NORMALIZATION.get(site_brand_name.strip())
