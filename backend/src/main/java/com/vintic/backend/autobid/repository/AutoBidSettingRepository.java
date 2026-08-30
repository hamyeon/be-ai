package com.vintic.backend.autobid.repository;

import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AutoBidSettingRepository extends JpaRepository<AutoBidSetting, Long> {

    // #41부터 (auction_id, user_id)만으로는 유일하지 않다 - CANCELED 이력이 여러 건 쌓일 수
    // 있기 때문이다(uk_auto_bid_setting_active_slot 참고). activeSlot=true인 "현재 설정"은
    // (auction_id, user_id, active_slot) UNIQUE 덕분에 항상 0~1건이라 안전하게 Optional로 받는다.
    Optional<AutoBidSetting> findByAuctionIdAndUserIdAndActiveSlotTrue(Long auctionId, Long userId);

    // #45: PESSIMISTIC_WRITE로 바꿨다 - Proxy resolution이 "이번 트리거를 낸 사용자를 제외한
    // 나머지 ACTIVE 경쟁자"를 찾을 때 쓰는데, 일반 SELECT였을 때는 다음 문제가 있었다.
    //
    //   1. 이 메서드를 호출하는 시점엔 이미 Auction row를 findByIdForUpdate(PESSIMISTIC_WRITE,
    //      현재 read)로 잠근 뒤다 - 하지만 MySQL/InnoDB REPEATABLE READ에서 트랜잭션의 첫 "일반"
    //      SELECT(Idempotency claim 조회, User 조회 등)가 이미 REPEATABLE READ snapshot을
    //      만들어 버린다.
    //   2. Auction의 findByIdForUpdate는 locking read라 snapshot과 무관하게 항상 최신 커밋을
    //      보지만, 그 뒤에 나가는 AutoBidSetting 일반 SELECT는 잠금이 없어 위 1번에서 이미
    //      만들어진 snapshot을 그대로 쓴다 - 그 사이 동시에 commit된 다른 사용자의 AutoBidSetting을
    //      놓칠 수 있다(stale candidate set).
    //   3. 즉 Auction row lock을 확보했다는 사실만으로는 "Proxy가 보는 경쟁자 후보 목록이
    //      최신인지"까지 보장되지 않았다 - 실제로 ProxyMixedConcurrencyMySqlIT(3명 동시
    //      LIVE AutoBid CREATE)에서 경쟁 자체가 감지되지 않아 전원 ACTIVE로 남는 것으로 재현됨.
    //
    // PESSIMISTIC_WRITE를 걸면 이 SELECT도 locking read가 되어 항상 최신 커밋을 읽는다 -
    // 데드락 위험은 없다: 이 row들을 쓸 수 있는 유일한 경로는 같은 Auction row의 write lock을
    // 먼저 획득한 트랜잭션뿐이라, 이 시점에 경쟁할 수 있는 다른 살아있는 트랜잭션이 없다.
    // 정상 상태에서는 최대 1건이지만, #41(Proxy 미구현 기간)에 생성된 LIVE 데이터에는 여러 명이
    // 동시에 ACTIVE로 남아있을 수 있어 List로 받아 ProxyPriceEngine이 effectiveCap 기준으로
    // 정규화(self-heal)한다 - 단일 row라고 가정하지 않는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from AutoBidSetting s
            where s.auction.id = :auctionId and s.status = :status and s.user.id <> :userId
            """)
    List<AutoBidSetting> findByAuctionIdAndStatusAndUserIdNot(
            @Param("auctionId") Long auctionId,
            @Param("status") AutoBidSettingStatus status,
            @Param("userId") Long userId
    );
}
