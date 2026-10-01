package com.vintic.backend.notification.dto;

import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.notification.domain.Notification;
import com.vintic.backend.notification.domain.NotificationType;

import java.time.OffsetDateTime;

public record NotificationResponse(
        Long id,
        NotificationType type,
        Long auctionId,
        Long resourceId,
        String title,
        String body,
        OffsetDateTime readAt,
        OffsetDateTime createdAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getAuctionId(),
                notification.getResourceId(),
                notification.getTitle(),
                notification.getBody(),
                TimePolicy.toApiTime(notification.getReadAt()),
                TimePolicy.toApiTime(notification.getCreatedAt())
        );
    }
}
