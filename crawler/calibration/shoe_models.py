# 신발 모델 표 - 매물 제목에서 (브랜드, 모델키)를 찾는다.
#
# build_used_market_prices.py에 있던 것을 공용 모듈로 뺐다(#104). 구성품 계수도
# 같은 모델끼리 묶어야 해서 같은 표가 필요한데, 산출 스크립트가 다른 산출 스크립트를
# import하는 구조가 되면 어느 쪽이 표의 주인인지 알 수 없다.
#
# model_aliases.py와는 다른 표다. 그쪽은 KREAM 참조의 정확한 모델명(airforce1low)에
# 묶여 있어 참조가 없는 모델을 담을 수 없다. 이쪽은 참조와 무관한 자체 키를 쓴다.
# (브랜드, 모델키, 표시명, 별칭들, 브랜드 힌트)
#
# 브랜드 힌트: 별칭이 숫자뿐인 모델(530, 574, 1461 ...)은 제목에 브랜드 단서가 같이
# 있어야 매칭한다. "530"만으로 잡으면 무관한 숫자를 끌어들인다.
#
# "이지"는 일부러 없다. 슬라이드·폼러너·350이 한 단어에 뒤섞여(실측: 482건, 범위
# 1.8만~7.7만) 어느 모델의 시세도 아니게 된다. "이지 350"처럼 특정되는 것만 둔다.
MODELS = [
    ("Nike", "airforce1", "Air Force 1", ["에어포스", "air force 1", "af1"], None),
    ("Nike", "dunklow", "Dunk Low", ["덩크 로우", "덩크로우", "덩크로", "dunk low"], None),
    ("Nike", "dunkhigh", "Dunk High", ["덩크 하이", "덩크하이", "dunk high"], None),
    ("Nike", "airmax90", "Air Max 90", ["에어맥스90", "에어맥스 90", "air max 90"], None),
    ("Nike", "airmax95", "Air Max 95", ["에어맥스95", "에어맥스 95", "air max 95"], None),
    ("Nike", "airmax97", "Air Max 97", ["에어맥스97", "에어맥스 97", "air max 97"], None),
    ("Nike", "jordan1", "Jordan 1", ["조던1", "조던 1", "에어조던1", "jordan 1"], None),
    ("Nike", "cortez", "Cortez", ["코르테즈", "cortez"], None),
    ("Nike", "blazer", "Blazer", ["블레이저", "blazer"], None),
    ("Nike", "vomero", "Vomero", ["보메로", "vomero"], None),
    ("Nike", "pegasus", "Pegasus", ["페가수스", "pegasus"], None),
    ("Nike", "v2krun", "V2K Run", ["v2k"], None),
    ("Adidas", "sambaog", "Samba", ["삼바", "samba"], None),
    ("Adidas", "gazelle", "Gazelle", ["가젤", "gazelle"], None),
    ("Adidas", "superstar", "Superstar", ["슈퍼스타", "superstar"], None),
    ("Adidas", "stansmith", "Stan Smith", ["스탠스미스", "스탠 스미스", "stan smith"], None),
    ("Adidas", "spezial", "Handball Spezial", ["스페지알", "spezial"], None),
    ("Adidas", "yeezy350", "Yeezy Boost 350", ["이지 350", "이지350", "yeezy 350"], None),
    ("New Balance", "nb327", "327", ["327"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb530", "530", ["530"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb574", "574", ["574"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb990", "990", ["990"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb993", "993", ["993"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb1906", "1906", ["1906"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb2002r", "2002R", ["2002r", "2002"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb9060", "9060", ["9060"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb550", "550", ["550"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("Dr. Martens", "dm1460", "1460", ["1460"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("Dr. Martens", "dm1461", "1461", ["1461"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("Asics", "gelkayano14", "Gel-Kayano 14", ["카야노14", "카야노 14", "kayano 14"], None),
    ("Asics", "gel1130", "Gel-1130", ["젤1130", "젤 1130", "gel-1130", "gel 1130"], None),
    ("Salomon", "xt6", "XT-6", ["xt-6", "xt6", "xt 6"], None),
    ("Converse", "chuck70", "Chuck 70", ["척70", "척 70", "chuck 70"], None),
    ("Crocs", "classicclog", "Classic Clog", ["클래식 클로그", "classic clog"], None),
    # --- #89 확대: 미매칭 매물 빈도 분석으로 추가 (scratchpad/coverage_candidates) ---
    # 기준: 표본 10건 이상 + 가격대 응집. "에어맥스"(번호 없음, 130건)와
    # "핏플랍"(브랜드 단위, 137건)은 여러 모델이 한 통에 섞여 제외 - "이지"와 같은 이유.
    ("Nike", "jordan4", "Jordan 4", ["조던4", "조던 4", "jordan 4"], None),
    ("Nike", "airmaxkoko", "Air Max Koko", ["에어맥스 코코", "맥스코코", "맥스 코코", "air max koko"], None),
    ("Dr. Martens", "dmadrian", "Adrian", ["아드리안", "adrian"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("Dr. Martens", "dm2976", "2976 Chelsea", ["2976", "첼시"], ["닥터마틴", "닥마", "마틴", "dr. martens", "dr martens"]),
    ("UGG", "tasman", "Tasman", ["타스만", "태즈먼", "tasman"], None),
    ("Hunter", "hunteroriginal", "Original Rain Boot", ["헌터"], ["레인", "부츠", "장화"]),
    ("Rockfish", "rockfishrain", "Weatherwear Rain Boot", ["락피쉬"], ["레인", "부츠", "장화", "웨더웨어"]),
    ("Skechers", "gowalk", "Go Walk", ["고워크", "gowalk", "go walk"], None),
    # --- #89 2차 확대: 검색어 확대 재크롤링(+4,574건) 후 재분석으로 추가 ---
    ("Vans", "oldskool", "Old Skool", ["올드스쿨", "올드 스쿨", "old skool"], None),
    ("Vans", "authentic", "Authentic", ["어센틱", "authentic"], ["반스", "vans"]),
    ("Vans", "slipon", "Slip-On", ["슬립온", "슬립 온"], ["반스", "vans"]),
    ("Converse", "chucktaylor", "Chuck Taylor", ["척테일러", "척 테일러", "chuck taylor"], None),
    ("Birkenstock", "boston", "Boston", ["보스턴"], ["버켄", "birkenstock"]),
    ("Nike", "jordan3", "Jordan 3", ["조던3", "조던 3", "jordan 3"], None),
    # --- #97 3차 확대: 크롤링 원본 재분석(제목 기준 20건+ 후보). 새 크롤링 없이 별칭만 추가 ---
    # SB 덩크는 일반 덩크와 가격대가 다른 별개 라인이라 분리한다. "sb 덩크 로우"가 "덩크 로우"보다
    # 길어 먼저 검사되므로 SB 매물이 dunklow로 새지 않는다.
    ("Nike", "dunksb", "SB Dunk", ["sb 덩크 로우", "sb덩크로우", "sb 덩크 하이", "sb덩크하이", "덩크 로우 sb", "덩크로우 sb", "sb 덩크", "sb덩크", "덩크 sb", "sb dunk", "dunk sb"], None),
    ("Nike", "jordan11", "Jordan 11", ["조던11", "조던 11", "jordan 11"], None),
    ("Nike", "airmax1", "Air Max 1", ["에어맥스1 ", "에어맥스 1 ", "에어맥스1(", "air max 1 ", "에어맥스 1'", "에어맥스1'"], None),
    ("Adidas", "campus", "Campus", ["캠퍼스", "campus"], ["아디다스", "adidas"]),
    ("Adidas", "forum", "Forum", ["포럼", "forum"], ["아디다스", "adidas"]),
    ("Adidas", "sl72", "SL 72", ["sl72", "sl 72"], None),
    ("Adidas", "ozweego", "Ozweego", ["오즈위고", "ozweego"], None),
    ("Converse", "onestar", "One Star", ["원스타", "one star"], ["컨버스", "converse"]),
    ("Converse", "runstar", "Run Star", ["런스타", "run star"], None),
    ("Vans", "sk8hi", "Sk8-Hi", ["sk8-hi", "sk8 hi", "sk8hi", "스케이트 하이", "스케이트하이"], None),
    ("Asics", "novablast", "Novablast", ["노바블라스트", "novablast"], None),
    ("Salomon", "xt4", "XT-4", ["xt-4", "xt4", "xt 4"], None),
    ("Hoka", "bondi", "Bondi", ["본디", "bondi"], None),
    ("Puma", "speedcat", "Speedcat", ["스피드캣", "speedcat"], None),
    ("Puma", "palermo", "Palermo", ["팔레르모", "palermo"], None),
    ("Puma", "pumasuede", "Suede", ["푸마 스웨이드", "puma suede"], None),
    ("Onitsuka Tiger", "mexico66", "Mexico 66", ["멕시코66", "멕시코 66", "mexico 66"], None),
    ("Birkenstock", "arizona", "Arizona", ["아리조나", "arizona"], ["버켄", "birkenstock"]),
    ("UGG", "uggultramini", "Ultra Mini", ["울트라 미니", "울트라미니", "ultra mini"], ["어그", "ugg"]),
    ("UGG", "uggclassicmini", "Classic Mini", ["클래식 미니", "클래식미니", "classic mini"], ["어그", "ugg"]),
    ("New Balance", "nb480", "480", ["480"], ["뉴발", "뉴 발", "new balance", "nb"]),
    ("New Balance", "nb860", "860", ["860"], ["뉴발", "뉴 발", "new balance", "nb"]),
]

# 긴 별칭부터 검사해야 "에어맥스95"가 "에어맥스 9x" 계열끼리 먹히지 않는다
FLAT = sorted(
    ((alias.lower(), brand, key, hints) for brand, key, _, aliases, hints in MODELS for alias in aliases),
    key=lambda t: -len(t[0]),
)


def find_model(title, description=""):
    """제목(우선)에서 모델을 찾는다. 숫자 별칭은 브랜드 힌트가 함께 있어야 한다."""
    t = (title or "").lower()
    blob = t + " " + (description or "").lower()
    for alias, brand, key, hints in FLAT:
        if alias not in t:
            continue
        if hints and not any(h in blob for h in hints):
            continue
        return brand, key
    return None
