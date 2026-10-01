package com.vintic.backend.common.auth.mock;

import com.vintic.backend.user.repository.UserRepository;
import org.springframework.stereotype.Component;

// mock 인증(X-User-Id 헤더)이 가리키는 사용자가 실제로 존재하는지 확인한다.
// 더미 ID 목록(1,2,3)을 하드코딩하던 것을 users 테이블 조회로 바꿨다 - 로컬 DB뿐 아니라 공유 DB를 쓰는
// dev에서도 같은 인증이 동작해야 하는데, 하드코딩된 목록은 공유 DB의 실제 사용자와 맞지 않기 때문이다.
@Component
public class MockUserRegistry {

    private final UserRepository userRepository;

    public MockUserRegistry(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean exists(Long userId) {
        return userRepository.existsById(userId);
    }
}
