import argparse
import logging
import random
import time
from datetime import datetime, timedelta, timezone

import requests

from . import brand_aliases, checkpoint, config, debug_log, images, listing, parser, shoe_filter, storage
from .detail import fetch_product_html
from .errors import BlockedError
from .models import Product

KST = timezone(timedelta(hours=9))

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)


def parse_args(argv=None):
    p = argparse.ArgumentParser(description="FruitsFamily 신발 상품 크롤러")
    p.add_argument("--brands", nargs="*", default=None, help="수집할 브랜드 목록 (미지정시 10개 전체)")
    p.add_argument("--max-products-per-brand", type=int, default=config.MAX_PRODUCTS_PER_BRAND_DEFAULT)
    p.add_argument("--max-pages-per-brand", type=int, default=None, help="더보기 클릭 최대 횟수 (미지정시 무제한)")
    p.add_argument("--max-images-per-product", type=int, default=config.MAX_IMAGES_PER_PRODUCT_DEFAULT)

    sold_group = p.add_mutually_exclusive_group()
    sold_group.add_argument("--include-sold", action="store_true", help="판매완료 상품도 저장 (기본값과 동일, 명시용)")
    sold_group.add_argument("--exclude-sold", action="store_true", help="판매완료 상품 제외 (기본은 포함)")

    p.add_argument("--download-images", action="store_true")
    p.add_argument("--output-format", default="jsonl,csv", help="쉼표로 구분된 출력 형식 (jsonl,csv)")
    p.add_argument(
        "--headless",
        type=lambda s: s.lower() != "false",
        default=True,
        help="Playwright 헤드리스 여부 (--headless false 로 브라우저 창 표시)",
    )
    p.add_argument("--request-delay-min", type=float, default=config.REQUEST_DELAY_MIN_SECONDS)
    p.add_argument("--request-delay-max", type=float, default=config.REQUEST_DELAY_MAX_SECONDS)
    p.add_argument("--resume", action="store_true")

    return p.parse_args(argv)


def resolve_brands(names):
    if not names:
        return brand_aliases.all_canonical_brands()

    resolved = []
    for name in names:
        canonical = brand_aliases.resolve_canonical(name)
        if canonical is None:
            raise SystemExit(f"알 수 없는 브랜드: {name}")
        resolved.append(canonical)
    return resolved


class TooManyConsecutiveFailures(Exception):
    pass


def run(argv=None):
    args = parse_args(argv)
    brands = resolve_brands(args.brands)
    include_sold = not args.exclude_sold
    formats = {fmt.strip() for fmt in args.output_format.split(",") if fmt.strip()}

    cp = checkpoint.load() if args.resume else checkpoint.new()
    existing = storage.load_records()
    seen_urls = {storage.normalize_url(r["item_url"]) for r in existing.values() if r.get("item_url")}

    session = requests.Session()

    totals = {"success": 0, "duplicate": 0, "not_shoe": 0, "sold_skipped": 0, "parse_failed": 0, "image_failed": 0}
    consecutive_parse_failures = 0

    try:
        for brand in brands:
            if args.resume and brand in cp["completed_brands"]:
                logger.info("브랜드 %s: 이미 완료됨(resume), 건너뜀", brand)
                continue

            logger.info("=== 브랜드 시작: %s ===", brand)
            cp["current_brand"] = brand
            brand_success = 0
            brand_failure = 0

            for slug in brand_aliases.brand_slugs(brand):
                if brand_success >= args.max_products_per_brand:
                    break

                try:
                    with listing.open_brand_listing(slug, headless=args.headless) as page:
                        logger.info("브랜드 %s(%s): 목록 페이지 접근", brand, slug)

                        for product_path in listing.iter_product_urls(
                            page, args.max_pages_per_brand, args.request_delay_min, args.request_delay_max
                        ):
                            if brand_success >= args.max_products_per_brand:
                                break

                            product_url = f"{config.SITE_BASE_URL}{product_path}"
                            normalized_url = storage.normalize_url(product_url)
                            if normalized_url in seen_urls:
                                totals["duplicate"] += 1
                                continue

                            try:
                                html = fetch_product_html(session, product_path)
                            except BlockedError:
                                raise
                            except Exception as error:
                                logger.warning("상세 페이지 요청 실패: %s (%s)", product_url, error)
                                brand_failure += 1
                                totals["parse_failed"] += 1
                                consecutive_parse_failures += 1
                                _guard_consecutive_failures(consecutive_parse_failures)
                                time.sleep(random.uniform(args.request_delay_min, args.request_delay_max))
                                continue

                            # 상품 판정(신발 여부 등)과 무관하게, 실제로 요청을 보낸
                            # 직후에는 항상 지연을 둔다 (사이트에 부담을 주지 않기 위함).
                            time.sleep(random.uniform(args.request_delay_min, args.request_delay_max))

                            try:
                                raw = parser.parse_product(html, product_url)
                            except parser.ParseError as error:
                                debug_log.record_parse_failure(product_url, html, str(error))
                                brand_failure += 1
                                totals["parse_failed"] += 1
                                consecutive_parse_failures += 1
                                _guard_consecutive_failures(consecutive_parse_failures)
                                continue

                            consecutive_parse_failures = 0

                            if not shoe_filter.is_shoe(raw.get("category"), raw["item_title"], raw.get("description")):
                                totals["not_shoe"] += 1
                                continue

                            if raw["is_sold"] and not include_sold:
                                totals["sold_skipped"] += 1
                                continue

                            original_brand_text = raw.get("original_brand_text") or brand
                            canonical_brand = brand_aliases.resolve_canonical(original_brand_text) or brand

                            product = Product(
                                source="FRUITSFAMILY",
                                source_product_id=raw.get("source_product_id"),
                                brand=canonical_brand,
                                original_brand_text=original_brand_text,
                                item_title=raw["item_title"],
                                price_krw=raw["price_krw"],
                                original_price=raw.get("original_price"),
                                size=raw.get("size"),
                                description=raw.get("description"),
                                is_sold=raw["is_sold"],
                                item_url=product_url,
                                collected_at=datetime.now(KST).date().isoformat(),
                                image_urls=raw.get("image_urls", []),
                                local_image_paths=[],
                            )
                            record = product.to_dict()

                            if args.download_images and record["image_urls"]:
                                brand_dir = images.brand_dir_name(canonical_brand)
                                img_key = images.stable_key(record["source_product_id"], record["item_url"])
                                saved = images.download_images(
                                    session, brand_dir, img_key, record["image_urls"], args.max_images_per_product
                                )
                                record["local_image_paths"] = saved
                                expected = min(len(record["image_urls"]), args.max_images_per_product)
                                if len(saved) < expected:
                                    totals["image_failed"] += 1

                            existing[storage.identity_key(record)] = record
                            seen_urls.add(normalized_url)
                            brand_success += 1
                            totals["success"] += 1

                            cp["last_product_url"] = product_url
                            cp["success_count"] = totals["success"]
                            cp["failure_count"] = totals["parse_failed"]
                            cp["image_failure_count"] = totals["image_failed"]

                except BlockedError as error:
                    logger.error("차단 감지, 실행 중단: %s", error)
                    checkpoint.save(cp)
                    storage.write_all(existing, formats)
                    return

            cp["completed_brands"].append(brand)
            checkpoint.save(cp)
            logger.info("브랜드 %s 완료 — 성공 %d, 실패 %d", brand, brand_success, brand_failure)

    except TooManyConsecutiveFailures:
        logger.error("연속 파싱 실패 %d회 — 사이트 DOM 변경 가능성, 실행 중단", config.MAX_CONSECUTIVE_PARSE_FAILURES)
        checkpoint.save(cp)
        storage.write_all(existing, formats)
        raise SystemExit(1)

    storage.write_all(existing, formats)
    checkpoint.save(cp)

    logger.info("=== 전체 완료 ===")
    for key, value in totals.items():
        logger.info("%s: %d", key, value)


def _guard_consecutive_failures(count):
    if count >= config.MAX_CONSECUTIVE_PARSE_FAILURES:
        raise TooManyConsecutiveFailures()


if __name__ == "__main__":
    run()
