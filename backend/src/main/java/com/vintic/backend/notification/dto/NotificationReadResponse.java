package com.vintic.backend.notification.dto;

import java.time.OffsetDateTime;

public record NotificationReadResponse(Long notificationId, OffsetDateTime readAt) {
}
