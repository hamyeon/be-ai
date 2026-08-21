"""당근 매물 상세 페이지에서 "이 매물의" 사진 전체를 뽑는 모듈.

상세 페이지에는 매물 캐러셀 사진 외에 하단 추천 매물 썸네일도 수십 장 섞여 있어서,
그냥 article 이미지를 전부 긁으면 남의 매물 사진이 들어온다.

실측(2026-08-20)으로 확인한 구분 규칙:
- 캐러셀의 <img>들은 서로 800바이트 안팎 간격으로 붙어 있다
- 추천 매물 카드는 카드 하나가 3,000바이트 이상이라 이미지 간격이 그만큼 벌어진다
- og:image(대표 사진)는 캐러셀 안에도 다시 등장한다

그래서 본문 이미지들의 등장 위치를 간격으로 군집화하고, og:image가 포함된 군집을
그 매물의 사진으로 본다. CSS 클래스명은 빌드마다 바뀌는 해시라 쓰지 않는다.
"""
import re

ARTICLE_IMAGE_PATTERN = re.compile(r"https://[a-z0-9.\-]+/origin/article/[^\"\s\\)]+")
OG_IMAGE_PATTERN = re.compile(r'property="og:image" content="([^"]+)"')

# 캐러셀 내 간격(~800)과 추천 카드 간격(~3,000)의 중간값
CLUSTER_GAP_BYTES = 1500


def extract_listing_images(html: str):
    """상세 페이지 HTML에서 이 매물의 원본 이미지 URL 목록을 돌려준다.

    구조를 못 알아보면 og:image 한 장이라도 돌려준다 - 호출부가 기존 데이터를 잃으면 안 된다.
    """
    og_match = OG_IMAGE_PATTERN.search(html)
    if not og_match:
        return []
    og_base = og_match.group(1).split("?")[0]

    # head의 메타태그 등장은 군집화에서 제외한다 (본문 캐러셀과 멀리 떨어져 있다)
    body_start = html.find("</head>")
    if body_start < 0:
        body_start = 0

    first_position_by_base = {}
    for match in ARTICLE_IMAGE_PATTERN.finditer(html, body_start):
        base = match.group(0).split("?")[0]
        first_position_by_base.setdefault(base, match.start())

    if og_base not in first_position_by_base:
        return [og_base]

    ordered = sorted(first_position_by_base.items(), key=lambda item: item[1])

    clusters = []
    current = [ordered[0]]
    for base, position in ordered[1:]:
        if position - current[-1][1] <= CLUSTER_GAP_BYTES:
            current.append((base, position))
        else:
            clusters.append(current)
            current = [(base, position)]
    clusters.append(current)

    for cluster in clusters:
        if any(base == og_base for base, _ in cluster):
            return [base for base, _ in cluster]
    return [og_base]
