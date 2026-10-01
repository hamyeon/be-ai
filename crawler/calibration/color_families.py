# 색상 표기(한/영)를 색 계열(family)로 정규화한다 (#93).
#
# 왜 계열인가: 같은 색을 "그레이"/"회색"/"grey"/"gray"/"차콜"로 제각각 적는다.
# 문자로 비교하면 전부 다른 색이 되어 (모델, 색상) 시세가 조각난다.
# 표기를 계열로 접으면 비교가 계열 대 계열이 되어 표기 흔들림이 사라진다.
#
# 하네스(#90)의 ColorFamilies.java와 같은 계열 체계를 쓴다 - Vision 채점과
# 시세 산출이 다른 색 기준을 쓰면 측정과 운영이 어긋난다.
#
# 단어 단위로만 매칭한다("titanium"이 "tan"에 걸리는 오탐 방지).
# 한글은 단어 경계가 애매해서 사전 표기가 텍스트에 등장하는지 보되,
# 긴 표기부터 검사한다("라이트그레이"가 "그레이"보다 먼저).
import re

# 표기 -> 계열들. 두 계열에 걸치는 색(아이보리, 카키 등)은 둘 다 준다.
TOKEN_TO_FAMILIES = {
    # black
    "black": {"black"}, "블랙": {"black"}, "검정": {"black"}, "검은": {"black"},
    "흑색": {"black"}, "먹색": {"black"},
    # white
    "white": {"white"}, "화이트": {"white"}, "흰색": {"white"}, "하양": {"white"},
    "하얀": {"white"}, "백색": {"white"}, "sail": {"white"}, "세일": {"white"},
    "offwhite": {"white", "cream"}, "오프화이트": {"white", "cream"},
    "ivory": {"white", "cream"}, "아이보리": {"white", "cream"},
    # cream
    "cream": {"cream"}, "크림": {"cream"}, "beige": {"cream"}, "베이지": {"cream"},
    "sand": {"cream"}, "샌드": {"cream"}, "oatmeal": {"cream"}, "오트밀": {"cream"},
    "tan": {"cream", "brown"}, "탄색": {"cream", "brown"},
    # brown
    "brown": {"brown"}, "브라운": {"brown"}, "갈색": {"brown"},
    "chocolate": {"brown"}, "초콜릿": {"brown"}, "초코": {"brown"},
    "mocha": {"brown"}, "모카": {"brown"}, "gum": {"brown"}, "검솔": {"brown"},
    # grey
    "grey": {"grey"}, "gray": {"grey"}, "그레이": {"grey"}, "회색": {"grey"},
    "charcoal": {"grey"}, "차콜": {"grey"}, "챠콜": {"grey"},
    "steel": {"grey"}, "스틸": {"grey"}, "smoke": {"grey"}, "스모크": {"grey"},
    # silver (회색 계열로도 인정 - 실버문 vs 회색 논쟁 방지)
    "silver": {"silver", "grey"}, "실버": {"silver", "grey"}, "은색": {"silver", "grey"},
    "metallic": {"silver"}, "메탈릭": {"silver"},
    # gold / yellow
    "gold": {"gold", "yellow"}, "골드": {"gold", "yellow"}, "금색": {"gold", "yellow"},
    "yellow": {"yellow"}, "옐로우": {"yellow"}, "옐로": {"yellow"}, "노랑": {"yellow"},
    "노란": {"yellow"},
    # orange
    "orange": {"orange"}, "오렌지": {"orange"}, "주황": {"orange"},
    # red
    "red": {"red"}, "레드": {"red"}, "빨강": {"red"}, "빨간": {"red"},
    "crimson": {"red"}, "크림슨": {"red"},
    "burgundy": {"red", "brown"}, "버건디": {"red", "brown"}, "와인": {"red", "brown"},
    # blue
    "blue": {"blue"}, "블루": {"blue"}, "파랑": {"blue"}, "파란": {"blue"},
    "navy": {"blue"}, "네이비": {"blue"}, "남색": {"blue"}, "곤색": {"blue"},
    "royal": {"blue"}, "로얄": {"blue"}, "sky": {"blue"}, "하늘색": {"blue"},
    "denim": {"blue"}, "데님": {"blue"},
    # green
    "green": {"green"}, "그린": {"green"}, "초록": {"green"}, "녹색": {"green"},
    "mint": {"green"}, "민트": {"green"}, "forest": {"green"}, "포레스트": {"green"},
    "olive": {"green", "brown"}, "올리브": {"green", "brown"},
    "khaki": {"green", "brown"}, "카키": {"green", "brown"},
    # pink
    "pink": {"pink"}, "핑크": {"pink"}, "분홍": {"pink"}, "rose": {"pink"}, "로즈": {"pink"},
    # purple
    "purple": {"purple"}, "퍼플": {"purple"}, "보라": {"purple"}, "violet": {"purple"},
}

# 긴 표기부터 검사한다. "오프화이트"가 "화이트"보다 먼저 잡혀야
# {white,cream}이 {white}로 뭉개지지 않는다.
_SORTED_TOKENS = sorted(TOKEN_TO_FAMILIES, key=len, reverse=True)
_WORD = re.compile(r"[a-z]+")


def families(text):
    """텍스트에 등장하는 색 계열의 집합. 없으면 빈 집합."""
    if not text:
        return set()
    lowered = text.lower()
    found = set()
    consumed = lowered
    # 한글 표기: 긴 것부터 찾고, 찾은 자리는 지워서 부분 표기 중복 매칭을 막는다
    for token in _SORTED_TOKENS:
        if not token.isascii() and token in consumed:
            found.update(TOKEN_TO_FAMILIES[token])
            consumed = consumed.replace(token, " ")
    # 영문 표기: 단어 단위로만 (titanium이 tan에 걸리면 안 된다)
    for word in _WORD.findall(lowered):
        if word in TOKEN_TO_FAMILIES:
            found.update(TOKEN_TO_FAMILIES[word])
    return found


def primary_family(text):
    """대표 계열 하나. 여러 계열이면 사전 순으로 안정적으로 고른다(투톤은 조합 키로 다룰 것)."""
    fams = families(text)
    return min(fams) if fams else None
