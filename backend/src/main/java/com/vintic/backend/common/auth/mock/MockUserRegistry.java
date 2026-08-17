package com.vintic.backend.common.auth.mock;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;

// local profile 전용 더미 유저 목록. 실제 User 엔티티가 생기면 UserRepository.existsById()로 교체한다.
@Component
@Profile("local")
public class MockUserRegistry {

    private static final Set<Long> DUMMY_USER_IDS = Set.of(1L, 2L, 3L);

    public boolean exists(Long userId) {
        return DUMMY_USER_IDS.contains(userId);
    }
}
