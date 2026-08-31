package com.vintic.backend.backupoffer.domain;

// FINAL contract §15. ACCEPTED/DECLINED는 #56-3(accept/decline)에서, EXPIRED는 #57(scheduler)
// 에서 실제로 도달하기 시작한다 - #56-2엔 WAITING 생성만 있다(BackupOffer.create() 참고).
public enum BackupOfferStatus {
    WAITING,
    ACCEPTED,
    DECLINED,
    EXPIRED
}
