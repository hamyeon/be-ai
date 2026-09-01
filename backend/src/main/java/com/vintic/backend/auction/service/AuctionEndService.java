package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.order.service.AuctionSettlementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

// #73-2: LIVE -> ENDED lifecycle 전환 + #56 AuctionSettlementService 연결. lock 순서/재검증
// 원칙은 AuctionStartService(#73-1)와 동일하다 - Auction FOR UPDATE로 다시 읽고, 그 시점의
// 최신 상태/endAt만 신뢰한다.
//
// 대상이 "LIVE && endAt <= now"인데, 여기서 endAt은 호출자가 넘겨준 후보 시각이 아니라 이
// 메서드가 lock 이후 다시 읽은 Auction.endAt이다 - 그래야 종료 연장(#43, maybeExtend())으로
// endAt이 뒤로 밀린 경매를 최초 예정 시각 기준으로 조기 종료하는 일이 없다(연장은 이 서비스가
// 아니라 BidCommandService/AutoBidCommandService의 사용자 command 경로에서만 일어나므로, 이
// 메서드가 매번 최신값을 다시 읽는 것만으로 충분하다 - 별도 재검증 로직을 추가할 필요가 없다).
//
// settlement는 AuctionSettlementService.settle()을 그대로 호출한다 - 정책을 복제하지 않는다.
// settle()도 @Transactional(기본 propagation REQUIRED)이라 이 메서드의 트랜잭션에 그대로
// 합류한다 - end()와 settle()이 하나의 물리 트랜잭션으로 묶여, 어느 한쪽이 실패하면 둘 다
// 롤백된다(Auction은 LIVE로 남는다). 그 결과 이 경매는 다음 poll에서 "LIVE && endAt <= now"
// 조건에 다시 걸려 자연스럽게 재시도된다 - 별도 retry framework를 만들지 않는다.
@Service
public class AuctionEndService {

    private final AuctionRepository auctionRepository;
    private final AuctionSettlementService auctionSettlementService;
    private final Clock clock;

    public AuctionEndService(
            AuctionRepository auctionRepository,
            AuctionSettlementService auctionSettlementService,
            Clock clock
    ) {
        this.auctionRepository = auctionRepository;
        this.auctionSettlementService = auctionSettlementService;
        this.clock = clock;
    }

    @Transactional
    public void endIfDue(Long auctionId) {
        Auction auction = auctionRepository.findByIdForUpdate(auctionId).orElse(null);
        if (auction == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (auction.getStatus() != AuctionStatus.LIVE || auction.getEndAt().isAfter(now)) {
            return;
        }

        auction.end();

        // settle()은 자신의 findByIdForUpdate로 같은 row를 다시 잠근다 - 이미 이 트랜잭션이
        // 보유한 lock의 재확인일 뿐이라 데드락 위험이 없다(#56-2/#57-2가 이미 확립한 "같은
        // 트랜잭션 내 재조회" 관례와 동일). settle()은 ENDED 상태를 전제하므로 반드시 end() 이후에
        // 호출한다 - 이 순서를 지키지 않으면 InvalidAuctionStatusException이 그대로 전파돼
        // end()까지 함께 롤백된다(그 자체가 방어선이다, 별도 가드를 추가하지 않는다).
        auctionSettlementService.settle(auctionId);
    }
}
