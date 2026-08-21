from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
OUTPUT_DIR = BASE_DIR / "output"
RAW_JSONL_PATH = OUTPUT_DIR / "daangn_shoes_raw.jsonl"
METRICS_PATH = OUTPUT_DIR / "daangn_shoes_metrics.json"

# search 경로만 사용 (robots.txt: /kr/buy-sell/s/* 카테고리 브라우징 경로는 크롤러에 차단되어 있음)
SEARCH_URL = "https://www.daangn.com/kr/buy-sell/"

REQUEST_DELAY_SECONDS = 1.5
REQUEST_TIMEOUT_SECONDS = 10
MAX_RETRIES = 3
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)

BRANDS = [
    {"canonical": "Nike", "tokens": ["나이키", "nike"]},
    {"canonical": "Adidas", "tokens": ["아디다스", "adidas"]},
    {"canonical": "New Balance", "tokens": ["뉴발란스", "new balance", "뉴발"]},
    {"canonical": "OOFOS", "tokens": ["우포스", "oofos"]},
    {"canonical": "Crocs", "tokens": ["크록스", "crocs"]},
    {"canonical": "Hunter", "tokens": ["헌터", "hunter"]},
    {"canonical": "Puma", "tokens": ["푸마", "퓨마", "puma"]},
    {"canonical": "Asics", "tokens": ["아식스", "asics"]},
    {"canonical": "Dr. Martens", "tokens": ["닥터마틴", "dr martens", "dr. martens"]},
    {"canonical": "Salomon", "tokens": ["살로몬", "salomon"]},
    # 조던은 별도 브랜드가 아니라 Nike로 통일한다(#27 결정, 시세 데이터 표기와 일치).
    # 검색 키워드("조던 신발")를 얻기 위해 항목은 따로 두되 canonical만 Nike다.
    {"canonical": "Nike", "tokens": ["조던", "jordan"]},
    {"canonical": "Mizuno", "tokens": ["미즈노", "mizuno"]},
    {"canonical": "Hoka", "tokens": ["호카", "hoka"]},
]

GENERIC_KEYWORDS = [
    "운동화", "신발", "스니커즈", "구두", "부츠", "샌들", "슬리퍼", "로퍼",
]

# 브랜드명만 검색하면 의류 등 신발 아닌 상품까지 섞여 나와서, 브랜드+"신발" 조합으로 좁힌다.
SEARCH_KEYWORDS = [f'{brand["tokens"][0]} 신발' for brand in BRANDS] + GENERIC_KEYWORDS

# 당근은 "in=동이름-id" 형식의 정확한 지역 id가 있어야 해당 지역 결과가 나오고,
# 이름만 주면 무시되고 기본 지역으로 fallback된다. id는 브라우저에서 직접 동네를 설정해 확인한 값.
REGIONS = [
    {"name": "대현동", "in_param": "대현동-6113"},
    {"name": "방배동", "in_param": "방배동-6127"},
    {"name": "역삼1동", "in_param": "역삼1동-392"},
    {"name": "상도제1동", "in_param": "상도제1동-327"},
    {"name": "영등포동", "in_param": "영등포동-307"},
    {"name": "화양동", "in_param": "화양동-72"},
    {"name": "신림동", "in_param": "신림동-355"},
    {"name": "길동", "in_param": "길동-448"},
    {"name": "행운동", "in_param": "행운동-344"},
    {"name": "진관동", "in_param": "진관동-205"},
    {"name": "문정2동", "in_param": "문정2동-423"},
    {"name": "석촌동", "in_param": "석촌동-417"},
]

CONDITION_DS_KEYWORDS = ["미착용", "새상품", "미개봉", "새제품", "택포함", "ds급"]
CONDITION_S_KEYWORDS = ["몇번", "한두번", "거의새것", "실착 1", "실착1"]
