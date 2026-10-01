package com.vintic.backend.ai.purchase.dto;

import java.util.Locale;
import java.util.Optional;

// 구매 목표의 최소 상태 등급. 시세 CSV(condition_rates.csv)와 같은 5단계다.
//
// Vision의 ConditionGrade(DS/A/B/C/UNKNOWN)에는 S가 없지만, 시세 계수와 사용자 표현
// ("거의 새것")에는 S가 있어 여기서는 5단계를 쓴다. "A급 이상"은 rank가 A 이상인 매물을
// 뜻하므로 서열 비교가 필요하다 - 백엔드 pre-filter는 atLeast()를 쓰면 되고, 매물 등급이
// UNKNOWN이면 비교 자체를 하지 말고 제외해야 한다.
public enum GoalCondition {
    DS(5),
    S(4),
    A(3),
    B(2),
    C(1);

    private final int rank;

    GoalCondition(int rank) {
        this.rank = rank;
    }

    // 매물 등급(listing)이 이 최소 조건을 만족하는가.
    public boolean satisfiedBy(GoalCondition listing) {
        return listing != null && listing.rank >= this.rank;
    }

    public static Optional<GoalCondition> fromLabel(String label) {
        if (label == null || label.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(label.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
