package com.vintic.backend.user;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// MockUserRegistry(local profile mock auth)가 신뢰하는 더미 유저 ID(1,2,3)에 대응하는 실제 users row를
// 보장한다. IDENTITY 채번에 의존하지 않도록 id를 직접 지정해 삽입하고, 이미 있으면 건너뛴다(멱등).
// MockUserRegistry를 실제 인증/UserRepository 기반으로 바꾸는 작업은 이번 범위가 아니다.
@Component
@Profile("local")
public class LocalUserSeeder implements ApplicationRunner {

    private static final long[] DUMMY_USER_IDS = {1L, 2L, 3L};

    private final JdbcTemplate jdbcTemplate;

    public LocalUserSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (long id : DUMMY_USER_IDS) {
            jdbcTemplate.update(
                    "INSERT IGNORE INTO users (id, email, nickname, noshow_count, created_at) VALUES (?, ?, ?, 0, NOW())",
                    id, "mock-user-" + id + "@vintic.local", "mock-user-" + id
            );
        }
    }
}
