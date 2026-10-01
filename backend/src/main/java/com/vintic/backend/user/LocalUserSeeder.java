package com.vintic.backend.user;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// mock 인증(X-User-Id)으로 쓰는 더미 유저 ID(1,2,3)에 대응하는 실제 users row를 보장한다.
// MockUserRegistry가 users 테이블을 조회하므로, 이 row가 없으면 로컬에서 상품 등록/입찰이 401로 막힌다.
// IDENTITY 채번에 의존하지 않도록 id를 직접 지정해 삽입하고, 이미 있으면 건너뛴다(멱등).
// 공유 DB를 쓰는 dev에는 일부러 적용하지 않는다 - 팀 공용 데이터에 더미 row를 넣지 않기 위해서다.
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
