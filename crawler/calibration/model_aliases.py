# 한글 제품명 -> 영문 모델 키 매핑.
#
# 당근 매물 제목은 한글("나이키 에어포스1")인데 KREAM/eBay 참조는 영문("Air Force 1 Low")이다.
# 문자열만으로는 겹치지 않아, 매핑 없이는 22,543건 중 거의 아무것도 매칭되지 않는다.
#
# 규칙:
#   - 키는 소문자로 비교한다. 공백/하이픈 변형은 별도 항목으로 나열한다.
#   - 긴 표기를 먼저 검사한다. "에어맥스95"가 "에어맥스"보다 앞서야 한다.
#   - 값은 KREAM/eBay 모델명을 소문자 영숫자만 남긴 형태와 같아야 한다.
MODEL_ALIASES = {
    # Nike
    "에어포스1": "airforce1low", "에어포스 1": "airforce1low", "에어포스원": "airforce1low",
    "에어포스": "airforce1low", "air force 1": "airforce1low", "af1": "airforce1low",
    "코르테즈": "cortez", "cortez": "cortez",
    "덩크로우": "dunklow", "덩크 로우": "dunklow", "덩크로": "dunklow",
    "dunk low": "dunklow", "덩크": "dunklow",
    "에어맥스95": "airmax95", "에어맥스 95": "airmax95", "air max 95": "airmax95", "am95": "airmax95",
    # Jordan (참조 CSV는 brand=Nike, model=Air Jordan ...)
    "조던1 레트로 하이": "airjordan1retrohighog", "에어조던1": "airjordan1retrohighog",
    "조던1": "airjordan1retrohighog", "air jordan 1": "airjordan1retrohighog",
    "조던4": "airjordan4retro", "에어조던4": "airjordan4retro", "air jordan 4": "airjordan4retro",
    # Adidas
    "삼바og": "sambaog", "삼바 og": "sambaog", "samba og": "sambaog", "삼바": "sambaog",
    # 슈퍼스타 2는 별개 저가 라인이다. 긴 표기가 먼저 검사되므로 "슈퍼스타 2"를
    # 참조 없는 키로 보내 일반 슈퍼스타 참조에 붙는 것을 막는다.
    "슈퍼스타 2": "superstar2", "슈퍼스타2": "superstar2", "superstar 2": "superstar2",
    "슈퍼스타": "superstar", "superstar": "superstar", "슈퍼스타즈": "superstar",
    "가젤인도어": "gazelleindoor", "가젤 인도어": "gazelleindoor",
    "gazelle indoor": "gazelleindoor", "가젤": "gazelleindoor",
    "이지350": "yeezyboost350v2", "이지 350": "yeezyboost350v2", "yeezy 350": "yeezyboost350v2",
    # New Balance
    "2002r": "2002r", "2002 r": "2002r",
    "990v4": "990v4", "990 v4": "990v4",
    "993": "993",
    # "530"만 쓰면 사이즈·가격 등 무관한 숫자에 붙는다. 브랜드가 함께 있는 표기만 인정.
    "뉴발란스 530": "530", "뉴발란스530": "530", "뉴발 530": "530",
    "뉴발530": "530", "nb530": "530", "new balance 530": "530", "nb 530": "530",
    # ASICS
    "젤카야노14": "gelkayano14", "젤 카야노 14": "gelkayano14",
    "카야노14": "gelkayano14", "카야노 14": "gelkayano14", "gel kayano 14": "gelkayano14",
    # Salomon
    "xt6": "xt6", "xt-6": "xt6", "xt 6": "xt6",
}

# 긴 표기부터 검사해야 "에어맥스95"가 "에어맥스"에 먹히지 않는다.
ALIASES_BY_LENGTH = sorted(MODEL_ALIASES.items(), key=lambda kv: -len(kv[0]))


def find_model(text: str):
    """제목+설명에서 참조 모델 키를 찾는다. 없으면 None."""
    if not text:
        return None
    lowered = text.lower()
    for alias, model in ALIASES_BY_LENGTH:
        if alias in lowered:
            return model
    return None
