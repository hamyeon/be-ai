package com.vintic.backend.common.auth.mock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

// 요청 헤더의 X-User-Id를 검증해서 currentUserId request attribute로 넘겨준다.
// 컨트롤러는 헤더를 직접 읽지 않고 @RequestAttribute("currentUserId")로 받는다.
public class MockAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER_NAME = "X-User-Id";
    public static final String CURRENT_USER_ID_ATTRIBUTE = "currentUserId";

    private final MockUserRegistry mockUserRegistry;

    public MockAuthInterceptor(MockUserRegistry mockUserRegistry) {
        this.mockUserRegistry = mockUserRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HEADER_NAME);
        if (header == null) {
            throw new MockAuthException(HEADER_NAME + " 헤더가 없습니다.");
        }

        Long userId;
        try {
            userId = Long.valueOf(header);
        } catch (NumberFormatException e) {
            throw new MockAuthException(HEADER_NAME + " 헤더 형식이 올바르지 않습니다: " + header);
        }

        if (!mockUserRegistry.exists(userId)) {
            throw new MockAuthException("존재하지 않는 사용자입니다: " + userId);
        }

        request.setAttribute(CURRENT_USER_ID_ATTRIBUTE, userId);
        return true;
    }
}
