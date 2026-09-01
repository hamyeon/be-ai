package com.vintic.backend.penalty.service;

import com.vintic.backend.common.exception.UserNotFoundException;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.penalty.domain.Penalty;
import com.vintic.backend.penalty.dto.MyPenaltyResponse;
import com.vintic.backend.penalty.repository.PenaltyRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// FINAL contract §14: noShowCount/bidRestricted/bidRestrictedUntil의 single source of truth.
// User.noshowCount/bidRestrictedUntil(OrderExpirationService가 갱신)과 Penalty 이력을 그대로
// 읽기만 한다(side-effect 없음) - AuctionResultQueryService/OrderQueryService와 동일한 원칙.
@Service
public class PenaltyQueryService {

    private final UserRepository userRepository;
    private final PenaltyRepository penaltyRepository;
    private final Clock clock;

    public PenaltyQueryService(UserRepository userRepository, PenaltyRepository penaltyRepository, Clock clock) {
        this.userRepository = userRepository;
        this.penaltyRepository = penaltyRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MyPenaltyResponse getMyPenalties(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("존재하지 않는 사용자입니다. userId: " + userId));

        LocalDateTime now = LocalDateTime.now(clock);
        List<Penalty> penalties = penaltyRepository.findByUser_IdOrderByCreatedAtDesc(userId);

        return new MyPenaltyResponse(
                user.getNoshowCount(),
                user.isBidRestricted(now),
                TimePolicy.toApiTime(user.getBidRestrictedUntil()),
                TimePolicy.toApiTime(now),
                penalties.stream()
                        .map(p -> new MyPenaltyResponse.PenaltyItem(
                                p.getId(), p.getType(), p.getAuction().getId(), TimePolicy.toApiTime(p.getCreatedAt())
                        ))
                        .toList()
        );
    }
}
