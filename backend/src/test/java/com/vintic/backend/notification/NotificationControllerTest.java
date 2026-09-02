package com.vintic.backend.notification;

import com.vintic.backend.common.exception.NotificationNotFoundException;
import com.vintic.backend.notification.domain.NotificationType;
import com.vintic.backend.notification.dto.NotificationListResponse;
import com.vintic.backend.notification.dto.NotificationReadResponse;
import com.vintic.backend.notification.dto.NotificationResponse;
import com.vintic.backend.notification.dto.UnreadCountResponse;
import com.vintic.backend.notification.service.NotificationCommandService;
import com.vintic.backend.notification.service.NotificationQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationQueryService notificationQueryService;

    @MockitoBean
    private NotificationCommandService notificationCommandService;

    @Test
    void 목록조회_성공시_200과_페이지_정보를_반환한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        NotificationResponse item = new NotificationResponse(
                1L, NotificationType.AUCTION_WON, 10L, 55L, "낙찰되었습니다", "결제를 진행해주세요.", null, now
        );
        NotificationListResponse response = new NotificationListResponse(List.of(item), 0, 20, false);
        when(notificationQueryService.getNotifications(2L, 0, 20)).thenReturn(response);

        mockMvc.perform(get("/api/notifications").requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.notifications[0].id").value(1))
                .andExpect(jsonPath("$.data.notifications[0].type").value("AUCTION_WON"))
                .andExpect(jsonPath("$.data.notifications[0].auctionId").value(10))
                .andExpect(jsonPath("$.data.notifications[0].resourceId").value(55))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 읽음처리_성공시_200과_readAt을_반환한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        when(notificationCommandService.markRead(1L, 2L)).thenReturn(new NotificationReadResponse(1L, now));

        mockMvc.perform(patch("/api/notifications/1/read").requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notificationId").value(1));
    }

    @Test
    void 존재하지_않거나_타인의_알림_읽음처리는_404와_40405를_반환한다() throws Exception {
        when(notificationCommandService.markRead(999L, 2L))
                .thenThrow(new NotificationNotFoundException("존재하지 않는 알림입니다. notificationId: 999"));

        mockMvc.perform(patch("/api/notifications/999/read").requestAttr("currentUserId", 2L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40405));
    }

    @Test
    void unread_count_조회_성공시_200과_카운트를_반환한다() throws Exception {
        when(notificationQueryService.getUnreadCount(2L)).thenReturn(new UnreadCountResponse(3));

        mockMvc.perform(get("/api/notifications/unread-count").requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(3));
    }
}
