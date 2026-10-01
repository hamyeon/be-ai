package com.vintic.backend.product.pricing;

import com.vintic.backend.config.CacheConfig;
import com.vintic.backend.product.dto.CalculatePriceRequest;
import com.vintic.backend.product.dto.CalculatePriceResponse;
import com.vintic.backend.product.service.PriceCalculationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// @Cacheable은 배선이 잘못돼도(프록시 미적용, SpEL 오타) 예외 없이 그냥 캐시 없이 돈다.
// 그래서 "같은 입력으로 두 번 불렀을 때 계산이 한 번만 실행되는가"를 직접 확인한다.
//
// Redis 대신 인메모리 캐시를 쓴다 - 여기서 검증할 것은 어노테이션/키 배선이지 Redis 자체가 아니고,
// Redis가 없는 CI에서도 항상 돌아야 하기 때문이다.
@SpringBootTest(classes = {RuleBasedPricingService.class, RuleBasedPricingServiceCacheTest.CacheTestConfig.class})
class RuleBasedPricingServiceCacheTest {

    @TestConfiguration
    @EnableCaching
    static class CacheTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(CacheConfig.PRICING_CACHE);
        }
    }

    @MockitoBean
    private PriceCalculationService priceCalculationService;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private CacheManager cacheManager;

    // 스프링 컨텍스트가 테스트 간에 공유되므로 캐시도 살아남는다.
    // 비우지 않으면 앞 테스트가 채운 캐시를 뒤 테스트가 타서 mock 검증이 어긋난다.
    @BeforeEach
    void clearCache() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    private static final CalculatePriceResponse RESPONSE = new CalculatePriceResponse(
            180000, 200000, 210000, 190000, 170000, 190000, "17만원 ~ 19만원", "테스트 사유",
            List.of(), List.of());

    private PricingRequest request(String brand) {
        return new PricingRequest(brand, "Dunk Low", "Panda", 270, "A", "FULL_SET");
    }

    @Test
    void 같은_입력으로_두_번_부르면_계산은_한_번만_실행된다() {
        when(priceCalculationService.calculate(any())).thenReturn(RESPONSE);

        PricingResult first = pricingService.calculate(request("Nike"));
        PricingResult second = pricingService.calculate(request("Nike"));

        assertThat(second).isEqualTo(first);
        verify(priceCalculationService, times(1)).calculate(any(CalculatePriceRequest.class));
    }

    @Test
    void 표기만_다른_같은_입력도_같은_캐시를_탄다() {
        // 시세 매칭이 대소문자/공백을 무시하므로 캐시 키도 그래야 한다.
        // 아니면 "Nike"와 " nike "가 각각 계산돼 캐시 적중률만 떨어진다.
        when(priceCalculationService.calculate(any())).thenReturn(RESPONSE);

        pricingService.calculate(request("Nike"));
        pricingService.calculate(request("  nike "));

        verify(priceCalculationService, times(1)).calculate(any(CalculatePriceRequest.class));
    }

    @Test
    void 입력이_다르면_각각_계산된다() {
        when(priceCalculationService.calculate(any())).thenReturn(RESPONSE);

        pricingService.calculate(request("Nike"));
        pricingService.calculate(request("Adidas"));

        verify(priceCalculationService, times(2)).calculate(any(CalculatePriceRequest.class));
    }
}
