package com.vintic.backend.notification;

import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.notification.dto.NotificationListResponse;
import com.vintic.backend.notification.dto.NotificationReadResponse;
import com.vintic.backend.notification.dto.UnreadCountResponse;
import com.vintic.backend.notification.service.NotificationCommandService;
import com.vintic.backend.notification.service.NotificationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;
    private final NotificationCommandService notificationCommandService;

    public NotificationController(
            NotificationQueryService notificationQueryService,
            NotificationCommandService notificationCommandService
    ) {
        this.notificationQueryService = notificationQueryService;
        this.notificationCommandService = notificationCommandService;
    }

    @Operation(
            summary = "내 알림 목록 조회",
            description = "최신순(createdAt DESC, id DESC) 페이지네이션."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증이 필요합니다(40101)")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<NotificationListResponse>> getNotifications(
            @RequestAttribute("currentUserId") Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        NotificationListResponse response = notificationQueryService.getNotifications(userId, page, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "알림 읽음 처리",
            description = "본인 알림만 처리 가능하다. 이미 읽은 알림에 재호출해도 최초 readAt이 그대로 유지된다(idempotent)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 성공(또는 이미 읽은 상태의 재확인)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증이 필요합니다(40101)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않거나 본인 소유가 아닌 알림(40405)")
    })
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<NotificationReadResponse>> markRead(
            @PathVariable Long notificationId,
            @RequestAttribute("currentUserId") Long userId
    ) {
        NotificationReadResponse response = notificationCommandService.markRead(notificationId, userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "안읽은 알림 개수 조회",
            description = "상단 벨 unread badge 전용."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증이 필요합니다(40101)")
    })
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> getUnreadCount(
            @RequestAttribute("currentUserId") Long userId
    ) {
        UnreadCountResponse response = notificationQueryService.getUnreadCount(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
