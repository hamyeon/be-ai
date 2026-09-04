# 참조 모델의 컬러웨이 성격 분류.
#
# 비율(중고가 ÷ 정가)을 낼 때 참조 가격이 그 모델의 "보통 가격"이어야 한다.
# 그런데 KREAM 참조는 모델당 컬러웨이가 1~2개뿐이고, 그중 상당수가 한정/프리미엄
# 컬러웨이다. 당근 매물은 온갖 일반 컬러가 섞여 있어서, 그대로 나누면
# "일반 컬러 중고가 ÷ 한정 컬러 정가"가 되어 비율이 실제보다 낮게 나온다.
#
# 실측 예: Air Max 95의 참조는 전부 Neon(282,000원)인데 당근 매물은 코르크·에센셜 등
# 일반 컬러가 대부분(중앙값 40,000원)이라 비율이 0.14로 나왔다. 시장 현상이 아니라
# 참조 편중이다.
#
# 13개 모델뿐이라 수동으로 분류하고 근거를 남긴다. 참조를 재수집해 컬러웨이가 늘면
# 이 분류는 필요 없어진다.
STANDARD = {
    "airforce1low":  "White - 상시 판매되는 기본 컬러",
    "gazelleindoor": "Blue Bird - 일반 라인",
    "993":           "Gray - 993의 대표 상시 컬러",
    "990v4":         "Gray - 990 시리즈 대표 상시 컬러",
    "xt6":           "Black - 기본 컬러",
    # 2026-09-04 크롤러 재수집분 (merge_kream_crawl.py)
    "superstar":     "White Black - 오리지널 기본 컬러 (코어 블랙·슈퍼스타2는 제외했다)",
    "530":           "Steel Grey - 530 대표 상시 컬러",
    "cortez":        "White Varsity Red/White Black - 클래식 컬러 (남성·W 모두 포함)",
    "574":           "Legacy Navy - 574 대표 상시 라인",
}

LIMITED = {
    "airmax95":              "Neon - OG 프리미엄 컬러. 일반 컬러 대비 시세가 크게 높다",
    "airjordan1retrohigh":   "Lost and Found - 한정 발매",
    "airjordan1retrohighog": "Lost and Found - 한정 발매",
    "yeezyboost350v2":       "Zebra - 한정 발매",
    "gelkayano14":           "Cream Black - 참조가 385,000원. 일반 카야노14 시세를 크게 상회",
    "2002r":                 "Protection Pack Rain Cloud - 한정 컬렉션",
    "dunklow":               "Gray Fog / Panda - Panda는 수요가 몰린 컬러라 정가 대비 프리미엄",
    "airjordan4retro":       "Military Black / White Thunder - 인기 컬러",
}

# 참조 사이즈 자체가 쓸 수 없는 경우
UNUSABLE = {
    "sambaog": "참조 5행이 전부 아동 사이즈(190~210)이고 사이즈 표기도 깨져 있다",
}


def quality(model: str) -> str:
    if model in UNUSABLE:
        return "UNUSABLE"
    if model in LIMITED:
        return "LIMITED"
    if model in STANDARD:
        return "STANDARD"
    return "UNKNOWN"


def reason(model: str) -> str:
    return STANDARD.get(model) or LIMITED.get(model) or UNUSABLE.get(model) or "분류되지 않음"
