from . import config


def is_shoe(category, title, description) -> bool:
    """신발 상품 여부를 판단한다.

    우선순위: 1) 사이트 공식 category 필드가 최우선이자 결정적이다 —
    "신발"이면 제목/설명이 애매해도 포함하고, 다른 값이면 확실히 제외한다.
    2) category 정보가 없을 때만 제목·설명 키워드로 폴백 판단한다.
    """
    if category == config.SHOE_CATEGORY_OFFICIAL:
        return True
    if category is not None:
        return False

    text = f"{title or ''} {description or ''}".lower()

    # 제외 키워드가 먼저 이긴다 ("신발끈"이 "신발" 키워드의 부분 문자열이라
    # 순서를 바꾸면 신발끈만 파는 상품이 걸러지지 않는다). category 필드가
    # 없는 경우에만 타는 폴백 경로라, 이 사이트에서는 거의 발생하지 않는다.
    if any(keyword.lower() in text for keyword in config.EXCLUDE_KEYWORDS):
        return False

    return any(keyword.lower() in text for keyword in config.SHOE_KEYWORDS)
