package com.vintic.backend.autobid.service;

import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.dto.AutoBidMeResponse;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.common.exception.AutoBidNotFoundException;
import com.vintic.backend.common.util.TimePolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class AutoBidQueryService {

    private final AutoBidSettingRepository autoBidSettingRepository;
    private final Clock clock;

    public AutoBidQueryService(AutoBidSettingRepository autoBidSettingRepository, Clock clock) {
        this.autoBidSettingRepository = autoBidSettingRepository;
        this.clock = clock;
    }

    // CANCELED는 findByAuctionIdAndUserIdAndActiveSlotTrue 쿼리 단계에서 이미 제외된다 - "과거
    // CANCELED row를 우연히 반환"할 여지가 없다(§3).
    @Transactional(readOnly = true)
    public AutoBidMeResponse getMyAutoBid(Long auctionId, Long userId) {
        AutoBidSetting setting = autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auctionId, userId)
                .orElseThrow(() -> new AutoBidNotFoundException("등록된 자동입찰이 없습니다. auctionId: " + auctionId));

        // canModify/canCancel: 현재 조회되는 세 상태(RESERVED/ACTIVE/CAP_REACHED) 모두 수정/취소
        // 가능이 최소 규칙이라(§9) 지금은 상수처럼 true다. 향후 상태별 제한이 생기면 이 지점에서
        // 분기하면 된다 - 필드 자체는 이미 독립적으로 존재한다.
        return new AutoBidMeResponse(
                setting.getId(),
                setting.getAuction().getId(),
                setting.getStatus(),
                setting.getMaxAmount(),
                setting.getAuction().getCurrentPrice(),
                setting.getAuction().getMinNextBidAmount(),
                TimePolicy.toApiTime(setting.getAuction().getStartAt()),
                TimePolicy.toApiTime(LocalDateTime.now(clock)),
                true,
                true
        );
    }
}
