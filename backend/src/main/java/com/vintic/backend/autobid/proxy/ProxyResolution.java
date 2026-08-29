package com.vintic.backend.autobid.proxy;

import java.util.List;

public record ProxyResolution(
        Long finalCurrentPrice,
        Long finalWinnerUserId,
        boolean priceChanged,
        ResultingAutoBid resultingAutoBid,
        boolean proxyResponded,
        List<CandidateResult> candidateResults
) {
}
