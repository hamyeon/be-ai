package com.vintic.backend.autobid.proxy;

// CANCELED는 이 결과에 절대 등장하지 않는다 - candidate 입력 자체에서 이미 제외된다(호출부 책임).
public enum ProxyEntrantStatus {
    ACTIVE,
    CAP_REACHED
}
