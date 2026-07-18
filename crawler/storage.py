import json
from pathlib import Path


def load_existing_urls(path: Path) -> set:
    if not path.exists():
        return set()

    urls = set()
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            url = record.get("item_url")
            if url:
                urls.add(url)
    return urls


def append_jsonl(path: Path, records: list) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as f:
        for record in records:
            f.write(json.dumps(record, ensure_ascii=False))
            f.write("\n")
