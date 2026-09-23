package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.common.exception.InvalidPurchaseGoalException;
import com.vintic.backend.common.exception.PurchaseGoalAccessDeniedException;
import com.vintic.backend.common.exception.PurchaseGoalNotFoundException;
import com.vintic.backend.common.exception.UserNotFoundException;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.dto.CreatePurchaseGoalRequest;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalCancelResponse;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalResponse;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

// 예산(hardMaxAmount)/사이즈의 "형식" 검증(NotNull, Positive)은 CreatePurchaseGoalRequest의
// bean validation이 먼저 끝낸다(위반 시 GlobalExceptionHandler가 40001로 응답, 이 서비스까지
// 오지 않는다) - 여기서는 Clock 없이는 판단할 수 없는 것(마감 시각이 실제로 미래인지)과 DB를
// 봐야 판단할 수 있는 것(minCondition 라벨이 유효한지)만 검증한다.
@Service
public class PurchaseGoalCommandService {

    private final PurchaseGoalRepository purchaseGoalRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public PurchaseGoalCommandService(
            PurchaseGoalRepository purchaseGoalRepository,
            UserRepository userRepository,
            Clock clock
    ) {
        this.purchaseGoalRepository = purchaseGoalRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public PurchaseGoalResponse createGoal(CreatePurchaseGoalRequest request, Long userId) {
        // userId는 컨트롤러의 @RequestAttribute("currentUserId")에서만 온다 - 요청 바디에는
        // userId 필드 자체가 없다. 여기서 재조회하는 것은 AutoBidCommandService 등 기존
        // 서비스들과 동일한 방어적 패턴이다(인증 시점에 이미 존재가 확인된 값의 재확인).
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("존재하지 않는 사용자입니다. userId: " + userId));

        GoalCondition minCondition = GoalCondition.fromLabel(request.minCondition())
                .orElseThrow(() -> new InvalidPurchaseGoalException(
                        "유효하지 않은 최소 상태 등급입니다. minCondition: " + request.minCondition()
                ));

        LocalDateTime deadline = request.deadline()
                .atZoneSameInstant(ClockConfig.APP_ZONE)
                .toLocalDateTime();
        LocalDateTime now = LocalDateTime.now(clock);
        if (!deadline.isAfter(now)) {
            throw new InvalidPurchaseGoalException("마감 시각은 현재 이후여야 합니다. deadline: " + deadline);
        }

        PurchaseGoal goal = PurchaseGoal.create(
                user,
                request.brand(),
                request.modelKey(),
                request.modelQuery(),
                minCondition,
                request.sizeKr(),
                request.hardMaxAmount(),
                request.freeTextConditions(),
                deadline,
                now
        );

        PurchaseGoal saved = purchaseGoalRepository.save(goal);
        return PurchaseGoalResponse.from(saved);
    }

    // OrderQueryService.getOrder()와 동일한 소유자 검증 관례(404 -> 403 순서)를 쓴다.
    // 상태 전이 규칙(ACTIVE->CANCELLED, ENGAGED->CANCEL_REQUESTED, 그 외 거절)은
    // PurchaseGoal.cancel()에 있다 - 여기서 상태를 직접 분기하지 않는다.
    @Transactional
    public PurchaseGoalCancelResponse cancelGoal(Long goalId, Long userId) {
        PurchaseGoal goal = purchaseGoalRepository.findById(goalId)
                .orElseThrow(() -> new PurchaseGoalNotFoundException("존재하지 않는 구매 목표입니다. goalId: " + goalId));

        if (!goal.getUser().getId().equals(userId)) {
            throw new PurchaseGoalAccessDeniedException("접근 권한이 없는 구매 목표입니다. goalId: " + goalId);
        }

        goal.cancel(LocalDateTime.now(clock));

        return new PurchaseGoalCancelResponse(goal.getId(), goal.getStatus(), TimePolicy.toApiTime(goal.getUpdatedAt()));
    }
}
