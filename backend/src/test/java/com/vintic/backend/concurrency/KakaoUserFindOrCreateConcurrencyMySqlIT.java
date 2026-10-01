package com.vintic.backend.concurrency;

import com.vintic.backend.auth.KakaoUserFindOrCreateService;
import com.vintic.backend.auth.kakao.KakaoUserInfo;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

// #75-4C: uk_users_kakao_user_id UNIQUE가 실제 MySQL에서 최종 방어선으로 동작하는지, 그리고
// KakaoLoginService.resolveUser()와 동일한 catch/retry 패턴이 동시 최초 로그인에서도 User를
// 정확히 1건만 남기는지 검증한다. Kakao HTTP 호출은 이 지점의 관심사가 아니므로 실제
// KakaoUserInfoClient는 쓰지 않는다 - KakaoUserFindOrCreateService만 직접 동시 호출한다.
// dev profile을 활성화해야 @Profile({"dev","prod"}) 빈이 생성된다 - datasource/jwt.secret은
// 아래에서 Testcontainers/고정값으로 덮어써 실제 dev 인프라와 무관하게 만든다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class KakaoUserFindOrCreateConcurrencyMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("jwt.secret", () -> "kakao-concurrency-it-test-secret-32-bytes-minimum!!");
    }

    @Autowired
    private KakaoUserFindOrCreateService kakaoUserFindOrCreateService;

    @Autowired
    private UserRepository userRepository;

    // KakaoLoginService.resolveUser()와 동일한 catch/retry - 이 IT의 관심사는 find-or-create의
    // 동시성뿐이라 KakaoLoginService(JWT 발급 포함) 전체를 거치지 않는다.
    private User resolveUser(KakaoUserInfo info) {
        try {
            return kakaoUserFindOrCreateService.findOrCreate(info);
        } catch (DataIntegrityViolationException e) {
            return kakaoUserFindOrCreateService.getByKakaoUserId(info.kakaoUserId());
        }
    }

    @Test
    void 동일_kakaoUserId로_동시에_최초_로그인해도_User가_1건만_생성된다() throws Exception {
        Long kakaoUserId = 555_000_001L;
        KakaoUserInfo info = new KakaoUserInfo(kakaoUserId, "race@example.com", "레이스", null);
        int threadCount = 8;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Long>> futures = IntStream.range(0, threadCount)
                .mapToObj(i -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return resolveUser(info).getId();
                }))
                .collect(Collectors.toList());

        ready.await();
        start.countDown();

        Set<Long> resolvedIds = new HashSet<>();
        for (Future<Long> future : futures) {
            resolvedIds.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        assertThat(resolvedIds).hasSize(1);
        assertThat(userRepository.count()).isEqualTo(1);
        User persisted = userRepository.findByKakaoUserId(kakaoUserId).orElseThrow();
        assertThat(persisted.getId()).isEqualTo(resolvedIds.iterator().next());
    }
}
