package com.vintic.backend.user.repository;

import com.vintic.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void 유저를_저장하고_id로_조회할_수_있다() {
        User saved = userRepository.save(User.register("a@vintic.local", "aaa", null));

        Optional<User> found = userRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("a@vintic.local");
        assertThat(found.get().getNoshowCount()).isZero();
        assertThat(found.get().getBidRestrictedUntil()).isNull();
    }

    @Test
    void email이_중복되면_저장에_실패한다() {
        userRepository.saveAndFlush(User.register("dup@vintic.local", "one", null));

        assertThatThrownBy(() ->
                userRepository.saveAndFlush(User.register("dup@vintic.local", "two", null))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }
}
