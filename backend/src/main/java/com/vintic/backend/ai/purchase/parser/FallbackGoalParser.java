package com.vintic.backend.ai.purchase.parser;

import com.vintic.backend.ai.purchase.dto.GoalDraft;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 1차 파서(LLM)가 실패하면 2차 파서(규칙)로 넘어간다.
//
// 설계안 6-1: "파싱 실패가 Goal 등록을 막지 않는다." 빈 폼을 주는 대신 규칙 기반이 채운
// 절반짜리 폼을 준다. 대신 초안 맨 앞에 AI 실패 경고를 붙이고 확신도를 0.5로 눌러
// 확인 화면이 수정을 강하게 유도하게 한다.
//
// 1차 파서의 실패는 이미 AiCallLog에 남아 있다. 여기서는 로그 한 줄만 더 남긴다.
@RequiredArgsConstructor
@Slf4j
public class FallbackGoalParser implements GoalParser {

    private static final double FALLBACK_CONFIDENCE_CAP = 0.5;

    private final GoalParser primary;
    private final GoalParser fallback;

    @Override
    public GoalDraft parse(String naturalLanguage) {
        try {
            return primary.parse(naturalLanguage);
        } catch (RuntimeException e) {
            log.warn("1차 Goal 파서 실패, 규칙 기반으로 대체합니다. reason={}", e.getMessage());
            GoalDraft draft = fallback.parse(naturalLanguage);
            return GoalDraftWarnings.withWarning(draft, GoalDraftWarnings.AI_FALLBACK, FALLBACK_CONFIDENCE_CAP);
        }
    }
}
