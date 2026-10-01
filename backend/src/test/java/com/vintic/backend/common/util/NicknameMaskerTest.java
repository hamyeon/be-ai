package com.vintic.backend.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NicknameMaskerTest {

    @Test
    void 닉네임이_1자면_그_1글자와_별표_4개를_반환한다() {
        assertThat(NicknameMasker.mask("a")).isEqualTo("a****");
    }

    @Test
    void 닉네임이_2자면_첫_1글자와_별표_4개를_반환한다() {
        assertThat(NicknameMasker.mask("ab")).isEqualTo("a****");
    }

    @Test
    void 닉네임이_3자면_3글자_전체와_별표_4개를_반환한다() {
        assertThat(NicknameMasker.mask("abc")).isEqualTo("abc****");
    }

    @Test
    void 닉네임이_3자를_초과하면_앞_3글자와_별표_4개를_반환한다() {
        assertThat(NicknameMasker.mask("mmaybeii")).isEqualTo("mma****");
        assertThat(NicknameMasker.mask("hamburger")).isEqualTo("ham****");
    }
}
