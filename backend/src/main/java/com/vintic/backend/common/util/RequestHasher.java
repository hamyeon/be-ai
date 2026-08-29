package com.vintic.backend.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// Idempotency payload 동일성 비교 전용 SHA-256 해셔다. 암호화가 목적이 아니라 "같은 의미의
// 요청인가"를 비교하는 것이 목적이므로 SHA-256으로 충분하다. 호출부가 각자의 payload를
// canonical 문자열(예: "amount=15000")로 만들어 넘긴다 - 어떤 필드를 해시할지는 command마다
// 다르지만, 해시 알고리즘/포맷 자체는 이 클래스 하나로 통일한다.
public final class RequestHasher {

    private RequestHasher() {
    }

    public static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
