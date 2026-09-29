package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.common.exception.InvalidPurchaseGoalException;
import com.vintic.backend.common.exception.InvalidPurchaseGoalStatusException;
import com.vintic.backend.common.exception.PurchaseGoalAccessDeniedException;
import com.vintic.backend.common.exception.PurchaseGoalNotFoundException;
import com.vintic.backend.common.exception.UserNotFoundException;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus;
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
        // 방금 만든 Goal이라 참여 이력이 있을 수 없다 - 조회 없이 0으로 고정한다.
        return PurchaseGoalResponse.from(saved, 0, 0);
    }

    // OrderQueryService.getOrder()와 동일한 소유자 검증 관례(404 -> 403 순서)를 쓴다.
    //
    // Day 5부터는 PurchaseGoal.cancel()(엔티티 dirty-checking 저장) 대신 조건부 UPDATE 두 번을
    // 시도한다 - Day 5의 참여(ACTIVE->ENGAGED) 전이도 같은 goal row에 조건부 UPDATE를 쓰므로,
    // 여기서 읽어둔 goal 엔티티의 status를 그대로 믿고 무조건 덮어쓰면 참여 트랜잭션이 먼저
    // commit된 경우 그 결과(ENGAGED)를 잃어버릴 수 있다(lost update). ACTIVE->CANCELLED가 실패하면
    // (이미 다른 상태로 바뀐 것) ENGAGED->CANCEL_REQUESTED를 시도한다 - 참여가 먼저 이겼다면
    // 이 두 번째 시도가 성공해 취소 의사를 기록한다. 두 시도 모두 실패하면 실제 DB 상태를 다시
    // 읽어 예외 메시지를 만든다(읽어둔 goal은 이미 stale할 수 있으므로 응답/예외 모두 이 재조회
    // 또는 UPDATE 결과 기준으로 만든다 - 처음 읽은 goal 엔티티 값을 그대로 응답에 쓰지 않는다).
    @Transactional
    public PurchaseGoalCancelResponse cancelGoal(Long goalId, Long userId) {
        PurchaseGoal goal = purchaseGoalRepository.findById(goalId)
                .orElseThrow(() -> new PurchaseGoalNotFoundException("존재하지 않는 구매 목표입니다. goalId: " + goalId));

        if (!goal.getUser().getId().equals(userId)) {
            throw new PurchaseGoalAccessDeniedException("접근 권한이 없는 구매 목표입니다. goalId: " + goalId);
        }

        LocalDateTime now = LocalDateTime.now(clock);

        if (purchaseGoalRepository.cancelFromActive(goalId, now) == 1) {
            return new PurchaseGoalCancelResponse(goalId, PurchaseGoalStatus.CANCELLED, TimePolicy.toApiTime(now));
        }
        if (purchaseGoalRepository.requestCancelFromEngaged(goalId, now) == 1) {
            return new PurchaseGoalCancelResponse(goalId, PurchaseGoalStatus.CANCEL_REQUESTED, TimePolicy.toApiTime(now));
        }

        PurchaseGoal current = purchaseGoalRepository.findById(goalId)
                .orElseThrow(() -> new PurchaseGoalNotFoundException("존재하지 않는 구매 목표입니다. goalId: " + goalId));
        throw new InvalidPurchaseGoalStatusException(
                "ACTIVE/ENGAGED 상태에서만 취소할 수 있습니다. 현재 상태: " + current.getStatus()
        );
    }
}
