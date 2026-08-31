package com.vintic.backend.order.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.InvalidAuctionStatusException;
import com.vintic.backend.order.domain.Order;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.user.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

// #56-0/#56-1: 종료된 경매를 낙찰자 Order로 정산하는 명시적 command다. GET /result는 이 서비스를
// 호출하지 않는다(side-effect free 유지) - 실제 production 호출 지점(LIVE->ENDED 스케줄러)은
// 아직 없고 DEFERRED UNTIL LIFECYCLE INTEGRATION이다(#44의 ProxyTrigger.None과 동일한 선례).
// 지금은 테스트가 이 서비스를 직접 호출해 검증하고, lifecycle 스케줄러가 merge되면 그 호출부가
// 이 메서드를 그대로 재사용한다.
@Service
public class AuctionSettlementService {

    // FINAL contract 모든 응답 예시가 shippingFee=3000으로 고정돼 있고, Product/Auction 어디에도
    // 배송비 필드가 없다 - 사용자 확인 결과 v1 범위에서는 전역 고정 상수로 처리한다(사용자 확정).
    // 상품별 배송비가 필요해지면 그때 별도 이슈로 스키마를 바꾼다.
    static final long SHIPPING_FEE = 3000L;

    private final AuctionRepository auctionRepository;
    private final OrderRepository orderRepository;

    public AuctionSettlementService(AuctionRepository auctionRepository, OrderRepository orderRepository) {
        this.auctionRepository = auctionRepository;
        this.orderRepository = orderRepository;
    }

    // Auction을 이 트랜잭션의 첫 번째(그리고 유일한 락 이전) 조회로 만들어 findByIdForUpdate를
    // 이 트랜잭션의 첫 statement로 유지한다 - #46 follow-up이 발견한 "첫 non-locking read가
    // REPEATABLE READ snapshot을 고정시켜 그 뒤 non-locking read가 stale해지는" 문제 클래스를
    // 애초에 피한다(이 메서드엔 Idempotency claim 같은 사전 non-locking 조회가 없다). 같은
    // auction에 대한 동시 settle() 호출은 이 row lock으로 완전히 직렬화된다 - 두 번째 호출은
    // 첫 번째가 커밋할 때까지 대기했다가 이미 생성된 Order를 보고 그대로 반환한다(재실행 시
    // 중복 생성 없음). uk_order_auction_buyer UNIQUE는 이 직렬화가 어떤 이유로든 깨지는
    // 경우를 위한 최종 방어선이다(#56-0 확정 - service check만으로 중복을 보장하지 않는다).
    @Transactional
    public Optional<Order> settle(Long auctionId) {
        Auction auction = auctionRepository.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));

        if (auction.getStatus() != AuctionStatus.ENDED) {
            throw new InvalidAuctionStatusException(
                    "ENDED 상태에서만 settlement를 수행할 수 있습니다. auctionId: " + auctionId + ", 현재 상태: " + auction.getStatus()
            );
        }

        User winner = auction.getCurrentWinner();
        if (winner == null) {
            // NO_BIDS - 낙찰자가 없으므로 Order를 만들지 않는다.
            return Optional.empty();
        }

        Optional<Order> existing = orderRepository.findByAuctionIdAndBuyerId(auctionId, winner.getId());
        if (existing.isPresent()) {
            return existing;
        }

        Order order = Order.createForWinner(
                auction,
                winner,
                auction.getCurrentPrice(),
                SHIPPING_FEE,
                auction.getEndAt().plusHours(24)
        );
        return Optional.of(orderRepository.save(order));
    }
}
