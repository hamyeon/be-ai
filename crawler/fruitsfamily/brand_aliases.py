from . import config


def all_canonical_brands() -> list:
    return [brand["canonical"] for brand in config.BRANDS]


def brand_slugs(canonical: str) -> list:
    for brand in config.BRANDS:
        if brand["canonical"] == canonical:
            return brand["slugs"]
    raise ValueError(f"알 수 없는 브랜드: {canonical}")


def resolve_canonical(text: str):
    """CLI 인자, 사이트 원문 브랜드 텍스트 등 임의의 문자열을 표준 브랜드명으로 변환한다."""
    if not text:
        return None

    normalized = text.strip().lower().replace("_", " ")
    for brand in config.BRANDS:
        if normalized == brand["canonical"].lower():
            return brand["canonical"]
        if normalized in (slug.lower() for slug in brand["slugs"]):
            return brand["canonical"]
        if normalized in (alias.lower().replace("_", " ") for alias in brand["aliases"]):
            return brand["canonical"]
    return None
