package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.ai.purchase.model.BrandAliases;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import com.vintic.backend.ai.purchase.price.PriceEstimate;
import com.vintic.backend.ai.purchase.price.PriceEstimateProvider;
import com.vintic.backend.ai.purchase.price.PriceEstimateQuery;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.domain.PurchaseGoalStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// Purchase Agent Day 3: ACTIVE 상태이고 마감이 지나지 않은 Goal에 대해, 지금 참여를 검토할 만한
// 경매 후보만 골라낸다. Matcher 호출·평가 이력 저장·순위/최종 cap 계산·Scheduler 실행·Goal 상태
// 전이·AutoBid 생성은 이 서비스의 책임이 아니다(Day 4/5) - 여기서는 명백히 부적격한 후보를
// 값싸게 걸러내는 hard filter만 한다.
@Service
@Slf4j
public class PurchaseGoalCandidateFinder {

    private static final Duration SCHEDULED_WINDOW = Duration.ofHours(24);

    private final AuctionRepository auctionRepository;
    private final AutoBidSettingRepository autoBidSettingRepository;
    private final ModelAliases modelAliases;
    private final PriceEstimateProvider priceEstimateProvider;
    private final Clock clock;

    public PurchaseGoalCandidateFinder(
            AuctionRepository auctionRepository,
            AutoBidSettingRepository autoBidSettingRepository,
            ModelAliases modelAliases,
            PriceEstimateProvider priceEstimateProvider,
            Clock clock
    ) {
        this.auctionRepository = auctionRepository;
        this.autoBidSettingRepository = autoBidSettingRepository;
        this.modelAliases = modelAliases;
        this.priceEstimateProvider = priceEstimateProvider;
        this.clock = clock;
    }

    // 방어적 가드다 - Day 4가 넘기는 Goal은 이미 ACTIVE/마감 전만 고른 것으로 기대하지만,
    // 이 서비스가 "그 전제가 깨지면 후보를 하나도 내지 않는다"를 스스로 보장한다.
    public List<PurchaseGoalCandidate> findCandidates(PurchaseGoal goal) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (goal.getStatus() != PurchaseGoalStatus.ACTIVE || !goal.getDeadline().isAfter(now)) {
            return List.of();
        }

        List<Auction> timeWindowCandidates = auctionRepository.findCandidatesForPurchaseAgent(
                AuctionStatus.LIVE, AuctionStatus.SCHEDULED, now, now.plus(SCHEDULED_WINDOW));
        if (timeWindowCandidates.isEmpty()) {
            return List.of();
        }

        List<Long> auctionIds = timeWindowCandidates.stream().map(Auction::getId).toList();
        Set<Long> alreadyManaged = Set.copyOf(autoBidSettingRepository
                .findAuctionIdsWithExistingSetting(goal.getUser().getId(), auctionIds));

        List<PurchaseGoalCandidate> candidates = new ArrayList<>();
        for (Auction auction : timeWindowCandidates) {
            if (alreadyManaged.contains(auction.getId())) {
                continue;
            }
            Product product = auction.getProduct();
            if (!matchesGoal(goal, product)) {
                continue;
            }
            if (goal.getHardMaxAmount() < auction.getMinNextBidAmount()) {
                continue;
            }
            estimatePrice(product).ifPresent(estimate -> candidates.add(new PurchaseGoalCandidate(auction, estimate)));
        }
        return candidates;
    }

    private boolean matchesGoal(PurchaseGoal goal, Product product) {
        if (goal.getModelKey() != null) {
            Optional<ModelAliases.Match> match = modelAliases.find(joinBrandModel(product));
            if (match.isEmpty() || !match.get().model().modelKey().equalsIgnoreCase(goal.getModelKey())) {
                return false;
            }
        } else if (goal.getBrand() != null && !brandMatches(goal.getBrand(), product.getBrand())) {
            return false;
        }

        Optional<GoalCondition> listingGrade = GoalCondition.fromLabel(product.getConditionGrade());
        if (listingGrade.isEmpty() || !goal.getMinCondition().satisfiedBy(listingGrade.get())) {
            return false;
        }

        if (goal.getSizeKr() != null
                && (product.getSizeKr() == null || !goal.getSizeKr().equals(product.getSizeKr()))) {
            return false;
        }

        return true;
    }

    // BrandAliases.canonical()로 둘 다 정규화되면 그 결과로 비교한다(예: "뉴발" == "New Balance").
    // 둘 중 하나라도 별칭 표 밖이면(카탈로그에 없는 브랜드) 원문 대소문자 무시 비교로 대체한다.
    private boolean brandMatches(String goalBrand, String productBrand) {
        Optional<String> goalCanonical = BrandAliases.canonical(goalBrand);
        Optional<String> productCanonical = BrandAliases.canonical(productBrand);
        if (goalCanonical.isPresent() && productCanonical.isPresent()) {
            return goalCanonical.get().equals(productCanonical.get());
        }
        return goalBrand.equalsIgnoreCase(productBrand);
    }

    private String joinBrandModel(Product product) {
        String brand = product.getBrand();
        String model = product.getModel();
        if (brand == null || brand.isBlank()) {
            return model == null ? "" : model;
        }
        return model == null ? brand : brand + " " + model;
    }

    // PriceEstimateProvider 실패(예외)가 이 후보 하나만 제외시키고 나머지 후보 평가를 막지 않게
    // 한다. Optional.empty()(계산 불가)와 estimatedPrice<=0(유효하지 않은 값)도 동일하게 제외한다.
    private Optional<PriceEstimate> estimatePrice(Product product) {
        try {
            return priceEstimateProvider.estimate(PriceEstimateQuery.of(product))
                    .filter(estimate -> estimate.estimatedPrice() > 0);
        } catch (RuntimeException e) {
            log.warn("Purchase Agent 시세 계산 실패 - productId: {}", product.getId(), e);
            return Optional.empty();
        }
    }
}
