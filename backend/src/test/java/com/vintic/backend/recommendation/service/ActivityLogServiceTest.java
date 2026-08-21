package com.vintic.backend.recommendation.service;

import com.vintic.backend.recommendation.domain.ActivityType;
import com.vintic.backend.recommendation.domain.UserActivityLog;
import com.vintic.backend.recommendation.repository.UserActivityLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityLogServiceTest {

    @Mock
    private UserActivityLogRepository activityLogRepository;

    private ActivityLogService newService() {
        return new ActivityLogService(activityLogRepository);
    }

    @Test
    void 조회_행동을_기록한다() {
        newService().recordView(1L, 10L, 100L);

        ArgumentCaptor<UserActivityLog> captor = ArgumentCaptor.forClass(UserActivityLog.class);
        verify(activityLogRepository).save(captor.capture());
        assertThat(captor.getValue().getActivityType()).isEqualTo(ActivityType.VIEW);
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
    }

    @Test
    void 비로그인_요청은_기록하지_않는다() {
        // 누구의 취향인지 모르는 로그는 개인화에 쓸 수 없다
        newService().recordView(null, 10L, 100L);

        verify(activityLogRepository, never()).save(any());
    }

    @Test
    void 로그_저장이_실패해도_예외를_던지지_않는다() {
        // 이 로그는 추천 품질용 부가 데이터다. 로그를 못 남겼다고 경매 조회가 실패하면 안 된다.
        when(activityLogRepository.save(any())).thenThrow(new RuntimeException("DB 연결 실패"));

        assertThatCode(() -> newService().recordView(1L, 10L, 100L)).doesNotThrowAnyException();
    }

    @Test
    void 잘못된_로그도_요청을_실패시키지_않는다() {
        // 대상이 없는 로그는 엔티티 생성 단계에서 거부되는데, 그 예외도 삼켜야 한다
        assertThatCode(() -> newService().recordView(1L, null, null)).doesNotThrowAnyException();

        verify(activityLogRepository, never()).save(any());
    }

    @Test
    void 입찰_행동을_기록한다() {
        newService().recordBid(1L, 10L, null);

        ArgumentCaptor<UserActivityLog> captor = ArgumentCaptor.forClass(UserActivityLog.class);
        verify(activityLogRepository).save(captor.capture());
        assertThat(captor.getValue().getActivityType()).isEqualTo(ActivityType.BID);
        assertThat(captor.getValue().getAuctionId()).isEqualTo(10L);
    }
}
