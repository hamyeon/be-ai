package com.vintic.backend.ai.purchase.harness;

import java.util.List;

// 하네스 케이스 하나. text를 파서에 넣고 expected와 비교한다.
//
// expected의 null은 "값이 없어야 정답"이라는 뜻이다(예산 없음, 모델 못 특정). 채점에서 제외가 아니다.
// freeTextKeywords는 자유 조건에 포함돼야 할 단어. 비어 있으면 자유 조건은 채점하지 않는다 -
// 자유 텍스트는 표기가 자유로워 정확 일치로는 못 재기 때문이다.
record GoalParseHarnessCase(
        String id,
        String text,
        Expected expected,
        String note
) {

    record Expected(
            String brand,
            String modelKey,
            String minCondition,
            Long hardMaxAmount,
            Integer sizeKr,
            List<String> freeTextKeywords
    ) {
    }
}
