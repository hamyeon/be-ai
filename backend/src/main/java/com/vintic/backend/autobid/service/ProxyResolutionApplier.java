package com.vintic.backend.autobid.service;

import com.vintic.backend.autobid.domain.AutoBidSetting;
import com.vintic.backend.autobid.proxy.ProxyEntrantStatus;

// ProxyPriceEngine의 결과(목표 상태)를 실제 AutoBidSetting에 반영하는 방법을 한 곳에서 정의한다.
// AutoBidCommandService(AUTO trigger)와 BidCommandService(MANUAL trigger) 양쪽이 재사용한다 -
// Proxy 가격 계산식은 여기 없다(그건 ProxyPriceEngine의 책임), 이건 "목표 상태 → 실제 도메인
// 전이 메서드 호출"만 담당하는 얇은 매핑이다.
public final class ProxyResolutionApplier {

    private ProxyResolutionApplier() {
    }

    public static void applyStatus(AutoBidSetting setting, ProxyEntrantStatus target) {
        if (target == ProxyEntrantStatus.ACTIVE) {
            switch (setting.getStatus()) {
                case RESERVED -> setting.activate();
                case CAP_REACHED -> setting.reactivateAfterCapIncrease();
                case ACTIVE -> {
                    // 이미 ACTIVE - 아무 것도 하지 않는다.
                }
                case CANCELED -> throw new IllegalStateException(
                        "CANCELED 상태의 AutoBidSetting은 Proxy resolution 대상이 될 수 없습니다. id: " + setting.getId()
                );
            }
        } else if (setting.getStatus() != com.vintic.backend.autobid.domain.AutoBidSettingStatus.CAP_REACHED) {
            setting.markCapReached();
        }
    }
}
