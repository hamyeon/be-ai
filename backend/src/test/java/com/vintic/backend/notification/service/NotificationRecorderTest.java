package com.vintic.backend.notification.service;

import com.vintic.backend.notification.domain.Notification;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.repository.NotificationRepository;
import com.vintic.backend.support.TestClockConfig;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// #75. lifecycle 연결(AuctionSettlementService 등에서의 실제 호출)은 다음 단계 - 여기서는
// NotificationRecorder 자체의 동작(businessEventKey 조립, 정적 title/body, UNIQUE 최종 방어선)만 검증한다.
@DataJpaTest
@Import({NotificationRecorder.class, TestClockConfig.class})
class NotificationRecorderTest {

    @Autowired
    private NotificationRecorder notificationRecorder;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EntityManager entityManager;

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    @Test
    void record_호출시_businessEventKey와_정적_문구로_알림을_저장한다() {
        User recipient = persistUser("winner@vintic.local");

        Notification notification = notificationRecorder.record(recipient, NotificationType.AUCTION_WON, 1L, 55L);
        entityManager.flush();

        assertThat(notification.getId()).isNotNull();
        assertThat(notification.getBusinessEventKey()).isEqualTo("AUCTION_WON:55");
        assertThat(notification.getAuctionId()).isEqualTo(1L);
        assertThat(notification.getResourceId()).isEqualTo(55L);
        assertThat(notification.getTitle()).isNotBlank();
        assertThat(notification.getBody()).isNotBlank();
        assertThat(notification.isUnread()).isTrue();
    }

    @Test
    void 같은_businessEventKey로_두_번_저장하면_UNIQUE_위반이_발생한다() {
        User recipient = persistUser("winner2@vintic.local");
        notificationRecorder.record(recipient, NotificationType.AUCTION_WON, 1L, 77L);
        entityManager.flush();

        assertThatThrownBy(() -> {
            notificationRecorder.record(recipient, NotificationType.AUCTION_WON, 1L, 77L);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void type별로_서로_다른_정적_title_body를_사용한다() {
        User recipient = persistUser("recipient3@vintic.local");

        Notification won = notificationRecorder.record(recipient, NotificationType.AUCTION_WON, 1L, 1L);
        Notification backupOffer = notificationRecorder.record(recipient, NotificationType.BACKUP_OFFER_CREATED, 1L, 2L);
        Notification paymentExpired = notificationRecorder.record(recipient, NotificationType.PAYMENT_EXPIRED, 1L, 3L);

        assertThat(won.getTitle()).isNotEqualTo(backupOffer.getTitle());
        assertThat(backupOffer.getTitle()).isNotEqualTo(paymentExpired.getTitle());
    }
}
