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
    // 순수 조회(GET /auto-bids/me 등) 전용이다 - business 결정을 내리는 write 경로는 아래
    // findCurrentByAuctionIdAndUserIdForUpdate()를 써야 한다(#46 follow-up 참고).
    Optional<AutoBidSetting> findByAuctionIdAndUserIdAndActiveSlotTrue(Long auctionId, Long userId);

    // #46 follow-up: write 경로가 "본인의 현재 AutoBidSetting"을 읽고 그 값을 검증/덮어쓰기에
    // 그대로 쓰는 모든 지점(UPDATE_AUTO_BID의 entrant 조회, CREATE_AUTO_BID의 40908 사전 검사,
    // PLACE_BID의 cancelOwnActiveAutoBidIfPresent) 전용 locking current read다.
    //
    // 이유: MySQL/InnoDB REPEATABLE READ의 read view는 트랜잭션의 첫 non-locking consistent
    // read에서 확립될 수 있다 - 이 구조에서는 command 실행 전 IdempotencyClaimService의 claim
    // 조회가 그 역할을 한다(#46 follow-up에서 실측). 그 뒤에 나오는 모든 일반 SELECT는 "누구의
    // row인가"와 무관하게 그 read view를 그대로 쓴다 - Auction FOR UPDATE(locking read라
    // read view와 무관하게 항상 최신을 봄) 뒤에 실행되더라도 예외가 아니다.
    // AutoBidSetting.maxAmount/status는 이 read view로 읽은 값이 그대로 검증 조건과 이후
    // changeMaxAmount()/cancel()의 덮어쓰기에 쓰이는 mutable, business-decision 필드라
    // non-locking read면 다른 트랜잭션의 커밋을 놓치고 stale write(lost update)로 이어질 수
    // 있다 - 실제로 AutoBidCapUpdateStaleReadMySqlIT(강제 race window)에서 재현했다
    // (초기 cap=100, 동시 PATCH 200/150 → 고쳐지기 전엔 150이 이미 커밋된 200을 덮어씀).
    //
    // Auction row lock을 이미 잡은 뒤에만 호출한다(호출부가 lock ordering을 보장) - 이 row를
    // 쓸 수 있는 다른 살아있는 트랜잭션이 이 시점엔 없으므로 데드락 위험은 없다(#45와 동일 근거).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from AutoBidSetting s
            where s.auction.id = :auctionId and s.user.id = :userId and s.activeSlot = true
            """)
    Optional<AutoBidSetting> findCurrentByAuctionIdAndUserIdForUpdate(
            @Param("auctionId") Long auctionId,
            @Param("userId") Long userId
    );

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

    // #73-1: SCHEDULED -> LIVE lifecycle 전환 시점의 RESERVED 일괄 정산 전용. 이 시점엔 "트리거를
    // 낸 사용자"가 없다 - RESERVED 전체가 곧 ProxyPriceEngine의 candidate pool이라 위
    // findByAuctionIdAndStatusAndUserIdNot처럼 특정 사용자를 제외할 이유가 없다. 같은 이유
    // (Auction row lock을 이미 잡은 뒤에만 호출, 그 시점엔 경쟁할 다른 살아있는 트랜잭션이 없음)로
    // PESSIMISTIC_WRITE를 쓴다 - 데드락 위험 없음, stale candidate set 방지 목적도 동일하다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from AutoBidSetting s
            where s.auction.id = :auctionId and s.status = :status
            """)
    List<AutoBidSetting> findByAuctionIdAndStatusForUpdate(
            @Param("auctionId") Long auctionId,
            @Param("status") AutoBidSettingStatus status
    );
}
