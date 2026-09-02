package com.vintic.backend.notification.dto;

import com.vintic.backend.notification.domain.Notification;
import org.springframework.data.domain.Page;

import java.util.List;

// BidHistoryResponse와 동일한 pagination shape 관례(page/size/hasNext).
public record NotificationListResponse(
        List<NotificationResponse> notifications,
        int page,
        int size,
        boolean hasNext
) {
    public static NotificationListResponse from(Page<Notification> notificationPage) {
        List<NotificationResponse> notifications = notificationPage.getContent().stream()
                .map(NotificationResponse::from)
                .toList();
        return new NotificationListResponse(
                notifications, notificationPage.getNumber(), notificationPage.getSize(), notificationPage.hasNext()
        );
    }
}
