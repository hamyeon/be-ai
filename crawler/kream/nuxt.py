"""KREAM 페이지의 __NUXT_DATA__(Nuxt 3 하이드레이션 페이로드)를 읽는 모듈.

Nuxt 3는 상태를 "평탄한 배열"로 직렬화한다. 컨테이너(dict/list) 안의 값은 실제 값이 아니라
배열 인덱스 참조다. 예:

    [{"sales": 3}, ..., {"items": 5}, ..., [6, 9], {"price": 7}, 320000, ...]

그래서 원하는 구조를 꺼내려면 인덱스를 재귀적으로 따라가며 복원해야 한다(resolve).
"""
import json
import re

NUXT_DATA_PATTERN = re.compile(
    r'<script[^>]*id="__NUXT_DATA__"[^>]*>(.*?)</script>', re.S
)

# 순환 참조(login_info 등)와 폭주 방지
MAX_DEPTH = 12


def extract_payload(html: str):
    """HTML에서 __NUXT_DATA__ 배열을 꺼낸다. 없으면 None - 페이지 구조가 바뀌었다는 뜻이다."""
    match = NUXT_DATA_PATTERN.search(html)
    if not match:
        return None
    try:
        payload = json.loads(match.group(1))
    except json.JSONDecodeError:
        return None
    return payload if isinstance(payload, list) else None


def resolve(payload, index, depth=0):
    """배열 인덱스 참조를 따라가 실제 값 구조를 복원한다."""
    if depth > MAX_DEPTH:
        return None
    if not isinstance(index, int) or not (0 <= index < len(payload)):
        return None

    value = payload[index]
    if isinstance(value, dict):
        return {k: resolve(payload, v, depth + 1) for k, v in value.items()}
    if isinstance(value, list):
        return [resolve(payload, item, depth + 1) for item in value]
    return value


def find_dict_with_keys(payload, required_keys):
    """required_keys를 모두 가진 dict 항목의 인덱스들을 돌려준다.

    Nuxt 페이로드는 필드명이 안정적이라(keys), 위치 대신 키 조합으로 찾는 게
    프론트 개편에 덜 깨진다.
    """
    required = set(required_keys)
    return [
        i for i, value in enumerate(payload)
        if isinstance(value, dict) and required.issubset(value.keys())
    ]
