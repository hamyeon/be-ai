package com.vintic.backend.backupoffer.service;

import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.dto.BackupOfferResponse;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.common.exception.BackupOfferAccessDeniedException;
import com.vintic.backend.common.exception.BackupOfferNotFoundException;
import com.vintic.backend.common.util.ProductDisplayName;
import com.vintic.backend.common.util.ShippingPolicy;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.product.domain.Product;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

// FINAL contract §15. #75부터 candidate 본인 여부를 검증한다(40305 BACKUP_OFFER_ACCESS_DENIED) -
// 이전까지는 backupOfferId를 아는 인증된 사용자라면 누구나 조회 가능했던 계약 침묵 gap이었다.
@Service
public class BackupOfferQueryService {

    private final BackupOfferRepository backupOfferRepository;
    private final Clock clock;

    public BackupOfferQueryService(BackupOfferRepository backupOfferRepository, Clock clock) {
        this.backupOfferRepository = backupOfferRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BackupOfferResponse getBackupOffer(Long backupOfferId, Long userId) {
        BackupOffer offer = backupOfferRepository.findByIdWithAuctionAndProduct(backupOfferId)
                .orElseThrow(() -> new BackupOfferNotFoundException(
                        "존재하지 않는 차순위 제안입니다. backupOfferId: " + backupOfferId
                ));
        if (!offer.isOwnedBy(userId)) {
            throw new BackupOfferAccessDeniedException(
                    "본인 명의의 차순위 제안이 아닙니다. backupOfferId: " + backupOfferId
            );
        }
        Product product = offer.getAuction().getProduct();

        Long totalAmount = offer.getPurchasePrice() + ShippingPolicy.FLAT_FEE;

        return new BackupOfferResponse(
                offer.getId(),
                offer.getAuction().getId(),
                offer.getStatus(),
                new BackupOfferResponse.Product(
                        product.getId(),
                        ProductDisplayName.name(product),
                        ProductDisplayName.subName(product),
                        product.getImageUrls().isEmpty() ? null : product.getImageUrls().get(0)
                ),
                offer.getPurchasePrice(),
                ShippingPolicy.FLAT_FEE,
                totalAmount,
                TimePolicy.toApiTime(offer.getDeadline()),
                TimePolicy.toApiTime(LocalDateTime.now(clock))
        );
    }
}
