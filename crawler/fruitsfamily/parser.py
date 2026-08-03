import json

from bs4 import BeautifulSoup

APOLLO_STATE_SCRIPT_ID = "__APOLLO_STATE__"

# 실제 품절 상품 표본으로 status="sold"를 확인했다 (OOFOS 브랜드에서 다수
# 확인, README 참고). "selling"이 아니면 전부 판매완료/구매불가로 간주한다.
SELLING_STATUS = "selling"


class ParseError(Exception):
    pass


def extract_apollo_state(html: str) -> dict:
    soup = BeautifulSoup(html, "html.parser")
    script = soup.find("script", id=APOLLO_STATE_SCRIPT_ID)
    if script is None or not script.string:
        raise ParseError("__APOLLO_STATE__ 스크립트를 찾을 수 없음")

    try:
        return json.loads(script.string)
    except json.JSONDecodeError as error:
        raise ParseError(f"__APOLLO_STATE__ JSON 파싱 실패: {error}") from error


def _resolve_product_entity(state: dict, product_url: str) -> dict:
    root_query = state.get("ROOT_QUERY") or {}

    ref = None
    for key, value in root_query.items():
        if key.startswith("seeProductResponse("):
            ref = ((value or {}).get("seeProduct") or {}).get("__ref")
            break

    if not ref:
        raise ParseError(f"seeProductResponse 쿼리를 찾을 수 없음: {product_url}")

    entity = state.get(ref)
    if entity is None:
        raise ParseError(f"상품 엔티티({ref})가 apollo state에 없음: {product_url}")

    return entity


def parse_product(html: str, product_url: str) -> dict:
    """상품 상세 페이지 HTML에서 __APOLLO_STATE__를 파싱해 원본 필드 dict를 반환한다.

    반환 dict는 저장 스키마가 아니라, main.py에서 저장 스키마로 매핑하기 전
    사이트 원본 필드 그대로다 (category, brand 원문 텍스트 등 포함).
    """
    state = extract_apollo_state(html)
    entity = _resolve_product_entity(state, product_url)

    title = entity.get("title")
    price = entity.get("price")
    if not title or price is None:
        raise ParseError(
            f"필수 필드(제목 또는 가격) 누락: title={title!r}, price={price!r} ({product_url})"
        )

    status = entity.get("status")
    is_sold = status != SELLING_STATUS

    return {
        "source_product_id": entity.get("id"),
        "original_brand_text": entity.get("brand"),
        "category": entity.get("category"),
        "item_title": title,
        "price_krw": price,
        "original_price": entity.get("original_price"),
        "size": entity.get("size"),
        "description": entity.get("description"),
        "is_sold": is_sold,
        "image_urls": [
            url for url in (entity.get("resizedSmallImages") or []) if isinstance(url, str)
        ],
    }
