package com.vintic.backend.backupoffer.service;

import com.vintic.backend.backupoffer.dto.BackupOfferAcceptResponse;
import com.vintic.backend.backupoffer.dto.BackupOfferDeclineResponse;
import com.vintic.backend.bid.service.IdempotencyClaimConflictException;
import com.vintic.backend.bid.service.IdempotencyClaimService;
import com.vintic.backend.common.util.RequestHasher;
import org.springframework.stereotype.Service;

// Controller의 얇은 진입점이다(AutoBidService와 동일한 역할 분담 - accept는 idempotency 경로를
// 타고 decline은 곧바로 커맨드로 위임한다). @Transactional을 직접 갖지 않는다 - 실제 트랜잭션
// 경계는 IdempotencyClaimService(accept)와 BackupOfferCommandService(decline)가 각자 갖는다.
@Service
public class BackupOfferService {

    private final IdempotencyClaimService idempotencyClaimService;
    private final BackupOfferCommandService backupOfferCommandService;

    public BackupOfferService(
            IdempotencyClaimService idempotencyClaimService,
            BackupOfferCommandService backupOfferCommandService
    ) {
        this.idempotencyClaimService = idempotencyClaimService;
        this.backupOfferCommandService = backupOfferCommandService;
    }

    // §0.11: ACCEPT_BACKUP_OFFER:{backupOfferId} scope. 요청 바디가 없어(§16) canonical
    // payload도 고정 문자열이다 - "다른 payload"가 존재할 수 없으므로 같은 key 재요청은 항상
    // 이 해시와 일치하고(exact replay), 계약이 요구하는 40905 IDEMPOTENCY_PAYLOAD_MISMATCH는
    // 이 endpoint에서 사실상 발생하지 않는다(그래도 인터페이스는 그대로 재사용한다 - 별도
    // 분기를 만들지 않는다).
    public BackupOfferAcceptResponse accept(Long backupOfferId, Long userId, String idempotencyKey) {
        String operationScope = "ACCEPT_BACKUP_OFFER:" + backupOfferId;
        String requestHash = RequestHasher.sha256("accept");

        try {
            return idempotencyClaimService.claimAndExecute(
                    userId, operationScope, idempotencyKey, requestHash,
                    BackupOfferAcceptResponse.class,
                    idempotencyId -> backupOfferCommandService.accept(backupOfferId, userId)
            );
        } catch (IdempotencyClaimConflictException e) {
            return idempotencyClaimService.resolveAfterConflict(
                    userId, operationScope, idempotencyKey, requestHash, BackupOfferAcceptResponse.class
            );
        }
    }

    // Idempotency-Key를 요구하지 않는다(§0.11에 이 endpoint가 없다).
    public BackupOfferDeclineResponse decline(Long backupOfferId, Long userId) {
        return backupOfferCommandService.decline(backupOfferId, userId);
    }
}
