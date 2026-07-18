import json
import time

import requests

from . import config, fetch, normalize, parse, storage


def run() -> None:
    started_at = time.monotonic()
    session = requests.Session()

    seen_urls = storage.load_existing_urls(config.RAW_JSONL_PATH)

    new_records = []
    total_raw_items = 0
    duplicates = 0
    dropped = 0

    for index, keyword in enumerate(config.SEARCH_KEYWORDS, start=1):
        print(f"[{index}/{len(config.SEARCH_KEYWORDS)}] '{keyword}' 검색 중...")

        try:
            html = fetch.fetch_search_html(keyword, session)
        except RuntimeError as error:
            print(f"  실패: {error}")
            continue

        raw_items = parse.extract_ld_json_items(html)
        total_raw_items += len(raw_items)

        for raw_item in raw_items:
            url = raw_item.get("url")
            if url and url in seen_urls:
                duplicates += 1
                continue

            record = normalize.normalize_item(raw_item, keyword, html, parse.is_crawl_disallowed)
            if record is None:
                dropped += 1
                continue

            seen_urls.add(record["item_url"])
            new_records.append(record)

        time.sleep(config.REQUEST_DELAY_SECONDS)

    storage.append_jsonl(config.RAW_JSONL_PATH, new_records)

    new_count = len(new_records)
    missing_brand = sum(1 for r in new_records if r["brand_guess"] is None)
    missing_condition = sum(1 for r in new_records if r["condition_grade_guess"] == "UNKNOWN")
    missing_box = sum(1 for r in new_records if r["box_included_guess"] is None)

    metrics = {
        "keywords_searched": len(config.SEARCH_KEYWORDS),
        "raw_items_seen": total_raw_items,
        "new_records_saved": new_count,
        "duplicates_skipped": duplicates,
        "dropped_missing_price_or_disallowed": dropped,
        "duplicate_rate": round(duplicates / total_raw_items, 3) if total_raw_items else None,
        "missing_brand_guess_rate": round(missing_brand / new_count, 3) if new_count else None,
        "missing_condition_grade_rate": round(missing_condition / new_count, 3) if new_count else None,
        "missing_box_included_rate": round(missing_box / new_count, 3) if new_count else None,
        "elapsed_seconds": round(time.monotonic() - started_at, 2),
    }

    config.METRICS_PATH.parent.mkdir(parents=True, exist_ok=True)
    with config.METRICS_PATH.open("w", encoding="utf-8") as f:
        json.dump(metrics, f, ensure_ascii=False, indent=2)

    print("\n--- 수집 결과 ---")
    for key, value in metrics.items():
        print(f"{key}: {value}")


if __name__ == "__main__":
    run()
