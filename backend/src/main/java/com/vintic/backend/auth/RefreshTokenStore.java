package com.vintic.backend.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

// #75-4D: Refresh Token의 유일한 서버측 기록. key/value 구조는 refresh:{jti} -> userId 하나뿐이다
// - 범용 session repository(디바이스 정보, 발급 이력 등)를 만들지 않는다. rotation도 하지 않는다
// (save는 로그인 시 1회만 호출된다, refresh는 이 store를 갱신하지 않는다).
//
// TTL은 JwtProperties의 refresh TTL 설정값을 다시 계산하지 않고, 이미 발급/파싱된 Refresh JWT의
// 실제 expiresAt(JwtClaims.expiresAt())을 그대로 source of truth로 사용한다 - 설정값과 토큰
// 실제 만료시각이 어긋날 여지를 만들지 않는다.
//
// 기존 spring-boot-starter-data-redis 자동구성 StringRedisTemplate을 그대로 재사용한다(#25
// AnalysisTaskProducer와 동일 관례) - 새 RedisTemplate/Config를 만들지 않는다.
@Component
@Profile({"dev", "prod"})
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public RefreshTokenStore(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    public void save(String jti, Long userId, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(clock), expiresAt);
        redisTemplate.opsForValue().set(key(jti), String.valueOf(userId), ttl);
    }

    public Optional<Long> findUserId(String jti) {
        String value = redisTemplate.opsForValue().get(key(jti));
        return Optional.ofNullable(value).map(Long::valueOf);
    }

    // 존재하지 않는 key를 삭제해도 예외 없이 조용히 넘어간다(Redis DEL의 기본 동작) - logout의
    // idempotent 요구사항(§5)이 별도 존재 확인 없이 자연스럽게 만족된다.
    public void delete(String jti) {
        redisTemplate.delete(key(jti));
    }

    private String key(String jti) {
        return KEY_PREFIX + jti;
    }
}
