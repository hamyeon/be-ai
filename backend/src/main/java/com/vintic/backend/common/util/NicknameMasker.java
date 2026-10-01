package com.vintic.backend.common.util;

// 계약(§0.9): 3자 이상 -> 앞 3글자 + ****, 1~2자 -> 첫 1글자 + ****. 별표는 원본 길이와 무관하게 항상 4개.
// User.nickname은 nullable=false이고 별도 blank 검증이 없어, 여기서도 null 방어는 하지 않고
// 항상 non-null 닉네임이 들어온다고 가정한다(현재 User validation과 동일한 전제).
public final class NicknameMasker {

    private static final String MASK = "****";
    private static final int MAX_VISIBLE_LENGTH = 3;
    private static final int MIN_VISIBLE_LENGTH = 1;

    private NicknameMasker() {
    }

    public static String mask(String nickname) {
        int visibleLength = nickname.length() >= MAX_VISIBLE_LENGTH ? MAX_VISIBLE_LENGTH : MIN_VISIBLE_LENGTH;
        return nickname.substring(0, visibleLength) + MASK;
    }
}
