package com.vintic.backend.bid.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BidRequestHashTest {

    @Test
    void 같은_amount는_항상_같은_해시를_만든다() {
        String first = BidRequestHash.sha256(15000L);
        String second = BidRequestHash.sha256(15000L);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64);
    }

    @Test
    void 다른_amount는_다른_해시를_만든다() {
        String forFifteen = BidRequestHash.sha256(15000L);
        String forTwenty = BidRequestHash.sha256(20000L);

        assertThat(forFifteen).isNotEqualTo(forTwenty);
    }
}
