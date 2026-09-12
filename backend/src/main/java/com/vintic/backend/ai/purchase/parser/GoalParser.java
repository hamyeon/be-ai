package com.vintic.backend.ai.purchase.parser;

import com.vintic.backend.ai.purchase.dto.GoalDraft;

// 자연어 구매 목표 → GoalDraft. 설계안 6-4의 격리 인터페이스.
//
// 구현이 규칙이든 LLM이든 호출부(컨트롤러, 나중의 백엔드 orchestration)는 이 인터페이스만
// 본다. 어느 구현을 쓸지는 GoalParserConfig가 설정값으로 정한다.
//
// 계약: 어떤 입력에도 예외 대신 GoalDraft를 돌려주려고 노력한다. 못 알아본 필드는 null이고
// warnings에 이유가 실린다. 파싱 실패가 Goal 등록을 막지 않아야 하기 때문이다(6-1 fallback).
public interface GoalParser {

    GoalDraft parse(String naturalLanguage);
}
