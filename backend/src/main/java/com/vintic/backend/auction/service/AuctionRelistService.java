package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.dto.AuctionRelistResponse;
import com.vintic.backend.auction.dto.RelistAuctionRequest;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.ActiveAuctionAlreadyExistsException;
import com.vintic.backend.common.exception.AuctionNotEligibleForReregistrationException;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.AuctionRegistrationLimitExceededException;
import com.vintic.backend.common.exception.AuctionSellerMismatchException;
import com.vintic.backend.common.exception.ProductNotFoundException;
import com.vintic.backend.common.util.BidIncrementPolicy;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// 기존 상품에 대한 "재경매" 전용 - 첫 경매는 ProductRegistrationService(상품+첫 경매 동시 등록,
// POST /api/products)가 유일한 외부 경로로 처리하므로, 이 서비스는 productId가 아니라
// "이전 경매(previousAuctionId)"를 입력으로 받는다 - 첫 경매가 아예 없는 상품은 참조할
// previousAuctionId 자체가 없어 구조적으로 이 API를 호출할 수 없다.
//
// 한 상품은 최초 등록을 포함해 총 2회까지만 경매를 등록할 수 있고(취소도 이 횟수에 포함),
// previousAuction이 ENDED라도 실제 입찰이 한 건도 없었던 경우(유찰)이거나 CANCELED(시작 전 취소)인
// 경우에만 재경매를 허용한다 - 결제 실패/차순위 제안 상태만으로는 재경매를 허용하지 않는다(기획 확정).
//
// 동시성/lock 순서: previousAuction을 FOR UPDATE로 먼저 잠근 뒤(harmless - 이미 terminal 상태라
// 잠가도 경합할 다른 쓰기가 없다) 거기서 얻은 productId로 Product를 FOR UPDATE로 잠그고, 그
// 이후에야 findAllByProductId()(non-locking)를 호출한다 - #45/#46이 확립한 "락 이전의 non-locking
// read가 REPEATABLE READ snapshot을 먼저 고정시키면 안 된다"는 원칙을 그대로 따른다(Auction.
// getProduct().getId()는 Hibernate가 FK 컬럼만으로 해석하므로 프록시 초기화 쿼리를 만들지 않는다 -
// 이 트랜잭션의 첫 non-locking read는 여전히 findAllByProductId()다). "SCHEDULED/LIVE 동시 1건"
// 불변식은 Auction.uk_auction_product_active_slot(activeSlot 유니크 제약)이 최종 방어선으로
// 보강한다 - "총 2회" 개수 제약은 DB 유니크로 표현할 수 없어 이 Product 락이 유일한 방어선이다.
@Service
public class AuctionRelistService {

    static final int MAX_REGISTRATIONS_PER_PRODUCT = 2;

    private final ProductRepository productRepository;
    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final Clock clock;

    public AuctionRelistService(
            ProductRepository productRepository,
            AuctionRepository auctionRepository,
            BidRepository bidRepository,
            Clock clock
    ) {
        this.productRepository = productRepository;
        this.auctionRepository = auctionRepository;
        this.bidRepository = bidRepository;
        this.clock = clock;
    }

    @Transactional
    public AuctionRelistResponse relist(Long previousAuctionId, Long currentUserId, RelistAuctionRequest request) {
        Auction previousAuction = auctionRepository.findByIdForUpdate(previousAuctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + previousAuctionId));

        // previousAuction.getProduct().getId()는 지연 로딩 프록시의 식별자 접근이라 별도 SELECT를
        // 만들지 않는다(product_id는 이미 auctions row 자체에 있는 FK 컬럼) - 이 트랜잭션의 첫
        // non-locking read를 여기서 만들지 않기 위해 의도적으로 이 방식을 쓴다.
        Long productId = previousAuction.getProduct().getId();
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException("존재하지 않는 상품입니다. productId: " + productId));

        if (!product.getSeller().getId().equals(currentUserId)) {
            throw new AuctionSellerMismatchException(
                    "본인 상품에 대해서만 재경매를 등록할 수 있습니다. previousAuctionId: " + previousAuctionId
            );
        }

        validateEligibleForRelist(previousAuction);

        AuctionSchedulePolicy.validate(request.startAt(), request.endAt(), clock);

        List<Auction> priors = auctionRepository.findAllByProductId(productId);

        boolean hasActive = priors.stream().anyMatch(a ->
                a.getStatus() == AuctionStatus.SCHEDULED || a.getStatus() == AuctionStatus.LIVE
        );
        if (hasActive) {
            throw new ActiveAuctionAlreadyExistsException(
                    "이미 예약되었거나 진행 중인 경매가 있습니다. productId: " + productId
            );
        }
        if (priors.size() >= MAX_REGISTRATIONS_PER_PRODUCT) {
            throw new AuctionRegistrationLimitExceededException(
                    "경매 등록 가능 횟수(최대 " + MAX_REGISTRATIONS_PER_PRODUCT + "회)를 모두 사용했습니다. productId: " + productId
            );
        }
        for (Auction prior : priors) {
            if (prior.getStatus() == AuctionStatus.ENDED && bidRepository.countByAuctionId(prior.getId()) > 0) {
                throw new AuctionNotEligibleForReregistrationException(
                        "입찰이 있었던 경매는 재등록할 수 없습니다. productId: " + productId
                );
            }
        }

        LocalDateTime startAt = TimePolicy.fromApiTime(request.startAt());
        LocalDateTime endAt = TimePolicy.fromApiTime(request.endAt());
        Auction auction = Auction.schedule(
                product, request.startPrice(), BidIncrementPolicy.DEFAULT_BID_INCREMENT, startAt, endAt
        );
        Auction saved = auctionRepository.save(auction);
        return AuctionRelistResponse.from(saved, previousAuctionId);
    }

    // previousAuction 자신이 유찰(ENDED + 입찰 0건) 또는 시작 전 취소(CANCELED)인 경우에만
    // 재경매 대상으로 인정한다 - SCHEDULED/LIVE(아직 끝나지 않음)나 ENDED인데 입찰이 있었던
    // 경우(낙찰 발생, 결제 상태와 무관)는 여기서 막는다.
    private void validateEligibleForRelist(Auction previousAuction) {
        if (previousAuction.getStatus() == AuctionStatus.CANCELED) {
            return;
        }
        if (previousAuction.getStatus() == AuctionStatus.ENDED) {
            if (bidRepository.countByAuctionId(previousAuction.getId()) > 0) {
                throw new AuctionNotEligibleForReregistrationException(
                        "입찰이 있었던 경매는 재등록할 수 없습니다. previousAuctionId: " + previousAuction.getId()
                );
            }
            return;
        }
        throw new AuctionNotEligibleForReregistrationException(
                "유찰되었거나 시작 전 취소된 경매만 재경매할 수 있습니다. previousAuctionId: " + previousAuction.getId()
        );
    }
}
