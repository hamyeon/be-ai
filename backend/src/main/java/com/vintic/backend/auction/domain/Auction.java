package com.vintic.backend.auction.domain;

import com.vintic.backend.common.exception.AlreadyHighestBidderException;
import com.vintic.backend.common.exception.AuctionClosedException;
import com.vintic.backend.common.exception.AuctionNotStartedException;
import com.vintic.backend.common.exception.BidAmountTooLowException;
import com.vintic.backend.common.exception.BidNotAlignedException;
import com.vintic.backend.common.exception.InvalidAuctionStatusException;
import com.vintic.backend.common.exception.SellerCannotBidException;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.LocalDateTime;

// current_winner_id는 LIVE 중 입찰 갱신 트랜잭션에서만 채워지며, 종료 시점 값을 그대로 고정(freeze)한다.
// 이번 범위에는 입찰 처리 자체가 없어 항상 null로 유지된다(입찰자 없이 종료되는 경우도 ENDED + winner null로 표현).
@Entity
@Table(
        name = "auctions",
        indexes = {
                @Index(name = "idx_auction_product", columnList = "product_id"),
                @Index(name = "idx_auction_status_end_at", columnList = "status, end_at"),
                @Index(name = "idx_auction_status_start_at", columnList = "status, start_at"),
                @Index(name = "idx_auction_current_winner", columnList = "current_winner_id")
        }
)
public class Auction {

    // 종료 연장 정책(FINAL contract §0.13/§9): 종료 1분 이내에 성공한 사용자 command로 실제 Bid가
    // 발생하면 +3분, 최대 3회. Proxy 내부 파생 응찰은 별도 트리거가 아니다 - 호출자(BidCommandService/
    // AutoBidCommandService)가 사용자 command당 maybeExtend()를 최대 1번만 호출해서 보장한다.
    public static final int MAX_EXTENSIONS = 3;
    private static final Duration EXTENSION_TRIGGER_WINDOW = Duration.ofMinutes(1);
    private static final Duration EXTENSION_DURATION = Duration.ofMinutes(3);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_winner_id")
    private User currentWinner;

    @Column(nullable = false)
    private Long startPrice;

    @Column(nullable = false)
    private Long currentPrice;

    @Column(nullable = false)
    private Long bidIncrement;

    @Column(nullable = false)
    private LocalDateTime startAt;

    @Column(nullable = false)
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuctionStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false, columnDefinition = "INT NOT NULL DEFAULT 0")
    private int extensionCount;

    protected Auction() {
    }

    public static Auction schedule(
            Product product,
            Long startPrice,
            Long bidIncrement,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        if (product == null) {
            throw new IllegalArgumentException("상품은 필수입니다.");
        }
        if (startPrice == null || startPrice <= 0) {
            throw new IllegalArgumentException("시작가는 0보다 커야 합니다.");
        }
        if (bidIncrement == null || bidIncrement <= 0) {
            throw new IllegalArgumentException("입찰 단위는 0보다 커야 합니다.");
        }
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("종료 시각은 시작 시각보다 이후여야 합니다.");
        }

        Auction auction = new Auction();
        auction.product = product;
        auction.startPrice = startPrice;
        auction.currentPrice = startPrice;
        auction.bidIncrement = bidIncrement;
        auction.startAt = startAt;
        auction.endAt = endAt;
        auction.status = AuctionStatus.SCHEDULED;
        auction.createdAt = LocalDateTime.now();
        return auction;
    }

    public void start() {
        if (status != AuctionStatus.SCHEDULED) {
            throw new InvalidAuctionStatusException(
                    "SCHEDULED 상태에서만 경매를 시작할 수 있습니다. 현재 상태: " + status
            );
        }
        this.status = AuctionStatus.LIVE;
    }

    public void end() {
        if (status != AuctionStatus.LIVE) {
            throw new InvalidAuctionStatusException(
                    "LIVE 상태에서만 경매를 종료할 수 있습니다. 현재 상태: " + status
            );
        }
        this.status = AuctionStatus.ENDED;
    }

    public void cancel() {
        if (status != AuctionStatus.SCHEDULED) {
            throw new InvalidAuctionStatusException(
                    "SCHEDULED 상태에서만 경매를 취소할 수 있습니다. 현재 상태: " + status
            );
        }
        this.status = AuctionStatus.CANCELED;
    }

    // 직접(수동) 입찰 전용 검증/갱신이다. 현재 최고입찰자의 재입찰 금지 규칙은
    // 직접 입찰에만 적용되며, Proxy/AutoBid의 cap 상향과는 별개 정책이라
    // 이 메서드를 그쪽에서 재사용하지 않는다.
    public void placeManualBid(User bidder, Long amount) {
        if (status == AuctionStatus.SCHEDULED) {
            throw new AuctionNotStartedException(
                    "아직 시작되지 않은 경매입니다. auctionId: " + id
            );
        }
        if (status != AuctionStatus.LIVE) {
            throw new AuctionClosedException(
                    "이미 종료되었거나 취소된 경매입니다. auctionId: " + id + ", 상태: " + status
            );
        }
        if (product.getSeller().isSameUser(bidder)) {
            throw new SellerCannotBidException(
                    "판매자는 자신의 경매에 입찰할 수 없습니다. auctionId: " + id
            );
        }
        if (currentWinner != null && currentWinner.isSameUser(bidder)) {
            throw new AlreadyHighestBidderException(
                    "이미 현재 최고입찰자입니다. auctionId: " + id
            );
        }
        long minAmount = getMinNextBidAmount();
        if (amount < minAmount) {
            throw new BidAmountTooLowException(
                    "입찰 금액은 " + minAmount + "원 이상이어야 합니다. 입력값: " + amount
            );
        }
        // min 미만(40904)과 별개 실패 코드(40913)다 - min 이상인 값 중에서만 배수 정렬을 확인한다.
        // AutoBid의 maxAmount에는 이 검증을 적용하지 않는다(실효 상한으로 동작, §5).
        if ((amount - currentPrice) % bidIncrement != 0) {
            throw new BidNotAlignedException(
                    "입찰 금액은 현재가로부터 " + bidIncrement + "원의 배수여야 합니다. 입력값: " + amount
            );
        }

        this.currentPrice = amount;
        this.currentWinner = bidder;
    }

    // 종료 연장 정책(FINAL contract §0.13/§9)의 유일한 진입점이다. 호출자가 "성공한 사용자 command당
    // 최대 1회"를 보장해야 한다 - 이 메서드 자체는 몇 번을 호출해도 방어하지 않는다(멱등하지 않음).
    // 경계값은 "종료 1분 이내"를 포함(inclusive)한다 - now가 endAt보다 1분 이상 이전이면 연장하지 않는다.
    public boolean maybeExtend(LocalDateTime now) {
        if (extensionCount >= MAX_EXTENSIONS) {
            return false;
        }
        if (now.isBefore(endAt.minus(EXTENSION_TRIGGER_WINDOW))) {
            return false;
        }
        this.endAt = this.endAt.plus(EXTENSION_DURATION);
        this.extensionCount++;
        return true;
    }

    // 직접입찰 금액 하한이자, 신규 AutoBid가 유효한 상한가로 받아들여지기 위한 최소값(minCapAmount)이기도 하다 -
    // 두 곳에서 같은 값을 각자 계산하면 어긋날 수 있어 이 메서드 하나로 통일한다.
    public Long getMinNextBidAmount() {
        return currentPrice + bidIncrement;
    }

    // Proxy 가격 결정(ProxyPriceEngine) 결과를 반영하는 전용 mutator다. cap/tie/effectiveCap 같은
    // Proxy 정책 판단은 이 메서드의 책임이 아니다(Engine/Service가 이미 끝낸 뒤 호출) - 다만
    // Auction 자신이 지켜야 하는 최소 구조적 불변식(승자 필수, 가격 하락 금지)은 여기서 방어한다.
    public void applyProxyResult(User winner, Long newPrice) {
        if (winner == null) {
            throw new IllegalArgumentException("Proxy 결과의 승자는 필수입니다.");
        }
        if (newPrice == null || newPrice < currentPrice) {
            throw new IllegalArgumentException(
                    "Proxy 결과 가격은 현재가보다 낮을 수 없습니다. currentPrice: " + currentPrice + ", newPrice: " + newPrice
            );
        }
        this.currentPrice = newPrice;
        this.currentWinner = winner;
    }

    // AutoBid PATCH/DELETE 등 placeManualBid 밖에서도 "이미 끝난 경매인가"를 판정해야 하는
    // 곳이 있어 분리했다. SCHEDULED는 포함하지 않는다 - AutoBid는 SCHEDULED에서도 정상 동작해야
    // 하므로(RESERVED) placeManualBid의 "status != LIVE ⇒ closed" 판정을 그대로 재사용할 수 없다.
    public boolean isClosed() {
        return status == AuctionStatus.ENDED || status == AuctionStatus.CANCELED;
    }

    // /live의 canBid 판정 전용이다. placeManualBid()와 같은 순서(제재→미시작→종료→판매자→최고입찰자)를
    // 따르되 금액 검증(BID_AMOUNT_TOO_LOW/BID_NOT_ALIGNED)은 포함하지 않는다 - 계약상 canBid는
    // 금액을 입력하기 전에 버튼을 눌러도 되는지만 의미하기 때문이다.
    public CannotBidReason determineCannotBidReason(User user, LocalDateTime now) {
        if (user.isBidRestricted(now)) {
            return CannotBidReason.PENALTY_RESTRICTED;
        }
        if (status == AuctionStatus.SCHEDULED) {
            return CannotBidReason.AUCTION_NOT_STARTED;
        }
        if (status != AuctionStatus.LIVE) {
            return CannotBidReason.AUCTION_CLOSED;
        }
        if (product.getSeller().isSameUser(user)) {
            return CannotBidReason.SELLER_CANNOT_BID;
        }
        if (currentWinner != null && currentWinner.isSameUser(user)) {
            return CannotBidReason.ALREADY_HIGHEST_BIDDER;
        }
        return null;
    }

    public Long getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public User getCurrentWinner() {
        return currentWinner;
    }

    public Long getStartPrice() {
        return startPrice;
    }

    public Long getCurrentPrice() {
        return currentPrice;
    }

    public Long getBidIncrement() {
        return bidIncrement;
    }

    public LocalDateTime getStartAt() {
        return startAt;
    }

    public LocalDateTime getEndAt() {
        return endAt;
    }

    public AuctionStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public int getExtensionCount() {
        return extensionCount;
    }
}
