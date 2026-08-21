package com.vintic.backend.bid.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// 수동 입찰 payload의 의미 있는 값만 canonical하게 구성해 해시한다.
// JSON 직렬화 방식(공백, 필드 순서)이 아니라 값 자체로 해시를 만들어야
// 같은 의미의 요청이 다른 requestHash로 오판되지 않는다.
// 암호화가 목적이 아니라 payload 동일성 비교가 목적이라 SHA-256으로 충분하다.
final class BidRequestHash {

    private BidRequestHash() {
    }

    static String sha256(Long amount) {
        String canonical = "amount=" + amount;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
