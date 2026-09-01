# 비율 계산에서 제외해야 하는 당근 매물 유형.
#
# "중고가 ÷ 정가" 비율을 낼 때, 분자와 분모가 같은 물건이어야 한다.
# 아래 세 유형은 그 전제를 깬다. S등급 매물 16건을 들여다보다 발견했다.
#
#   2.37  나이키 x 슈프림 에어포스 1 로우      <- 협업 제품. 일반 White 참조보다 비싸다
#   1.67  에어포스1 (블랙260/화이트255)        <- 두 켤레 묶음 가격
#   0.10  나이키 에어포스1 올백 130            <- 유아 사이즈
#
# 각각 비율을 위로/아래로 밀어내 분산을 키운다. 중앙값이 어느 정도 버텨주지만
# 표본이 작은 구간(S등급 n=16)에서는 그대로 결과를 흔든다.
import re

# 협업·한정 라인. 참조가 일반 컬러웨이라 이들과는 애초에 비교 대상이 아니다.
COLLAB = re.compile(
    r'슈프림|supreme|콜라보|콜라브|off\s*-?\s*white|오프화이트|사카이|sacai|'
    r'프라그먼트|fragment|디올|dior|트래비스|travis|자크뮈스|jacquemus|'
    r'마르지엘라|margiela|s-?lab|피스마이너스원|피마원',
    re.IGNORECASE)

# 여러 켤레를 한 가격에 파는 경우. 분자가 여러 물건의 합이 된다.
BUNDLE = re.compile(
    r'[2-9]\s*(켤레|족|개)|일괄|묶음|한번에|모두\s*팔|'
    r'\d{3}\s*/\s*\d{3}',            # "블랙260/화이트255"
    re.IGNORECASE)

# 아동·유아용. 성인화와 가격대가 다르다.
KIDS = re.compile(r'키즈|아동|유아|주니어|kids|어린이|\bGS\b|\bPS\b|\bTD\b', re.IGNORECASE)

# 정상 성인 신발 사이즈 표기 (5 단위)
ADULT_SIZE = re.compile(r'(?<!\d)(2[2-9]\d|3[0-2]\d)(?!\d)')


def exclusion_reason(text: str):
    """제외해야 하면 사유를, 아니면 None을 돌려준다."""
    if not text:
        return None
    if COLLAB.search(text):
        return "협업/한정"
    if BUNDLE.search(text):
        return "묶음판매"
    if KIDS.search(text):
        return "아동용"
    return None
