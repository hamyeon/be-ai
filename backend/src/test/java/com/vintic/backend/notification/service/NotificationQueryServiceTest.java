package com.vintic.backend.notification.service;

import com.vintic.backend.notification.domain.Notification;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.dto.NotificationListResponse;
import com.vintic.backend.notification.dto.UnreadCountResponse;
import com.vintic.backend.notification.repository.NotificationRepository;
import com.vintic.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(NotificationQueryService.class)
class NotificationQueryServiceTest {

    @Autowired
    private NotificationQueryService notificationQueryService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EntityManager entityManager;

    private User persistUser(String email) {
        User user = User.register(email, email, null);
        entityManager.persist(user);
        return user;
    }

    private Notification persistNotification(User recipient, String eventKey, LocalDateTime createdAt) {
        Notification notification = Notification.create(
                recipient, NotificationType.AUCTION_WON, 1L, 1L, "제목", "본문", eventKey, createdAt
        );
        return notificationRepository.save(notification);
    }

    @Test
    void 목록조회는_createdAt_내림차순으로_정렬된다() {
        User recipient = persistUser("recipient@vintic.local");
        LocalDateTime now = LocalDateTime.now();
        persistNotification(recipient, "AUCTION_WON:1", now.minusMinutes(2));
        Notification latest = persistNotification(recipient, "AUCTION_WON:2", now);
        entityManager.flush();
        entityManager.clear();

        NotificationListResponse response = notificationQueryService.getNotifications(recipient.getId(), 0, 20);

        assertThat(response.notifications()).hasSize(2);
        assertThat(response.notifications().get(0).id()).isEqualTo(latest.getId());
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void 다른_사용자의_알림은_보이지_않는다() {
        User recipient = persistUser("recipient2@vintic.local");
        User stranger = persistUser("stranger@vintic.local");
        persistNotification(recipient, "AUCTION_WON:3", LocalDateTime.now());
        entityManager.flush();
        entityManager.clear();

        NotificationListResponse response = notificationQueryService.getNotifications(stranger.getId(), 0, 20);

        assertThat(response.notifications()).isEmpty();
    }

    @Test
    void pagination_size가_적용된다() {
        User recipient = persistUser("recipient3@vintic.local");
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 3; i++) {
            persistNotification(recipient, "AUCTION_WON:" + (10 + i), now.minusMinutes(i));
        }
        entityManager.flush();
        entityManager.clear();

        NotificationListResponse response = notificationQueryService.getNotifications(recipient.getId(), 0, 2);

        assertThat(response.notifications()).hasSize(2);
        assertThat(response.hasNext()).isTrue();
    }

    @Test
    void unread_count는_읽지_않은_알림만_센다() {
        User recipient = persistUser("recipient4@vintic.local");
        Notification unread = persistNotification(recipient, "AUCTION_WON:20", LocalDateTime.now());
        Notification read = persistNotification(recipient, "AUCTION_WON:21", LocalDateTime.now());
        read.markRead(LocalDateTime.now());
        entityManager.flush();
        entityManager.clear();

        UnreadCountResponse response = notificationQueryService.getUnreadCount(recipient.getId());

        assertThat(response.unreadCount()).isEqualTo(1);
    }
}
