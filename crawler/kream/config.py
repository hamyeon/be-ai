from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent  # crawler/
DATA_DIR = BASE_DIR / "data"
PRODUCTS_JSONL_PATH = DATA_DIR / "kream_products.jsonl"
TRADES_JSONL_PATH = DATA_DIR / "kream_trades.jsonl"
CHECKPOINT_PATH = DATA_DIR / "kream_checkpoint.json"

SITE_BASE_URL = "https://kream.co.kr"
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)

# robots.txt(2026-08 확인): User-agent: * 에 Allow: / 이고, 금지는 개인 페이지(/my*, /history*)뿐.
# 검색/상품 페이지는 로그인 없이 서버 렌더링으로 내려오며, 여기서 쓰는 URL은 전부 허용 범위다.

# 실측(2026-08-20): KREAM은 짧은 버스트(요청 15개 안팎)만 허용하고 그 뒤로는 한동안
# 전부 500을 반환한다(토큰 버킷식 속도 제한). 2~5초 간격은 한도를 너무 빨리 소진해
# 상품 4~5개마다 냉각 구간에 부딪혔다. 간격을 늘리는 쪽이 결과적으로 더 빨리 끝난다.
REQUEST_DELAY_MIN_SECONDS = 10.0
REQUEST_DELAY_MAX_SECONDS = 20.0
REQUEST_TIMEOUT_SECONDS = 15
MAX_RETRIES = 2

# 상품 하나가 재시도까지 전부 실패하면 냉각 구간에 들어갔다는 뜻이다.
# 바로 다음 요청을 보내면 연속 실패만 쌓이므로, 한도가 다시 차기를 기다린다.
FAILURE_COOLDOWN_SECONDS = 120

MAX_PRODUCTS_PER_KEYWORD_DEFAULT = 20

# 사이트 공식 카테고리(shop_category_name_1d). 검색 결과에서 신발만 걸러낸다.
SHOE_CATEGORY = "신발"

# 검색 키워드와 브랜드 정규화.
# - Jordan은 KREAM에선 별도 브랜드지만 우리 시세 데이터는 Nike로 통일한다 (#27 결정).
#   그래서 검색은 "조던"으로도 따로 하되, 저장 시 brand는 Nike가 된다.
# - canonical은 backend 시세 CSV(kream/ebay_normalized)의 브랜드 표기와 맞춘다.
SEARCH_KEYWORDS = [
    # 참조 커버리지 보강용 모델 검색어 (#61 manual_reference_guide.md 우선순위).
    # KREAM은 짧은 버스트만 허용하므로, 차단 전의 한정된 요청을 브랜드 대표 검색어보다
    # 계수 산출에 바로 쓰이는 모델에 먼저 쓴다. 앞에 있을수록 먼저 수집된다.
    {"keyword": "슈퍼스타", "canonical": "Adidas"},
    {"keyword": "뉴발란스 530", "canonical": "New Balance"},
    {"keyword": "코르테즈", "canonical": "Nike"},
    {"keyword": "뉴발란스 574", "canonical": "New Balance"},
    {"keyword": "닥터마틴 1461", "canonical": "Dr. Martens"},
    {"keyword": "에어맥스 97", "canonical": "Nike"},
    {"keyword": "뉴발란스 327", "canonical": "New Balance"},
    {"keyword": "블레이저 미드", "canonical": "Nike"},
    {"keyword": "보메로", "canonical": "Nike"},
    {"keyword": "스페치알", "canonical": "Adidas"},
    {"keyword": "뉴발란스 1906", "canonical": "New Balance"},
    {"keyword": "페가수스", "canonical": "Nike"},
    {"keyword": "덩크 하이", "canonical": "Nike"},
    {"keyword": "에어맥스 90", "canonical": "Nike"},
    {"keyword": "V2K", "canonical": "Nike"},
    {"keyword": "스탠스미스", "canonical": "Adidas"},
    {"keyword": "척 70", "canonical": "Converse"},
    {"keyword": "나이키", "canonical": "Nike"},
    {"keyword": "조던", "canonical": "Nike"},
    {"keyword": "아디다스", "canonical": "Adidas"},
    {"keyword": "아식스", "canonical": "Asics"},
    {"keyword": "뉴발란스", "canonical": "New Balance"},
    {"keyword": "크록스", "canonical": "Crocs"},
    {"keyword": "미즈노", "canonical": "Mizuno"},
    {"keyword": "호카", "canonical": "Hoka"},
    {"keyword": "푸마", "canonical": "Puma"},
    {"keyword": "살로몬", "canonical": "Salomon"},
]

# KREAM이 응답에 적는 brand_name -> 우리 표기. 여기 없는 브랜드는 수집 대상이 아니다.
BRAND_NORMALIZATION = {
    "Nike": "Nike",
    "Jordan": "Nike",  # Nike로 통일 (#27)
    "Adidas": "Adidas",
    "ASICS": "Asics",
    "Asics": "Asics",
    "New Balance": "New Balance",
    "Crocs": "Crocs",
    "Mizuno": "Mizuno",
    "MIZUNO": "Mizuno",
    "Hoka": "Hoka",
    "HOKA": "Hoka",
    "Hoka One One": "Hoka",
    "Puma": "Puma",
    "PUMA": "Puma",
    "Salomon": "Salomon",
    "SALOMON": "Salomon",
    # 참조 커버리지 보강(#61)에서 추가. 당근 매물 빈도 상위인데 참조가 없던 브랜드.
    "Converse": "Converse",
    "CONVERSE": "Converse",
    "Dr. Martens": "Dr. Martens",
    "Dr.Martens": "Dr. Martens",
}
