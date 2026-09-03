package com.vintic.backend.notification.service;

import com.vintic.backend.notification.domain.Notification;
import com.vintic.backend.notification.dto.NotificationListResponse;
import com.vintic.backend.notification.dto.UnreadCountResponse;
import com.vintic.backend.notification.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// GET /api/notifications, GET /api/notifications/unread-count. 둘 다 side-effect 없음 -
// 저장된 상태를 그대로 읽기만 한다(다른 QueryService와 동일 원칙).
@Service
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;

    public NotificationQueryService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional(readOnly = true)
    public NotificationListResponse getNotifications(Long userId, int page, int size) {
        Page<Notification> notificationPage = notificationRepository
                .findByRecipient_IdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(page, size));
        return NotificationListResponse.from(notificationPage);
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse getUnreadCount(Long userId) {
        long unreadCount = notificationRepository.countByRecipient_IdAndReadAtIsNull(userId);
        return new UnreadCountResponse(unreadCount);
    }
}
