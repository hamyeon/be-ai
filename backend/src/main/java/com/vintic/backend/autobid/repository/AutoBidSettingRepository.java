package com.vintic.backend.autobid.repository;

import com.vintic.backend.autobid.domain.AutoBidSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AutoBidSettingRepository extends JpaRepository<AutoBidSetting, Long> {

    // (auction_id, user_id) unique 제약상 최대 1건이므로 "최신 row 선택" 로직은 필요 없다.
    // CANCELED 여부 판단(현재 사용 중인 설정 없음)은 호출부(/live)에서 처리한다.
    Optional<AutoBidSetting> findByAuctionIdAndUserId(Long auctionId, Long userId);
}
