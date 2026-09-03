package com.vintic.backend.notification.domain;

import com.vintic.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTest {

    private User recipient() {
        User user = User.register("recipient@vintic.local", "recipient", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        return user;
    }

    @Test
    void 생성_직후에는_unread_상태다() {
        LocalDateTime now = LocalDateTime.now();
        Notification notification = Notification.create(
                recipient(), NotificationType.AUCTION_WON, 1L, 55L, "낙찰되었습니다", "결제를 진행해주세요.",
                "AUCTION_WON:55", now
        );

        assertThat(notification.isUnread()).isTrue();
        assertThat(notification.getReadAt()).isNull();
    }

    @Test
    void markRead를_호출하면_readAt이_설정된다() {
        LocalDateTime now = LocalDateTime.now();
        Notification notification = Notification.create(
                recipient(), NotificationType.AUCTION_WON, 1L, 55L, "낙찰되었습니다", "결제를 진행해주세요.",
                "AUCTION_WON:55", now
        );

        LocalDateTime readAt = now.plusMinutes(5);
        notification.markRead(readAt);

        assertThat(notification.isUnread()).isFalse();
        assertThat(notification.getReadAt()).isEqualTo(readAt);
    }

    @Test
    void 이미_읽은_알림에_markRead를_다시_호출해도_최초_readAt이_유지된다() {
        LocalDateTime now = LocalDateTime.now();
        Notification notification = Notification.create(
                recipient(), NotificationType.AUCTION_WON, 1L, 55L, "낙찰되었습니다", "결제를 진행해주세요.",
                "AUCTION_WON:55", now
        );
        LocalDateTime firstReadAt = now.plusMinutes(5);
        notification.markRead(firstReadAt);

        notification.markRead(firstReadAt.plusHours(1));

        assertThat(notification.getReadAt()).isEqualTo(firstReadAt);
    }

    @Test
    void 필수값이_없으면_생성에_실패한다() {
        LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() -> Notification.create(
                null, NotificationType.AUCTION_WON, 1L, 55L, "제목", "본문", "AUCTION_WON:55", now
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Notification.create(
                recipient(), NotificationType.AUCTION_WON, 1L, 55L, "", "본문", "AUCTION_WON:55", now
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Notification.create(
                recipient(), NotificationType.AUCTION_WON, 1L, 55L, "제목", "본문", "", now
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
