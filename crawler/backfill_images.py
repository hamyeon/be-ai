import json
import time

import requests

from . import config, fetch, normalize, parse


def run() -> None:
    session = requests.Session()

    image_by_url = {}
    for region in config.REGIONS:
        for index, keyword in enumerate(config.SEARCH_KEYWORDS, start=1):
            print(f"[{region['name']} {index}/{len(config.SEARCH_KEYWORDS)}] '{keyword}' 검색 중...")
            try:
                html = fetch.fetch_search_html(keyword, region["in_param"], session)
            except RuntimeError as error:
                print(f"  실패: {error}")
                continue

            for raw_item in parse.extract_ld_json_items(html):
                url = raw_item.get("url")
                images = normalize._parse_images(raw_item.get("image"))
                if url and images:
                    image_by_url[url] = images

            time.sleep(config.REQUEST_DELAY_SECONDS)

    records = []
    if config.RAW_JSONL_PATH.exists():
        with config.RAW_JSONL_PATH.open("r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    records.append(json.loads(line))

    backfilled = 0
    for record in records:
        if not record.get("image_urls"):
            images = image_by_url.get(record.get("item_url"))
            if images:
                record["image_urls"] = images
                backfilled += 1

    with config.RAW_JSONL_PATH.open("w", encoding="utf-8") as f:
        for record in records:
            f.write(json.dumps(record, ensure_ascii=False))
            f.write("\n")

    still_missing = sum(1 for r in records if not r.get("image_urls"))
    print("\n--- 이미지 백필 결과 ---")
    print(f"검색 결과에서 찾은 매물 수: {len(image_by_url)}")
    print(f"기존 레코드 수: {len(records)}")
    print(f"이번에 이미지 채워진 레코드 수: {backfilled}")
    print(f"여전히 이미지 없는 레코드 수: {still_missing}")


if __name__ == "__main__":
    run()
