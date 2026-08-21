package com.vintic.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

// Redis 기반 캐시 설정.
//
// 같은 Redis를 분석 작업 큐(Redis Streams, "ai:analysis:requests")로도 쓰고 있으므로
// 캐시 키는 전부 "cache:" 네임스페이스 아래로 몰아넣는다. 운영 중 캐시만 비워야 할 때
// SCAN cache:* 로 큐 메시지를 건드리지 않고 지울 수 있어야 한다.
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String PRICING_CACHE = "pricing-result";

    // 가격 계산의 원천(KREAM/eBay CSV)은 배포 시에만 바뀐다. 다만 Redis는 배포를 넘어 살아남으므로
    // TTL 없이 두면 CSV를 갈아끼워도 옛 가격이 계속 나온다. TTL이 사실상의 데이터 갱신 주기다.
    @Value("${cache.pricing.ttl-minutes:60}")
    private long pricingTtlMinutes;

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        // 값은 JSON으로 직렬화한다. JDK 직렬화는 DTO 필드가 바뀌면 역직렬화가 깨지고,
        // redis-cli로 캐시 내용을 눈으로 확인할 수도 없다.
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("cache:")
                .entryTtl(Duration.ofMinutes(pricingTtlMinutes))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .build();
    }
}
