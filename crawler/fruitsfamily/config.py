from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent  # crawler/
DATA_DIR = BASE_DIR / "data"
IMAGES_DIR = DATA_DIR / "images"
DEBUG_DIR = DATA_DIR / "debug"
PRODUCTS_JSONL_PATH = DATA_DIR / "products.jsonl"
PRODUCTS_CSV_PATH = DATA_DIR / "products.csv"
CHECKPOINT_PATH = DATA_DIR / "checkpoint.json"

SITE_BASE_URL = "https://fruitsfamily.com"
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)

REQUEST_DELAY_MIN_SECONDS = 2.0
REQUEST_DELAY_MAX_SECONDS = 5.0
REQUEST_TIMEOUT_SECONDS = 10
MAX_RETRIES = 3

MAX_PRODUCTS_PER_BRAND_DEFAULT = 20
MAX_IMAGES_PER_PRODUCT_DEFAULT = 3
MAX_CONSECUTIVE_PARSE_FAILURES = 5

# 사이트 자체가 상품에 매기는 공식 카테고리 값 (신발 여부 판단의 최우선 신호).
SHOE_CATEGORY_OFFICIAL = "신발"

# 사이트 상단 메가메뉴의 "신발" 카테고리 링크에서 확인한 subcategoryId 값.
# 남성(35,36,37,38,39,94)과 여성(35,36,37,41,38,39,40,94) 목록의 합집합이다.
# /brand/{slug}?subcategoryIds=...&sort=POPULAR 형태로 브랜드 목록 페이지에
# 그대로 붙이면 그 브랜드의 신발 상품만 서버 단에서 걸러서 내려준다 —
# 실측 결과 정확도 100%(Adidas 80개, Nike 40개 표본 전부 category="신발").
# 이 필터를 쓰면 굳이 의류 상세페이지까지 열어볼 필요가 없어 요청 수가
# 크게 줄어든다 (Adidas 기준 120개 중 18개만 신발이었던 걸, 80개를
# 전부 신발로 바로 찾아냄 — 필터링 전 카테고리 판정이 전수조사가
# 아니었다는 뜻이라, 사이트의 "더보기" 무필터 목록 자체가 인기도 기준으로
# 일부만 노출하는 것으로 추정된다).
SHOE_SUBCATEGORY_IDS = [35, 36, 37, 38, 39, 40, 41, 94]

# 공식 category 필드가 없을 때만 쓰는 폴백 키워드.
SHOE_KEYWORDS = [
    "스니커즈", "운동화", "구두", "로퍼", "샌들", "슬리퍼", "부츠", "워커",
    "클로그", "뮬", "러닝화", "트레킹화", "축구화", "신발", "shoes",
    "sneakers", "boots", "sandals", "loafers", "slides", "clogs",
]

EXCLUDE_KEYWORDS = [
    "의류", "모자", "가방", "지갑", "액세서리", "양말", "신발끈",
    "빈 박스", "영수증", "키링", "인형",
]

# 사이트 브랜드 페이지 slug(/brand/{slug}). Dr. Martens는 "Doc Martens"라는
# 별도 브랜드 페이지도 존재해 두 slug를 모두 순회해야 한다.
BRANDS = [
    {"canonical": "Nike", "slugs": ["Nike"], "aliases": ["나이키", "nike"]},
    {"canonical": "Adidas", "slugs": ["Adidas"], "aliases": ["아디다스", "adidas"]},
    {
        "canonical": "New Balance",
        "slugs": ["New Balance"],
        "aliases": ["뉴발란스", "뉴발", "new balance", "new_balance"],
    },
    {"canonical": "OOFOS", "slugs": ["OOFOS"], "aliases": ["우포스", "oofos"]},
    {"canonical": "Crocs", "slugs": ["Crocs"], "aliases": ["크록스", "crocs"]},
    {"canonical": "Hunter", "slugs": ["Hunter"], "aliases": ["헌터", "hunter"]},
    {"canonical": "Puma", "slugs": ["Puma"], "aliases": ["푸마", "puma"]},
    {"canonical": "ASICS", "slugs": ["Asics"], "aliases": ["아식스", "asics"]},
    {
        "canonical": "Dr. Martens",
        "slugs": ["Dr. Martens", "Doc Martens"],
        "aliases": ["닥터마틴", "닥터 마틴", "dr. martens", "dr martens", "doc martens"],
    },
    {"canonical": "Salomon", "slugs": ["SALOMON"], "aliases": ["살로몬", "salomon"]},
]
