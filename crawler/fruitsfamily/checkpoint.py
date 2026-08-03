import json
from datetime import datetime, timedelta, timezone

from . import config

KST = timezone(timedelta(hours=9))


def new() -> dict:
    return {
        "current_brand": None,
        "completed_brands": [],
        "last_product_url": None,
        "success_count": 0,
        "failure_count": 0,
        "image_failure_count": 0,
        "updated_at": None,
    }


def load() -> dict:
    if not config.CHECKPOINT_PATH.exists():
        return new()
    try:
        with config.CHECKPOINT_PATH.open("r", encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, json.JSONDecodeError):
        return new()

    checkpoint = new()
    checkpoint.update(data)
    return checkpoint


def save(checkpoint: dict) -> None:
    checkpoint["updated_at"] = datetime.now(KST).isoformat()
    config.CHECKPOINT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with config.CHECKPOINT_PATH.open("w", encoding="utf-8") as f:
        json.dump(checkpoint, f, ensure_ascii=False, indent=2)
