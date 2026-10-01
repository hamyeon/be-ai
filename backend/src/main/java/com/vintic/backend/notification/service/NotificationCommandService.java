package com.vintic.backend.notification.service;

import com.vintic.backend.common.exception.NotificationNotFoundException;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.notification.domain.Notification;
import com.vintic.backend.notification.dto.NotificationReadResponse;
import com.vintic.backend.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

// PATCH /api/notifications/{id}/read. findByIdAndRecipient_Id 하나로 존재하지 않는 알림과
// 타인의 알림을 구분 없이 404/40405로 처리한다(#75 사용자 확정 - 별도 403 없음).
@Service
public class NotificationCommandService {

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    public NotificationCommandService(NotificationRepository notificationRepository, Clock clock) {
        this.notificationRepository = notificationRepository;
        this.clock = clock;
    }

    @Transactional
    public NotificationReadResponse markRead(Long notificationId, Long userId) {
        Notification notification = notificationRepository.findByIdAndRecipient_Id(notificationId, userId)
                .orElseThrow(() -> new NotificationNotFoundException(
                        "존재하지 않는 알림입니다. notificationId: " + notificationId
                ));

        // 이미 읽은 알림이면 markRead()가 내부적으로 no-op이라 재호출해도 readAt이 최초 값
        // 그대로 유지된다(idempotent).
        notification.markRead(LocalDateTime.now(clock));

        return new NotificationReadResponse(notification.getId(), TimePolicy.toApiTime(notification.getReadAt()));
    }
}
