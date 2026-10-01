package com.vintic.backend.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// #75-4D: 실제 로컬 Redis(docker-compose)에 대고 도는 통합 테스트 -
// AnalysisTaskProducerRedisIntegrationTest와 동일한 관례(연결 불가 시 조용히 skip). Redis에
// @Profile({"dev","prod"})가 걸려 있어도 이 테스트는 RefreshTokenStore를 Spring 빈으로
// 주입받지 않고 직접 생성한다 - AnalysisTaskProducer 테스트와 동일한 패턴.
@DataRedisTest
class RefreshTokenStoreTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private RefreshTokenStore refreshTokenStore;
    private String testJti;

    @BeforeEach
    void setUp() {
        boolean redisAvailable;
        try {
            redisAvailable = "PONG".equalsIgnoreCase(
                    String.valueOf(redisTemplate.getConnectionFactory().getConnection().ping())
            );
        } catch (Exception e) {
            redisAvailable = false;
        }
        assumeTrue(redisAvailable, "로컬 Redis에 연결할 수 없어 이 통합 테스트를 건너뜁니다.");

        refreshTokenStore = new RefreshTokenStore(redisTemplate, Clock.system(ZoneId.of("Asia/Seoul")));
        testJti = "test-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (testJti != null) {
            redisTemplate.delete("refresh:" + testJti);
        }
    }

    @Test
    void save한_jti로_userId를_조회할_수_있다() {
        refreshTokenStore.save(testJti, 42L, Instant.now().plus(Duration.ofMinutes(10)));

        Optional<Long> found = refreshTokenStore.findUserId(testJti);

        assertThat(found).contains(42L);
    }

    @Test
    void save시_실제_expiresAt_기준으로_TTL이_설정된다() {
        refreshTokenStore.save(testJti, 1L, Instant.now().plus(Duration.ofMinutes(10)));

        Long ttlSeconds = redisTemplate.getExpire("refresh:" + testJti);

        assertThat(ttlSeconds).isNotNull();
        // 10분 근접값(초 단위 오차 허용) - JwtProperties TTL을 다시 계산하지 않고 넘긴 expiresAt
        // 그대로 반영됐는지 확인한다.
        assertThat(ttlSeconds).isBetween(590L, 600L);
    }

    @Test
    void delete하면_더_이상_조회되지_않는다() {
        refreshTokenStore.save(testJti, 1L, Instant.now().plus(Duration.ofMinutes(10)));

        refreshTokenStore.delete(testJti);

        assertThat(refreshTokenStore.findUserId(testJti)).isEmpty();
    }

    @Test
    void 존재하지_않는_jti를_delete해도_예외가_발생하지_않는다() {
        refreshTokenStore.delete("never-existed-" + UUID.randomUUID());
    }

    // TTL이 실제로 짧게 지나면 Redis가 스스로 key를 지운다는 것을 검증한다 - 애플리케이션
    // 로직이 아니라 Redis 서버 자체의 만료 동작이라 결정적 Clock 주입으로 대체할 수 없다.
    // 1초 TTL + 짧은 sleep으로 최소화한다(긴 sleep 사용 안 함).
    @Test
    void 만료된_jti는_더_이상_조회되지_않는다() throws InterruptedException {
        refreshTokenStore.save(testJti, 1L, Instant.now().plusSeconds(1));

        Thread.sleep(1500);

        assertThat(refreshTokenStore.findUserId(testJti)).isEmpty();
    }
}
