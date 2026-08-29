package com.vintic.backend.autobid.domain;

// §0.13 effectiveCap 공식의 유일한 계산 지점이다. AutoBidSetting(entity)과 ProxyPriceEngine
// (순수 계산, entity에 접근할 수 없음) 양쪽이 같은 공식을 각자 다시 구현하지 않도록 여기 하나로
// 통일한다.
public final class EffectiveCapCalculator {

    private EffectiveCapCalculator() {
    }

    // maxAmount가 bidIncrement 배수로 정렬돼 있지 않아도(등록 시 정렬을 요구하지 않음) 실제 도달
    // 가능한 마지막 grid 지점을 반환한다. currentPrice 이하로는 절대 내려가지 않고, maxAmount를
    // 절대 초과하지 않는다(effectiveCap <= maxAmount).
    public static long calculate(long maxAmount, long currentPrice, long bidIncrement) {
        long steps = (maxAmount - currentPrice) / bidIncrement;
        long effectiveCap = currentPrice + steps * bidIncrement;
        if (effectiveCap > maxAmount) {
            // 수학적으로 발생할 수 없다(정수 나눗셈 floor 특성) - 계산식이 깨졌다는 신호이므로
            // 조용히 넘기지 않는다.
            throw new IllegalStateException(
                    "effectiveCap이 maxAmount를 초과했습니다. maxAmount=" + maxAmount
                            + ", currentPrice=" + currentPrice + ", bidIncrement=" + bidIncrement
            );
        }
        return effectiveCap;
    }
}
