package com.vintic.backend.backupoffer.service;

import com.vintic.backend.backupoffer.domain.BackupOffer;
import com.vintic.backend.backupoffer.dto.BackupOfferResponse;
import com.vintic.backend.backupoffer.repository.BackupOfferRepository;
import com.vintic.backend.common.exception.BackupOfferNotFoundException;
import com.vintic.backend.common.util.ProductDisplayName;
import com.vintic.backend.common.util.ShippingPolicy;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.product.domain.Product;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

// FINAL contract §15. 소유자 검증(예: GET /orders/{id}의 40304 ORDER_ACCESS_DENIED)이 계약에
// 없다 - backupOfferId를 아는 인증된 사용자라면 누구나 조회 가능하다(계약을 그대로 반영, 임의로
// 권한 체크를 추가하지 않았다).
@Service
public class BackupOfferQueryService {

    private final BackupOfferRepository backupOfferRepository;
    private final Clock clock;

    public BackupOfferQueryService(BackupOfferRepository backupOfferRepository, Clock clock) {
        this.backupOfferRepository = backupOfferRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BackupOfferResponse getBackupOffer(Long backupOfferId) {
        BackupOffer offer = backupOfferRepository.findByIdWithAuctionAndProduct(backupOfferId)
                .orElseThrow(() -> new BackupOfferNotFoundException(
                        "존재하지 않는 차순위 제안입니다. backupOfferId: " + backupOfferId
                ));
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
