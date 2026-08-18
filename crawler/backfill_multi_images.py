"""기존 당근 수집 데이터의 이미지 목록을 다중 이미지로 확장하는 백필. (backfill_images.py와 같은 패턴)

이미 이미지가 2장 이상인 레코드와 비인덱스 체계 레코드는 건너뛰므로 몇 번을 다시 돌려도
안전하다(중단 후 재실행 가능).

실행:
    python -m crawler.backfill_multi_images            # 전체
    python -m crawler.backfill_multi_images --limit 20 # 앞에서 20건만 (동작 확인용)
"""
import argparse
import json
import time

import requests

from . import config, multi_images


def run(limit=None) -> None:
    records = []
    with config.RAW_JSONL_PATH.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))

    session = requests.Session()
    session.headers["User-Agent"] = config.USER_AGENT

    candidates = [
        r for r in records
        if len(r.get("image_urls") or []) == 1
        and multi_images.indexed_base(r["image_urls"][0].split("?")[0]) is not None
    ]
    if limit is not None:
        candidates = candidates[:limit]

    print(f"전체 레코드: {len(records)}")
    print(f"이번에 확인할 인덱스 체계 레코드: {len(candidates)}")

    expanded = 0
    started_at = time.monotonic()
    for i, record in enumerate(candidates, start=1):
        # 검색 결과의 URL에는 300x300 썸네일 쿼리가 붙어 있다. 원본으로 프로빙한다.
        first_url = record["image_urls"][0].split("?")[0]
        urls = multi_images.probe_images(first_url, session)
        if len(urls) > 1:
            record["image_urls"] = urls
            expanded += 1
        if i % 50 == 0:
            elapsed = time.monotonic() - started_at
            print(f"  [{i}/{len(candidates)}] 확장 {expanded}건, 경과 {elapsed:.0f}초")

    with config.RAW_JSONL_PATH.open("w", encoding="utf-8") as f:
        for record in records:
            f.write(json.dumps(record, ensure_ascii=False))
            f.write("\n")

    counts = [len(r.get("image_urls") or []) for r in records]
    multi = sum(1 for c in counts if c > 1)
    print("\n--- 다중 이미지 백필 결과 ---")
    print(f"이번에 2장 이상으로 확장된 레코드: {expanded}")
    print(f"전체 중 2장 이상인 레코드: {multi}/{len(records)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=None, help="확인할 레코드 수 상한 (동작 확인용)")
    run(**vars(parser.parse_args()))
