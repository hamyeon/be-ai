package com.vintic.backend.recommendation.service;

import com.vintic.backend.recommendation.domain.ActivityType;
import com.vintic.backend.recommendation.domain.UserActivityLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ActivityLogServiceTest {

    @Mock
    private UserActivityLogWriter writer;

    private ActivityLogService newService() {
        return new ActivityLogService(writer);
    }

    private UserActivityLog captured() {
        ArgumentCaptor<UserActivityLog> captor = ArgumentCaptor.forClass(UserActivityLog.class);
        verify(writer).write(captor.capture());
        return captor.getValue();
    }

    @Test
    void 조회_행동을_기록한다() {
        newService().recordView(1L, 10L, 100L);

        assertThat(captured().getActivityType()).isEqualTo(ActivityType.VIEW);
        assertThat(captured().getUserId()).isEqualTo(1L);
    }

    @Test
    void 입찰_행동을_기록한다() {
        newService().recordBid(1L, 10L, null);

        assertThat(captured().getActivityType()).isEqualTo(ActivityType.BID);
    }

    @Test
    void 찜_행동을_기록한다() {
        newService().recordLike(1L, 10L, null);

        assertThat(captured().getActivityType()).isEqualTo(ActivityType.LIKE);
        assertThat(captured().getAuctionId()).isEqualTo(10L);
    }

    @Test
    void 찜을_다시_눌러도_기록이_쌓이지_않는다() {
        // 찜 API는 멱등이라 이미 찜한 상품을 다시 눌러도 성공한다. 그때마다 로그를 쌓으면
        // 같은 상품이 가중치를 계속 얻는다.
        newService().recordLike(1L, 10L, null);

        InOrder order = inOrder(writer);
        order.verify(writer).deleteLike(1L, 10L);
        order.verify(writer).write(any());
    }

    @Test
    void 찜을_해제하면_기록을_지운다() {
        // 찜은 이벤트가 아니라 상태다. 남겨두면 이미 관심을 거둔 상품 쪽으로 취향이 기운다.
        newService().removeLike(1L, 10L);

        verify(writer).deleteLike(1L, 10L);
        verify(writer, never()).write(any());
    }

    @Test
    void 비로그인_요청은_기록하지_않는다() {
        ActivityLogService service = newService();
        service.recordView(null, 10L, 100L);
        service.recordLike(null, 10L, null);
        service.removeLike(null, 10L);

        verify(writer, never()).write(any());
        verify(writer, never()).deleteLike(anyLong(), anyLong());
    }

    @Test
    void 기록에_실패해도_예외를_던지지_않는다() {
        // 로그를 못 남겼다고 경매 조회가 실패하면 안 된다.
        doThrow(new RuntimeException("DB 연결 실패")).when(writer).write(any());

        assertThatCode(() -> newService().recordView(1L, 10L, 100L))
                .doesNotThrowAnyException();
    }

    @Test
    void 트랜잭션_커밋_실패도_막는다() {
        // 쓰기를 별도 빈으로 분리한 이유. 프록시 경계가 write()에 있어야 커밋 시점의
        // 예외까지 이 자리에서 잡힌다. (docs/troubleshooting.md 2번)
        doThrow(new org.springframework.transaction.TransactionSystemException("커밋 실패"))
                .when(writer).write(any());

        assertThatCode(() -> newService().recordView(1L, 10L, 100L))
                .doesNotThrowAnyException();
    }

    @Test
    void 찜_해제_삭제가_실패해도_예외를_던지지_않는다() {
        doThrow(new RuntimeException("DB 연결 실패")).when(writer).deleteLike(anyLong(), anyLong());

        assertThatCode(() -> newService().removeLike(1L, 10L))
                .doesNotThrowAnyException();
    }
}
