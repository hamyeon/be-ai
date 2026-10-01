package com.vintic.backend.notification.repository;

import com.vintic.backend.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // GET /api/notifications 목록 조회 - stable ordering(createdAt DESC, id DESC).
    Page<Notification> findByRecipient_IdOrderByCreatedAtDescIdDesc(Long recipientId, Pageable pageable);

    // GET /api/notifications/unread-count.
    long countByRecipient_IdAndReadAtIsNull(Long recipientId);

    // PATCH /api/notifications/{id}/read 전용 - 존재하지 않는 알림과 타인의 알림을 이 조회
    // 하나로 구분 없이 처리한다(둘 다 404/40405, #75 사용자 확정).
    Optional<Notification> findByIdAndRecipient_Id(Long id, Long recipientId);
}
