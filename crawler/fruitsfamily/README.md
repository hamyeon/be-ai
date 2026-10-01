# FruitsFamily 신발 크롤러

후루츠패밀리(https://fruitsfamily.com) 공개 웹페이지에서 지정한 10개 브랜드의
**신발** 상품만 수집한다. 수집한 데이터는 사용자가 업로드한 신발 사진의 상태와
예상 가격을 AI가 분석할 때 참고하는 유사 매물 데이터로 쓰인다.

기존 `crawler/`의 당근마켓 크롤러와는 완전히 분리된 별도 서브패키지이며
(`config.py`, `main.py` 등 파일명이 겹쳐서), 당근마켓 크롤러 코드는 전혀
건드리지 않았다.

## 조사 결과 요약

- **robots.txt**: `https://fruitsfamily.com/robots.txt`는 모든 User-agent에
  `Disallow` 없이 전면 허용되어 있다. 크롤링을 막아둔 경로가 없다.
- **로그인/CAPTCHA**: 조사한 모든 페이지(브랜드 목록, 상품 상세, sitemap)가
  로그인 없이 접근 가능했고 CAPTCHA는 발견되지 않았다.
- **비공개 API를 직접 호출하지 않음**: 사이트는 내부적으로 GraphQL(Apollo)을
  쓰지만, 이 엔드포인트를 직접 호출하지 않았다. 상품 목록의 "더보기" 로딩은
  Playwright로 실제 버튼을 클릭해서(`button.InfiniteScroll-more`) 처리하고,
  상품 상세 페이지는 서버가 렌더링해서 내려주는 정적 HTML만 그대로 읽는다.
- **서버 단에서 신발 카테고리만 걸러서 요청함**: 사이트 상단 메가메뉴의 "신발"
  링크가 `/search?gender=MEN&subcategoryIds=35,36,37,38,39,94` 형태로
  연결되는 걸 확인했고, 이 `subcategoryIds` 쿼리를 `/brand/{slug}` 뒤에도
  그대로 붙일 수 있다(`config.SHOE_SUBCATEGORY_IDS`, 남녀 목록 합집합).
  즉 `/brand/Adidas?subcategoryIds=35,36,37,38,39,40,41,94&sort=POPULAR`처럼
  브랜드 목록 페이지 자체에 신발 카테고리 필터를 걸어서 요청한다
  (`listing.py`). 실측 결과 정확도 100%였고(Adidas 80개, Nike 40개 표본
  전부 category="신발"), 오히려 필터 없이 인기순 "더보기"만 탄 경우보다
  더 많은 신발을 찾아냈다 — 사이트의 무필터 "더보기"가 인기도 기준으로 일부
  상품만 노출하는 것으로 보인다(Adidas 기준: 무필터 120개 중 18개만 신발이었던
  게, 필터를 걸면 80개를 전부 신발로 찾아낸다). URL 자체가 브랜드 페이지의
  일반 사용자도 쓸 수 있는 공개 쿼리 파라미터라, 비공개 API를 호출하는 게
  아니라 사이트가 제공하는 필터 기능을 그대로 쓰는 것이다.
- **상품 상세 페이지 파싱 방식**: 상세 페이지는 완전히 서버사이드 렌더링되며,
  `<script id="__APOLLO_STATE__" type="application/json">`에 사이트가 직접
  쓰는 하이드레이션용 JSON이 그대로 들어있다. 여기서 브랜드, **공식
  category("신발"/"상의"/"아우터"/"가방" 등 사이트가 직접 분류한 값)**,
  가격(정수), 할인 전 가격, 판매상태, 사이즈, 설명, 이미지, 상품 고유 ID까지
  CSS 셀렉터 없이 안정적으로 뽑아낼 수 있다. 신발 판정은 목록 단계에서 이미
  신발 카테고리로 필터링되지만, 상세 파싱 후에도 이 공식 category로 다시
  한번 확인하고(`shoe_filter.py`), category가 없을 때만 키워드로 폴백한다
  (2중 확인이라 안전).
- **판매완료(품절) 상태 확인됨**: 표본을 넓혀 확인한 결과 실제 품절 상품의
  `status` 값은 `"sold"`다 (OOFOS 브랜드에서 다수 확인). `is_sold`는
  `status != "selling"`이면 True로 판정하도록 구현했으므로 이미 `"sold"`를
  올바르게 잡아낸다. 가격 분석 관점에서는 판매완료 상품이 활성 매물 호가보다
  더 신뢰도 높은(실제 거래 성사) 참고 데이터일 수 있어, 기본값을 **품절 상품도
  포함하는 쪽**으로 뒀다 (`--exclude-sold`로 제외 가능).

## 실행 방법

```bash
pip install -r ../requirements.txt
python -m playwright install chromium   # 최초 1회

python -m crawler.fruitsfamily.main \
  --brands nike adidas new_balance \
  --max-products-per-brand 20 \
  --max-images-per-product 3 \
  --download-images \
  --output-format jsonl,csv
```

브랜드를 지정하지 않으면 10개 전체를 대상으로 한다.

### CLI 옵션

| 옵션 | 기본값 | 설명 |
|---|---|---|
| `--brands` | 전체 10개 | 수집할 브랜드 (한글/영문 별칭, `new_balance`처럼 언더스코어도 인식) |
| `--max-products-per-brand` | 20 | 브랜드당 새로 저장할 신발 상품 수 목표 |
| `--max-pages-per-brand` | 무제한 | "더보기" 클릭 최대 횟수 |
| `--max-images-per-product` | 3 | 상품당 다운로드할 이미지 수 |
| `--include-sold` / `--exclude-sold` | include(기본 포함) | 판매완료 상품 포함 여부. 판매완료 = 실제 거래 성사인 경우가 많아 가격 참고 데이터로 유용하다고 보고 기본 포함으로 뒀다 |
| `--download-images` | 끔 | 이미지 로컬 다운로드 여부 |
| `--output-format` | `jsonl,csv` | 쉼표로 구분된 출력 형식 |
| `--headless` | true | `--headless false`로 브라우저 창을 띄워서 디버깅 가능 |
| `--request-delay-min` / `--request-delay-max` | 2.0 / 5.0 | 요청 간 랜덤 지연(초) |
| `--resume` | 끔 | 이전 체크포인트에서 이어서 실행 (완료된 브랜드는 건너뜀) |

## 크롤링 정책

- 요청 간격 2~5초 랜덤 지연 (신발 여부와 무관하게, 실제로 상세페이지를
  요청할 때마다 적용 — 신발이 아니라고 걸러지는 상품이라도 예외 없음).
- 동시 요청 없음 (전부 순차 처리).
- 최대 재시도 3회, 실패마다 대기 시간 증가.
- `429` 발생 시 60초 × 시도횟수만큼 대기 후 재시도.
- `403` 또는 목록 페이지에서 `429` 감지 시 `BlockedError`로 실행 전체를
  즉시 중단한다 (해당 시점까지 수집한 데이터는 저장하고 종료).
- 연속 파싱 실패가 5회 이상 발생하면 DOM 변경 가능성으로 보고 실행을
  중단한다 (`config.MAX_CONSECUTIVE_PARSE_FAILURES`).
- 프록시/계정 회전, fingerprint 위조, CAPTCHA 자동 해결 등 차단 우회 로직은
  구현하지 않았다.

## 데이터 모델

`crawler/data/products.jsonl` / `products.csv`에 저장되는 필드:

```
source, source_product_id, brand, original_brand_text, item_title,
price_krw, original_price, size, description, is_sold, item_url,
collected_at, image_urls, local_image_paths
```

기존 당근마켓 크롤러(`crawler/normalize.py`)와 필드명 스타일을 맞췄다
(`item_url`, `item_title`, `price_krw`, `collected_at`, `source: "FRUITSFAMILY"`).
단, `brand`는 당근의 `brand_guess`와 달리 이름을 그대로 뒀다 — 당근 쪽은
키워드로 "추측"한 값이라 `_guess`가 붙어있고, 이쪽은 사이트가 직접 제공하는
확정값이라 성격이 달라서다. 두 소스를 함께 쓰는 쪽(가격 계산 로직)에서는
`row.get("brand") or row.get("brand_guess")` 한 줄로 흡수하면 된다.

### 상품 식별 및 중복 방지

1순위: 사이트 상품 ID (`source_product_id`), 2순위: 정규화된 상품 URL
(끝 슬래시·쿼리스트링 제거). 이미 저장된 상품과 같은 키를 만나면 새 값으로
덮어써서(upsert) 가격/판매상태 변경을 반영하되, 행이 중복 생성되지는 않는다.
단, **크롤러 자체는 기본적으로 이미 만난 상품 URL을 다시 요청하지 않고
건너뛴다** — 즉 이번 구현에서 upsert는 "재저장해도 중복 안 생김"을
보장하는 안전장치이고, 실제로 가격 변경을 감지하려면 별도의 재수집(예:
주기적 전체 재크롤링) 트리거가 필요하다 (이번 스코프에는 없음).

### 이미지

상품당 최대 3장, `data/images/{brand_slug}/{상품ID 또는 URL해시}_{순번}.{ext}`
로 저장한다 (예: `data/images/new_balance/12345_01.jpg`). Content-Type과
URL 확장자가 둘 다 이미지가 아니면 저장하지 않으며, 개별 이미지 다운로드
실패는 로그만 남기고 상품 자체는 계속 저장한다.

## 알려진 한계 / 확인되지 않은 사항

1. **`--max-products-per-brand`는 브랜드 전체 보유량이 아니라 "이번 실행에서
   새로 저장할 목표"다**: 이미 20개를 모은 브랜드에 대해 같은 명령을 다시
   실행하면, 중복은 안 생기지만 새로 20개를 더 채우려고 시도한다(누적
   증가). 브랜드별 총량을 고정 상한으로 두려면 이 옵션의 의미를 바꾸는
   추가 작업이 필요하다.
2. **"더보기" 버튼 셀렉터 의존**: `button.InfiniteScroll-more`라는 CSS
   클래스가 사이트 프론트엔드 개편으로 바뀌면 목록 수집이 멈춘다(상세
   페이지 파싱과 달리 이건 재시도 없이 그 브랜드의 목록 수집을 종료함).
3. **재개(`--resume`)는 완료된 브랜드 스킵 위주**: 이 사이트의 "더보기"는
   서버가 페이지 번호/커서를 URL로 노출하지 않아서, 진행 중이던 브랜드를
   정확히 중단 지점부터 재개하지 않는다. 대신 처음부터 다시 "더보기"를
   누르되, 이미 저장된 상품 URL은 결과적으로 건너뛰므로 데이터 중복은
   생기지 않는다 (다만 이미 본 후보 링크를 다시 스캔하는 시간은 든다).
4. **크롤러 데이터와 백엔드 연동 없음**: 이 크롤러는 `crawler/data/` 아래
   파일로만 결과를 남기고, 백엔드 코드에서 이 데이터를 읽어가는 부분은
   이번 작업 범위에 포함되지 않는다.
5. **이미지는 S3에 올리지 않음(의도적)**: 백엔드에 이미 사용자 업로드 사진용
   S3 연동(`S3Config`, `S3UploaderService`)이 있어서, 이 크롤러가 모은
   참고용 이미지도 결국 S3로 옮겨질 가능성이 높다. 하지만 그 업로드는
   이 크롤러 스코프에 넣지 않기로 했다 — `crawler/data/images/`에 로컬
   파일로만 남기고, S3로 옮기는 건 AI 파이프라인을 담당하는 쪽에서 별도로
   처리하는 걸로 결정했다 (Python `boto3` 의존성 추가, AWS 자격증명 연동이
   필요해 크롤러와는 별개 작업으로 보는 게 맞다고 판단).

## 테스트

```bash
python -m unittest discover -s crawler/tests -t . -v
```

실제 사이트 HTML을 저장해둔 fixture(`crawler/tests/fixtures/`) 기반 단위
테스트만 있고, 실행 중 실제 사이트에 요청을 보내는 테스트는 없다. 품절
케이스와 필수 필드 누락 케이스는 실제 사례를 찾지 못해 합성(synthetic)
fixture로 대체했다 (파일 상단 주석에 명시).
