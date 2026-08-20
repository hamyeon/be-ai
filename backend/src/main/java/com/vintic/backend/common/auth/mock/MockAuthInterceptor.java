package com.vintic.backend.common.auth.mock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

// 요청 헤더의 X-User-Id를 검증해서 currentUserId request attribute로 넘겨준다.
// 컨트롤러는 헤더를 직접 읽지 않고 @RequestAttribute("currentUserId")로 받는다.
//
// 검증 대상은 currentUserId를 파라미터로 받는 핸들러뿐이다. 로그인한 사용자가 있어야 하는 API(상품 등록,
// 입찰)와 그렇지 않은 API(시세 계산, 분석 결과 폴링, 경매/입찰 이력 조회)가 섞여 있어서, 앞의 것에만
// 헤더를 요구해야 뒤의 것이 헤더 없이 열려 있을 수 있다.
// 대상 경로를 나열하는 대신 파라미터 선언을 보는 이유는, 목록과 실제 코드가 어긋나는 순간 인증이 조용히
// 빠지거나(누락) 열려 있어야 할 API가 막히기(오탐) 때문이다. 파라미터 선언은 어긋날 수가 없다.
public class MockAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER_NAME = "X-User-Id";
    public static final String CURRENT_USER_ID_ATTRIBUTE = "currentUserId";

    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    private final MockUserRegistry mockUserRegistry;

    public MockAuthInterceptor(MockUserRegistry mockUserRegistry) {
        this.mockUserRegistry = mockUserRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!requiresCurrentUser(handler)) {
            return true;
        }

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

    private boolean requiresCurrentUser(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return false;
        }

        for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
            RequestAttribute annotation = parameter.getParameterAnnotation(RequestAttribute.class);
            if (annotation != null && CURRENT_USER_ID_ATTRIBUTE.equals(attributeName(parameter, annotation))) {
                return true;
            }
        }
        return false;
    }

    // @RequestAttribute는 이름을 생략할 수 있고, 그때는 파라미터 이름이 곧 attribute 이름이 된다.
    // 이름을 생략한 선언을 놓치면 인증이 걸려야 할 API가 그냥 통과하므로 여기까지 본다.
    private String attributeName(MethodParameter parameter, RequestAttribute annotation) {
        if (!annotation.name().isEmpty()) {
            return annotation.name();
        }
        if (!annotation.value().isEmpty()) {
            return annotation.value();
        }
        parameter.initParameterNameDiscovery(PARAMETER_NAME_DISCOVERER);
        return parameter.getParameterName();
    }
}
