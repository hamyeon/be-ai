package com.vintic.backend.user.repository;

import com.vintic.backend.user.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // #57-2: OrderExpirationService가 noShowCount/bidRestrictedUntil을 갱신하기 전에 잠근다.
    // Auction -> Order 락을 이미 획득한 뒤에만 호출되므로(OrderExpirationService 클래스 주석
    // 참고) lock ordering은 항상 Auction -> Order -> User로 고정된다 - 서로 다른 auction의
    // 만료 처리가 같은 buyer(User row)를 동시에 잠그려 해도 각자 자신의 Auction/Order를 먼저
    // 확보한 뒤에만 User를 요청하므로 순환 대기가 생기지 않는다. noShowCount++가 read-then-write라
    // non-locking이면 두 트랜잭션이 서로의 증가분을 잃어버릴 수 있어(lost update) locking read를 쓴다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);
}
