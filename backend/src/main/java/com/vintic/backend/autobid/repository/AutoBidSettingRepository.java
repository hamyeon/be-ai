package com.vintic.backend.autobid.repository;

import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AutoBidSettingRepository extends JpaRepository<AutoBidSetting, Long> {

    // #41부터 (auction_id, user_id)만으로는 유일하지 않다 - CANCELED 이력이 여러 건 쌓일 수
    // 있기 때문이다(uk_auto_bid_setting_active_slot 참고). activeSlot=true인 "현재 설정"은
    // (auction_id, user_id, active_slot) UNIQUE 덕분에 항상 0~1건이라 안전하게 Optional로 받는다.
    Optional<AutoBidSetting> findByAuctionIdAndUserIdAndActiveSlotTrue(Long auctionId, Long userId);

    // Proxy resolution이 "이번 트리거를 낸 사용자를 제외한 나머지 ACTIVE 경쟁자"를 찾을 때 쓴다.
    // 정상 상태에서는 최대 1건이지만, #41(Proxy 미구현 기간)에 생성된 LIVE 데이터에는 여러 명이
    // 동시에 ACTIVE로 남아있을 수 있어 List로 받아 ProxyPriceEngine이 effectiveCap 기준으로
    // 정규화(self-heal)한다 - 단일 row라고 가정하지 않는다.
    List<AutoBidSetting> findByAuctionIdAndStatusAndUserIdNot(Long auctionId, AutoBidSettingStatus status, Long userId);
}
