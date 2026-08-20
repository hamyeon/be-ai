"""기존 당근 수집 데이터 중 비인덱스 체계 매물의 이미지를 상세 페이지에서 확장하는 백필.

backfill_multi_images(_N 프로빙)가 못 다루는 나머지 매물이 대상이다. 매물당 상세 페이지
요청 1번 + 예의상 지연이 들어 오래 걸린다(약 2,000건 = 1시간 이상). 이미 2장 이상인
레코드는 건너뛰므로 중단 후 재실행해도 안전하다.

삭제된 매물은 페이지 요청이 실패해 1장 그대로 남는다 - 데이터를 잃지 않는다.

실행:
    python -m crawler.backfill_detail_images            # 전체
    python -m crawler.backfill_detail_images --limit 20 # 동작 확인용
"""
import argparse
import json
import time

import requests

from . import config, images_enrich, multi_images


def run(limit=None) -> None:
    records = []
    with config.RAW_JSONL_PATH.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))

    session = requests.Session()

    candidates = [
        r for r in records
        if len(r.get("image_urls") or []) == 1
        and multi_images.indexed_base(r["image_urls"][0].split("?")[0]) is None
    ]
    if limit is not None:
        candidates = candidates[:limit]

    print(f"전체 레코드: {len(records)}")
    print(f"상세 페이지로 확장할 비인덱스 레코드: {len(candidates)}")

    expanded = 0
    started_at = time.monotonic()
    for index, record in enumerate(candidates, start=1):
        if images_enrich.enrich_record_images(record, session):
            expanded += 1
        if index % 50 == 0:
            elapsed = time.monotonic() - started_at
            print(f"  [{index}/{len(candidates)}] 확장 {expanded}건, 경과 {elapsed:.0f}초")

    with config.RAW_JSONL_PATH.open("w", encoding="utf-8") as f:
        for record in records:
            f.write(json.dumps(record, ensure_ascii=False))
            f.write("\n")

    counts = [len(r.get("image_urls") or []) for r in records]
    print("\n--- 상세 페이지 이미지 백필 결과 ---")
    print(f"이번에 2장 이상으로 확장된 레코드: {expanded}")
    print(f"전체 중 2장 이상인 레코드: {sum(1 for c in counts if c > 1)}/{len(records)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=None, help="확인할 레코드 수 상한 (동작 확인용)")
    run(**vars(parser.parse_args()))
