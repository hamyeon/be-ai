package com.vintic.backend.notification.service;

import com.vintic.backend.common.exception.NotificationNotFoundException;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.notification.domain.Notification;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.dto.NotificationReadResponse;
import com.vintic.backend.notification.repository.NotificationRepository;
import com.vintic.backend.support.TestClockConfig;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({NotificationCommandService.class, TestClockConfig.class})
class NotificationCommandServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(TestClockConfig.FIXED_INSTANT, ClockConfig.APP_ZONE);

    @Autowired
    private NotificationCommandService notificationCommandService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EntityManager entityManager;

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    private Notification persistNotification(User recipient, String eventKey) {
        Notification notification = Notification.create(
                recipient, NotificationType.AUCTION_WON, 1L, 1L, "제목", "본문", eventKey, LocalDateTime.now()
        );
        return notificationRepository.save(notification);
    }

    @Test
    void 본인_알림을_읽음_처리하면_readAt이_주입된_Clock_기준으로_설정된다() {
        User recipient = persistUser("recipient@vintic.local");
        Notification notification = persistNotification(recipient, "AUCTION_WON:1");
        entityManager.flush();
        entityManager.clear();

        NotificationReadResponse response = notificationCommandService.markRead(notification.getId(), recipient.getId());

        assertThat(response.readAt().toLocalDateTime()).isEqualTo(FIXED_NOW);
        Notification reloaded = notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(reloaded.isUnread()).isFalse();
    }

    @Test
    void 이미_읽은_알림을_다시_읽음_처리해도_최초_readAt이_유지된다() {
        User recipient = persistUser("recipient2@vintic.local");
        Notification notification = persistNotification(recipient, "AUCTION_WON:2");
        entityManager.flush();
        entityManager.clear();

        NotificationReadResponse first = notificationCommandService.markRead(notification.getId(), recipient.getId());
        NotificationReadResponse second = notificationCommandService.markRead(notification.getId(), recipient.getId());

        assertThat(second.readAt()).isEqualTo(first.readAt());
    }

    @Test
    void 존재하지_않는_알림_읽음_처리는_예외가_발생한다() {
        User recipient = persistUser("recipient3@vintic.local");

        assertThatThrownBy(() -> notificationCommandService.markRead(9999L, recipient.getId()))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    void 타인의_알림_읽음_처리는_존재하지_않는_알림과_동일하게_예외가_발생한다() {
        User recipient = persistUser("recipient4@vintic.local");
        User stranger = persistUser("stranger@vintic.local");
        Notification notification = persistNotification(recipient, "AUCTION_WON:3");
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> notificationCommandService.markRead(notification.getId(), stranger.getId()))
                .isInstanceOf(NotificationNotFoundException.class);
    }
}
