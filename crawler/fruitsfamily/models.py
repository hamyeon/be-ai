from dataclasses import asdict, dataclass, field
from typing import Optional


@dataclass
class Product:
    source: str
    source_product_id: Optional[str]
    brand: str
    original_brand_text: str
    item_title: str
    price_krw: int
    original_price: Optional[int]
    size: Optional[str]
    description: Optional[str]
    is_sold: bool
    item_url: str
    collected_at: str
    image_urls: list = field(default_factory=list)
    local_image_paths: list = field(default_factory=list)

    def to_dict(self) -> dict:
        return asdict(self)
