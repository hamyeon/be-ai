package com.vintic.backend.ai.purchase.api;

import com.vintic.backend.ai.purchase.dto.GoalDraft;
import com.vintic.backend.ai.purchase.parser.GoalParser;
import com.vintic.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 자연어 → GoalDraft. 설계안 6-3의 POST /api/purchase-goals/parse.
//
// DB에 아무것도 남기지 않는다. 응답은 초안이고, 사용자가 확인 화면에서 고친 값이
// POST /api/purchase-goals(백엔드 담당, 별도 컨트롤러)로 확정된다. 그래서 이 엔드포인트는
// PurchaseGoal 엔티티가 없어도 먼저 올라갈 수 있고, 프론트는 mock 없이 붙을 수 있다.
//
// 인증은 기본 정책(authenticated)을 따른다 - 유료 LLM 호출이라 익명에 열지 않는다.
@RestController
@RequestMapping("/api/purchase-goals")
@RequiredArgsConstructor
public class GoalParseController {

    private final GoalParser goalParser;

    @Operation(summary = "자연어 구매 목표 파싱",
            description = "\"뉴발 990, A급 이상, 15만원 이하로 하나\" 같은 문장을 구조화된 GoalDraft 초안으로 바꾼다. "
                    + "DB 저장 없음. 응답은 초안이며 사용자 확인·수정 후 POST /api/purchase-goals로 확정한다. "
                    + "AI 해석에 실패하면 규칙 기반 초안을 warnings와 함께 돌려준다(빈 폼 fallback은 프론트 몫).")
    @PostMapping("/parse")
    public ResponseEntity<ApiResponse<GoalDraft>> parse(@Valid @RequestBody GoalParseRequest request) {
        GoalDraft draft = goalParser.parse(request.text());
        return ResponseEntity.ok(ApiResponse.success(draft));
    }
}
