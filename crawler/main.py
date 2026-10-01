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
    region_stats = []

    for region in config.REGIONS:
        region_started_at = time.monotonic()
        region_new = 0
        region_raw = 0
        region_duplicates = 0

        for index, keyword in enumerate(config.SEARCH_KEYWORDS, start=1):
            print(f"[{region['name']} {index}/{len(config.SEARCH_KEYWORDS)}] '{keyword}' 검색 중...")

            try:
                html = fetch.fetch_search_html(keyword, region["in_param"], session)
            except RuntimeError as error:
                print(f"  실패: {error}")
                continue

            raw_items = parse.extract_ld_json_items(html)
            region_raw += len(raw_items)
            total_raw_items += len(raw_items)

            for raw_item in raw_items:
                url = raw_item.get("url")
                if url and url in seen_urls:
                    duplicates += 1
                    region_duplicates += 1
                    continue

                record = normalize.normalize_item(
                    raw_item, keyword, region["name"], html, parse.is_crawl_disallowed
                )
                if record is None:
                    dropped += 1
                    continue

                seen_urls.add(record["item_url"])
                new_records.append(record)
                region_new += 1

            time.sleep(config.REQUEST_DELAY_SECONDS)

        region_stats.append({
            "region": region["name"],
            "raw_items_seen": region_raw,
            "new_records_saved": region_new,
            "duplicates_skipped": region_duplicates,
            "elapsed_seconds": round(time.monotonic() - region_started_at, 2),
        })

    # 이미지 확장(게시물 사진 전체 수집)은 여기서 하지 않는다.
    # 신규 매물이 수만 건이면 확장에 몇 시간이 걸리는데, 저장 전에 하면 그동안 전부
    # 메모리에만 있어 중단 시 유실된다(실측: 신규 19,207건 = 약 10시간 분량).
    # 먼저 저장하고, 확장은 중단해도 재개되는 백필로 처리한다.
    storage.append_jsonl(config.RAW_JSONL_PATH, new_records)
    single_image = sum(1 for r in new_records if len(r.get("image_urls") or []) == 1)
    print(f"\n이미지가 1장뿐인 신규 매물: {single_image}건")
    print("게시물 사진 전체로 확장하려면:")
    print("  python -m crawler.backfill_multi_images    # _N 인덱스 체계 (HEAD 프로빙, 빠름)")
    print("  python -m crawler.backfill_detail_images   # 나머지 (상세 페이지, 오래 걸림)")

    new_count = len(new_records)
    missing_brand = sum(1 for r in new_records if r["brand_guess"] is None)
    missing_condition = sum(1 for r in new_records if r["condition_grade_guess"] == "UNKNOWN")
    missing_box = sum(1 for r in new_records if r["box_included_guess"] is None)
    missing_image = sum(1 for r in new_records if not r["image_urls"])

    metrics = {
        "regions_searched": len(config.REGIONS),
        "keywords_searched": len(config.SEARCH_KEYWORDS),
        "raw_items_seen": total_raw_items,
        "new_records_saved": new_count,
        "duplicates_skipped": duplicates,
        "dropped_missing_price_or_disallowed": dropped,
        "duplicate_rate": round(duplicates / total_raw_items, 3) if total_raw_items else None,
        "missing_brand_guess_rate": round(missing_brand / new_count, 3) if new_count else None,
        "missing_condition_grade_rate": round(missing_condition / new_count, 3) if new_count else None,
        "missing_box_included_rate": round(missing_box / new_count, 3) if new_count else None,
        "missing_image_rate": round(missing_image / new_count, 3) if new_count else None,
        "elapsed_seconds": round(time.monotonic() - started_at, 2),
        "by_region": region_stats,
    }

    config.METRICS_PATH.parent.mkdir(parents=True, exist_ok=True)
    with config.METRICS_PATH.open("w", encoding="utf-8") as f:
        json.dump(metrics, f, ensure_ascii=False, indent=2)

    print("\n--- 수집 결과 ---")
    for key, value in metrics.items():
        print(f"{key}: {value}")


if __name__ == "__main__":
    run()
