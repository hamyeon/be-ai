package com.vintic.backend.concurrency;

import com.vintic.backend.notification.domain.Notification;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.repository.NotificationRepository;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// #75 마무리: uk_notification_business_event_key UNIQUE 제약 자체가 실제 MySQL에서 최종
// 방어선으로 동작하는지 증명한다 - lifecycle guard(중복 재실행 방지)를 우회한 직접 INSERT
// 시나리오라 NotificationAtomicityMySqlIT(#75, CHECK 제약으로 INSERT 강제 실패 + 롤백 확인)와는
// 관심사가 다르다. 독립 컨테이너를 쓰는 별도 클래스로 둔 이유: 이 클래스는 검증 대상 테이블에
// 아무 스키마 변경(ALTER TABLE)도 하지 않지만, NotificationAtomicityMySqlIT는 notifications
// 테이블에 영구 CHECK 제약(chk_smoke_force_notification_fail CHECK(1=0))을 남긴다 - 같은
// 컨테이너/스키마를 공유하면 실행 순서에 따라 이 테스트의 첫 정상 INSERT부터 그 잔여 제약에
// 걸려 실패해, "UNIQUE 위반"이 아니라 다른 이유로 테스트가 성립하지 않게 된다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class NotificationBusinessEventKeyUniqueMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private Notification notification(User recipient, String businessEventKey) {
        return Notification.create(
                recipient, NotificationType.AUCTION_WON, 1L, 999L, "낙찰되었습니다", "결제를 진행해주세요.",
                businessEventKey, LocalDateTime.now()
        );
    }

    @Test
    void 동일_businessEventKey로_두_번째_INSERT하면_UNIQUE_위반이_발생하고_중복_row가_남지_않는다() {
        User recipient = userRepository.save(User.register("recipient-" + System.nanoTime() + "@vintic.local", "recipient", null));
        String businessEventKey = "AUCTION_WON:999";

        // 1) 첫 INSERT + flush 성공.
        notificationRepository.saveAndFlush(notification(recipient, businessEventKey));
        assertThat(notificationRepository.count()).isEqualTo(1);

        // 2) 같은 businessEventKey를 가진 별도 row로 재시도 - 각 saveAndFlush는 그 자체로 독립된
        //    트랜잭션(Spring Data JPA repository 메서드 경계)이라, 실패한 두 번째 INSERT만 롤백되고
        //    첫 번째 INSERT는 영향받지 않는다.
        assertThatThrownBy(() -> notificationRepository.saveAndFlush(notification(recipient, businessEventKey)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 3) rollback 이후에도 동일 key row가 2건으로 늘지 않고 최초 1건만 남는다.
        assertThat(notificationRepository.count()).isEqualTo(1);
    }
}
