package com.vintic.backend.product.pricing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PricingRequestTest {

    @Test
    void 캐시_키는_대소문자와_공백_차이를_무시한다() {
        PricingRequest upper = new PricingRequest("Nike", "Dunk Low", "Panda", 270, "A", "FULL_SET");
        PricingRequest messy = new PricingRequest(" nike ", "dunk low", "PANDA", 270, "a", "full_set");

        assertThat(upper.cacheKey()).isEqualTo(messy.cacheKey());
    }

    @Test
    void 필드가_다르면_캐시_키도_다르다() {
        PricingRequest base = new PricingRequest("Nike", "Dunk Low", "Panda", 270, "A", "FULL_SET");
        PricingRequest otherSize = new PricingRequest("Nike", "Dunk Low", "Panda", 275, "A", "FULL_SET");
        PricingRequest otherGrade = new PricingRequest("Nike", "Dunk Low", "Panda", 270, "B", "FULL_SET");

        assertThat(base.cacheKey()).isNotEqualTo(otherSize.cacheKey());
        assertThat(base.cacheKey()).isNotEqualTo(otherGrade.cacheKey());
    }

    @Test
    void null_필드가_있어도_키가_만들어진다() {
        PricingRequest withNulls = new PricingRequest("Nike", null, null, null, null, null);

        assertThat(withNulls.cacheKey()).isEqualTo("nike|||||");
    }
}
