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
    {"canonical": "Jordan", "tokens": ["조던", "jordan"]},
    {"canonical": "Adidas", "tokens": ["아디다스", "adidas"]},
    {"canonical": "New Balance", "tokens": ["뉴발란스", "new balance", "뉴발"]},
    {"canonical": "Converse", "tokens": ["컨버스", "converse"]},
    {"canonical": "Vans", "tokens": ["반스", "vans"]},
    {"canonical": "Asics", "tokens": ["아식스", "asics"]},
    {"canonical": "Salomon", "tokens": ["살로몬", "salomon"]},
    {"canonical": "Puma", "tokens": ["푸마", "퓨마", "puma"]},
    {"canonical": "Reebok", "tokens": ["리복", "reebok"]},
    {"canonical": "Crocs", "tokens": ["크록스", "crocs"]},
    {"canonical": "Timberland", "tokens": ["팀버랜드", "timberland"]},
    {"canonical": "Dr. Martens", "tokens": ["닥터마틴", "dr martens", "dr. martens"]},
    {"canonical": "Under Armour", "tokens": ["언더아머", "under armour"]},
    {"canonical": "Hoka", "tokens": ["호카", "hoka"]},
    {"canonical": "Brooks", "tokens": ["브룩스", "brooks"]},
    {"canonical": "On", "tokens": ["온러닝", "on running"]},
    {"canonical": "Fila", "tokens": ["휠라", "fila"]},
    {"canonical": "Skechers", "tokens": ["스케쳐스", "스케처스", "skechers"]},
    {"canonical": "Hunter", "tokens": ["헌터", "hunter"]},
    {"canonical": "Onitsuka Tiger", "tokens": ["오니츠카", "onitsuka"]},
    {"canonical": "TOMS", "tokens": ["탐스", "toms"]},
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
]

CONDITION_DS_KEYWORDS = ["미착용", "새상품", "미개봉", "새제품", "택포함", "ds급"]
CONDITION_S_KEYWORDS = ["몇번", "한두번", "거의새것", "실착 1", "실착1"]
