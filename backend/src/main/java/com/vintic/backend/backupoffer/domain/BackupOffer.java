package com.vintic.backend.backupoffer.domain;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.common.exception.InvalidBackupOfferStatusException;
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
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

// FINAL contract §15-17. purchasePrice는 "totalAmount/shippingFee를 미리 계산해 얼리는" Order와
// 달리 이 엔티티에 저장하지 않는다 - shippingFee가 전역 고정 상수(ShippingPolicy)라 조회 시점에
// 그냥 더하면 된다(BackupOfferQueryService에서 계산). accept()가 실제로 Order를 만드는 시점에는
// Order 쪽에 그 값이 얼려 저장된다(BackupOfferCommandService.accept() 참고) - BackupOffer
// 자신은 purchasePrice 원본 값만 갖고 있으면 충분하다.
//
// uk_backup_offer_auction_candidate: 같은 (auction, candidate) 조합은 최대 1건만 존재해야
// 한다는 DB invariant다. AuctionForfeitService가 Auction row lock으로 동시 forfeit 호출을
// 이미 직렬화하므로 정상 경로에서 걸릴 일은 없지만, service check만으로 중복을 보장하지 않는다는
// 방침(#56-0 §8)에 따라 uk_order_auction_buyer(#56-1)와 동일한 층위의 최종 방어선으로 둔다.
@Entity
@Table(
        name = "backup_offers",
        uniqueConstraints = @UniqueConstraint(name = "uk_backup_offer_auction_candidate", columnNames = {"auction_id", "candidate_id"}),
        indexes = {
                @Index(name = "idx_backup_offer_candidate", columnList = "candidate_id")
        }
)
public class BackupOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", nullable = false)
    private User candidate;

    @Column(nullable = false)
    private Long purchasePrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BackupOfferStatus status;

    @Column(nullable = false)
    private LocalDateTime deadline;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected BackupOffer() {
    }

    // 차순위 제안의 유일한 생성 진입점이다. deadline = createdAt + 24h(§0.10 "차순위 제안 응답
    // 기한"). purchasePrice는 호출자가 넘긴다 - "차순위 후보의 마지막 유효 입찰가"를 찾는 책임은
    // rank 산정 쿼리(BidRepository)를 이미 가진 AuctionForfeitService에 있고, 이 팩토리는 그
    // 값을 검증 없이 그대로 저장한다.
    public static BackupOffer create(Auction auction, User candidate, Long purchasePrice) {
        if (auction == null) {
            throw new IllegalArgumentException("경매는 필수입니다.");
        }
        if (candidate == null) {
            throw new IllegalArgumentException("차순위 후보는 필수입니다.");
        }
        if (purchasePrice == null || purchasePrice <= 0) {
            throw new IllegalArgumentException("구매 가능 금액은 0보다 커야 합니다.");
        }

        BackupOffer offer = new BackupOffer();
        offer.auction = auction;
        offer.candidate = candidate;
        offer.purchasePrice = purchasePrice;
        offer.status = BackupOfferStatus.WAITING;
        offer.createdAt = LocalDateTime.now();
        offer.deadline = offer.createdAt.plusHours(24);
        return offer;
    }

    // FINAL contract §16: WAITING만 accept 가능. 시간 만료(§0.10) 판정은 이 메서드의 책임이
    // 아니다 - BackupOfferCommandService가 accept() 호출 전에 isExpired()로 먼저 걸러
    // BackupOfferExpiredException(40911)을 던진다. 이미 처리된(ACCEPTED/DECLINED/EXPIRED) 상태에서
    // 호출되면 도메인 boundary에서 막는다 - 서비스가 먼저 걸렀어야 하는 상황이라 일반 상태 가드
    // 예외를 던진다(계약이 요구하는 40912 매핑은 서비스가 한다, Order.cancel()과 동일 패턴).
    public void accept() {
        if (status != BackupOfferStatus.WAITING) {
            throw new InvalidBackupOfferStatusException(
                    "WAITING 상태에서만 수락할 수 있습니다. backupOfferId: " + id + ", 현재 상태: " + status
            );
        }
        this.status = BackupOfferStatus.ACCEPTED;
    }

    // FINAL contract §17: WAITING만 decline 가능. accept()와 동일하게 상태 가드만 domain
    // boundary에서 책임진다.
    public void decline() {
        if (status != BackupOfferStatus.WAITING) {
            throw new InvalidBackupOfferStatusException(
                    "WAITING 상태에서만 거절할 수 있습니다. backupOfferId: " + id + ", 현재 상태: " + status
            );
        }
        this.status = BackupOfferStatus.DECLINED;
    }

    // §0.10 "차순위 제안 응답 기한" 판정. scheduler 없이도(#57 이전) accept가 lazy하게 만료를
    // 감지할 수 있어야 하므로 status가 아니라 deadline을 직접 비교한다 - status는 이 판정만으로
    // EXPIRED로 바뀌지 않는다(실제 상태 전이는 #57 scheduler의 책임, 여기선 판정만 한다).
    public boolean isExpired(LocalDateTime now) {
        return now.isAfter(deadline);
    }

    public Long getId() {
        return id;
    }

    public Auction getAuction() {
        return auction;
    }

    public User getCandidate() {
        return candidate;
    }

    public Long getPurchasePrice() {
        return purchasePrice;
    }

    public BackupOfferStatus getStatus() {
        return status;
    }

    public LocalDateTime getDeadline() {
        return deadline;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
