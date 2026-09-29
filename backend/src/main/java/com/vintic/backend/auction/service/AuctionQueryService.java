package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.domain.CannotBidReason;
import com.vintic.backend.auction.dto.AuctionDetailResponse;
import com.vintic.backend.auction.dto.AuctionLiveResponse;
import com.vintic.backend.auction.dto.SimilarAuctionsResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.dto.AutoBidRecommendationResponse;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.bid.repository.BidRepository;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.UserNotFoundException;
import com.vintic.backend.common.util.NicknameMasker;
import com.vintic.backend.common.util.ProductDisplayName;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.ai.purchase.price.PriceEstimate;
import com.vintic.backend.ai.purchase.price.PriceEstimateProvider;
import com.vintic.backend.ai.purchase.price.PriceEstimateQuery;
import com.vintic.backend.like.repository.AuctionLikeRepository;
import com.vintic.backend.order.domain.OrderStatus;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.purchasegoal.service.AgentManagedAuctionGuard;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AuctionQueryService {

    // #55: 상세 화면 "추천상품" 영역(프론트 확인 결과 이 endpoint 하나가 그 영역을 담당하며
    // 별도 추천경매/추천상품 API는 없다)에 노출할 최대 개수. 4개로 확정(사용자 지시).
    private static final int SIMILAR_LIMIT = 4;

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final AutoBidSettingRepository autoBidSettingRepository;
    private final AuctionLikeRepository auctionLikeRepository;
    private final OrderRepository orderRepository;
    private final AgentManagedAuctionGuard agentManagedAuctionGuard;
    private final Clock clock;
    private final PriceEstimateProvider priceEstimateProvider;

    public AuctionQueryService(
            AuctionRepository auctionRepository,
            BidRepository bidRepository,
            UserRepository userRepository,
            AutoBidSettingRepository autoBidSettingRepository,
            AuctionLikeRepository auctionLikeRepository,
            OrderRepository orderRepository,
            AgentManagedAuctionGuard agentManagedAuctionGuard,
            Clock clock,
            PriceEstimateProvider priceEstimateProvider
    ) {
        this.auctionRepository = auctionRepository;
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.autoBidSettingRepository = autoBidSettingRepository;
        this.auctionLikeRepository = auctionLikeRepository;
        this.orderRepository = orderRepository;
        this.agentManagedAuctionGuard = agentManagedAuctionGuard;
        this.clock = clock;
        this.priceEstimateProvider = priceEstimateProvider;
    }

    // viewerUserId는 null일 수 있다(#55: 상세조회는 기존부터 비로그인 접근을 허용해왔다 - 계약상
    // Authorization은 필수지만, 이 endpoint를 인증 필수로 바꾸는 것은 도메인 정책 변경이라 이번
    // 범위에서 임의로 바꾸지 않았다. 완료 보고의 gap 항목 참고). 비로그인이면 myState/isLiked는
    // 개인화 없이 중립값을 반환한다.
    @Transactional(readOnly = true)
    public AuctionDetailResponse getAuctionDetail(Long auctionId, Long viewerUserId) {
        Auction auction = auctionRepository.findByIdWithProductAndWinner(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));
        Product product = auction.getProduct();
        User seller = product.getSeller();

        long bidCount = bidRepository.countByAuctionId(auctionId);
        long likeCount = auctionLikeRepository.countByAuctionId(auctionId);

        LocalDateTime now = LocalDateTime.now(clock);
        User viewer = viewerUserId == null ? null : userRepository.findById(viewerUserId)
                .orElseThrow(() -> new UserNotFoundException("존재하지 않는 사용자입니다. userId: " + viewerUserId));

        boolean isLiked = viewer != null && auctionLikeRepository.existsByAuctionIdAndUserId(auctionId, viewerUserId);
        AuctionDetailResponse.MyState myState = buildMyState(auction, viewer, now);

        Long minNextBidAmount = auction.getMinNextBidAmount();
        Long finalPrice = auction.getStatus() == AuctionStatus.ENDED && auction.getCurrentWinner() != null
                ? auction.getCurrentPrice()
                : null;

        Optional<PriceEstimate> aiEstimate = estimateSafely(product);

        return new AuctionDetailResponse(
                auction.getId(),
                auction.getStatus(),
                new AuctionDetailResponse.Product(
                        product.getId(),
                        ProductDisplayName.name(product),
                        product.getBrand(),
                        ProductDisplayName.subName(product),
                        product.getConditionGrade(),
                        product.getImageUrls()
                ),
                new AuctionDetailResponse.Seller(
                        seller.getId(),
                        seller.getNickname(),
                        seller.getProfileImageUrl(),
                        // #56-1: Order 도메인이 생겨 실제 카운트로 연결한다. "판매 완료"는 PAID
                        // Order만 센다(#56-0 확정) - PAYMENT_PENDING/PAYMENT_EXPIRED/CANCELED는
                        // 제외. 단일 count(*) 쿼리라 N+1이 아니다.
                        (int) orderRepository.countByAuction_Product_Seller_IdAndStatus(seller.getId(), OrderStatus.PAID)
                ),
                product.getDescription(),
                auction.getStartPrice(),
                auction.getCurrentPrice(),
                auction.getBidIncrement(),
                minNextBidAmount,
                minNextBidAmount, // minCapAmount는 도메인상 minNextBidAmount와 항상 같다(§7/§live와 동일 정책).
                TimePolicy.toApiTime(auction.getStartAt()),
                TimePolicy.toApiTime(auction.getEndAt()),
                TimePolicy.toApiTime(now),
                aiEstimate.map(estimate -> (long) estimate.estimatedPrice()).orElse(null),
                minNextBidAmount, // aiRecommendedAutoBidCap: §4에서 이미 확정된 정책(buyer 전용 추천 소스 없음 -> minCapAmount)을 재사용한다.
                aiEstimate.map(PriceEstimate::reason).orElse(null),
                (int) bidCount,
                isLiked,
                (int) likeCount,
                myState,
                finalPrice
        );
    }

    // #96: aiEstimatedPrice/aiPriceReason는 Product.recommendedPrice/reason이 아니라 서버가 지금 계산한 값이다.
    //
    // Product.recommendedPrice는 ProductRegistrationService가 CreateProductRequest.recommendedPrice()를
    // 그대로 저장한 클라이언트 값이라, 판매자가 부풀리면 구매자 화면의 "AI 시세"가 그대로 부풀려진다.
    // Purchase Agent(#95)의 cap 입력으로도 같은 이유로 못 쓴다. PriceEstimateProvider는 기존
    // PricingService(캐시 60분)를 그대로 불러 같은 입력이면 같은 값을 준다 - 새 계산식은 없다.
    //
    // 시세를 못 내면(카탈로그 밖 모델) null이다 - 틀린 숫자보다 없음이 낫다(ai-system.md 원칙).
    // 시세 계산 실패가 경매 상세 조회를 실패시키면 안 되므로 예외는 삼키고 null로 둔다.
    private Optional<PriceEstimate> estimateSafely(Product product) {
        try {
            return priceEstimateProvider.estimate(PriceEstimateQuery.of(product));
        } catch (RuntimeException e) {
            log.warn("경매 상세 AI 시세 계산 실패 - null로 응답합니다. productId={}, message={}", product.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private AuctionDetailResponse.MyState buildMyState(Auction auction, User viewer, LocalDateTime now) {
        if (viewer == null) {
            return new AuctionDetailResponse.MyState(false, false, false, null, null, null, null, null, false);
        }

        boolean isSeller = auction.getProduct().getSeller().isSameUser(viewer);
        User winner = auction.getCurrentWinner();
        boolean isHighestBidder = winner != null && winner.isSameUser(viewer);

        CannotBidReason cannotBidReason = auction.determineCannotBidReason(viewer, now);
        boolean canBid = cannotBidReason == null;
        LocalDateTime bidRestrictedUntil = cannotBidReason == CannotBidReason.PENALTY_RESTRICTED
                ? viewer.getBidRestrictedUntil()
                : null;

        AutoBidSettingStatus autoBidStatus = null;
        Long autoBidCap = null;
        Long purchaseGoalId = null;
        boolean managedByPurchaseAgent = false;
        Optional<AutoBidSetting> setting = autoBidSettingRepository
                .findByAuctionIdAndUserIdAndActiveSlotTrue(auction.getId(), viewer.getId());
        if (setting.isPresent()) {
            autoBidStatus = setting.get().getStatus();
            autoBidCap = setting.get().getMaxAmount();
            purchaseGoalId = setting.get().getPurchaseGoalId();
            managedByPurchaseAgent = agentManagedAuctionGuard.isManaged(purchaseGoalId, auction.getId(), viewer.getId());
        }

        return new AuctionDetailResponse.MyState(
                isSeller,
                isHighestBidder,
                canBid,
                cannotBidReason,
                TimePolicy.toApiTime(bidRestrictedUntil),
                autoBidStatus,
                autoBidCap,
                purchaseGoalId,
                managedByPurchaseAgent
        );
    }

    @Transactional(readOnly = true)
    public AuctionLiveResponse getLiveView(Long auctionId, Long userId) {
        Auction auction = auctionRepository.findByIdWithProductAndWinner(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));
        User currentUser = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("존재하지 않는 사용자입니다. userId: " + userId));

        Long minNextBidAmount = auction.getMinNextBidAmount();
        User winner = auction.getCurrentWinner();
        String highestBidderMasked = winner == null ? null : NicknameMasker.mask(winner.getNickname());
        boolean isMine = winner != null && winner.isSameUser(currentUser);

        CannotBidReason cannotBidReason = auction.determineCannotBidReason(currentUser, LocalDateTime.now(clock));
        boolean canBid = cannotBidReason == null;
        LocalDateTime bidRestrictedUntil = cannotBidReason == CannotBidReason.PENALTY_RESTRICTED
                ? currentUser.getBidRestrictedUntil()
                : null;

        // #41: activeSlot=true인 "현재 설정"만 조회하므로(CANCELED는 항상 activeSlot=null이라
        // 이 쿼리에 아예 안 걸림) 여기서 status로 다시 CANCELED를 걸러낼 필요가 없다.
        AutoBidSettingStatus myAutoBidStatus = null;
        Long myCap = null;
        Optional<AutoBidSetting> setting = autoBidSettingRepository.findByAuctionIdAndUserIdAndActiveSlotTrue(auctionId, userId);
        if (setting.isPresent()) {
            myAutoBidStatus = setting.get().getStatus();
            myCap = setting.get().getMaxAmount();
        }

        return new AuctionLiveResponse(
                auction.getId(),
                auction.getStatus(),
                auction.getCurrentPrice(),
                minNextBidAmount,
                auction.getBidIncrement(),
                highestBidderMasked,
                isMine,
                canBid,
                cannotBidReason,
                TimePolicy.toApiTime(bidRestrictedUntil),
                TimePolicy.toApiTime(auction.getEndAt()),
                TimePolicy.toApiTime(LocalDateTime.now(clock)),
                auction.getExtensionCount(),
                Auction.MAX_EXTENSIONS,
                myAutoBidStatus,
                myCap,
                minNextBidAmount
        );
    }

    @Transactional(readOnly = true)
    public AutoBidRecommendationResponse getAutoBidRecommendation(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));
        Long minCapAmount = auction.getMinNextBidAmount();

        return new AutoBidRecommendationResponse(
                auction.getId(),
                minCapAmount,
                auction.getCurrentPrice(),
                minCapAmount,
                auction.getBidIncrement()
        );
    }

    // #55 Implementation Notes: 상세 화면 "추천상품" 영역(프론트 확인 결과, 별도 추천경매 API
    // 없음)에 대응하는 조회다. 선정 기준은 same-brand heuristic이다 - AI/embedding 추천이
    // 아니고 recommendation.ProductVectorService(개인화 추천용, 목적이 다름)를 의도적으로 쓰지
    // 않았다. 실제 선정 로직은 AuctionRepository.findSimilarByBrand() 한 곳에만 있다 - 이 메서드의
    // 나머지(자기 제외는 그 쿼리 파라미터, likeCount/isLiked 배치 조회, envelope 조립)는 선정
    // 기준이 나중에 바뀌어도 그대로 재사용 가능하도록 결합을 최소화했다. 정렬은 endAt asc, id asc로
    // 고정해(쿼리 자체에 명시) 동일 endAt 경매끼리도 항상 같은 순서가 나온다(deterministic
    // tie-break). likeCount/isLiked는 item 개수만큼 쿼리를 반복하지 않도록 배치 조회한다.
    @Transactional(readOnly = true)
    public SimilarAuctionsResponse getSimilarAuctions(Long auctionId, Long viewerUserId) {
        Auction auction = auctionRepository.findByIdWithProductAndWinner(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));

        List<Auction> candidates = auctionRepository.findSimilarByBrand(
                auction.getProduct().getBrand(),
                auctionId,
                List.of(AuctionStatus.LIVE, AuctionStatus.SCHEDULED),
                PageRequest.of(0, SIMILAR_LIMIT)
        );

        if (candidates.isEmpty()) {
            return new SimilarAuctionsResponse(List.of());
        }

        List<Long> candidateAuctionIds = candidates.stream().map(Auction::getId).toList();
        Map<Long, Long> likeCounts = auctionLikeRepository.countByAuctionIdIn(candidateAuctionIds).stream()
                .collect(Collectors.toMap(
                        AuctionLikeRepository.AuctionLikeCount::getAuctionId,
                        AuctionLikeRepository.AuctionLikeCount::getLikeCount
                ));
        Set<Long> likedAuctionIds = viewerUserId == null
                ? Set.of()
                : new HashSet<>(auctionLikeRepository.findLikedAuctionIds(viewerUserId, candidateAuctionIds));

        List<SimilarAuctionsResponse.Item> items = candidates.stream()
                .map(candidate -> {
                    Product product = candidate.getProduct();
                    return new SimilarAuctionsResponse.Item(
                            product.getId(),
                            candidate.getId(),
                            product.getBrand(),
                            ProductDisplayName.name(product),
                            product.getImageUrls().isEmpty() ? null : product.getImageUrls().get(0),
                            candidate.getCurrentPrice(),
                            likeCounts.getOrDefault(candidate.getId(), 0L).intValue(),
                            likedAuctionIds.contains(candidate.getId())
                    );
                })
                .toList();

        return new SimilarAuctionsResponse(items);
    }
}
