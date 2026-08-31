package com.vintic.backend.backupoffer.dto;

import com.vintic.backend.backupoffer.domain.BackupOfferStatus;

// FINAL contract §17. status는 항상 DECLINED 고정. 다음 순위 BackupOffer 생성 여부는 순수
// side-effect다 - 계약이 이 응답에 그 정보를 포함하지 않는다.
public record BackupOfferDeclineResponse(
        Long backupOfferId,
        BackupOfferStatus status
) {
}
