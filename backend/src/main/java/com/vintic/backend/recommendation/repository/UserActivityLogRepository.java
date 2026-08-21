package com.vintic.backend.recommendation.repository;

import com.vintic.backend.recommendation.domain.UserActivityLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserActivityLogRepository extends JpaRepository<UserActivityLog, Long> {

    // 유저 벡터를 만들 때 쓴다. 오래된 행동일수록 현재 취향과 멀어지므로 최신순으로 잘라 쓴다.
    List<UserActivityLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // Cold Start 판정용. 행동이 몇 건 안 되면 개인화 대신 Fallback으로 보낸다.
    long countByUserId(Long userId);

    // 인기도 Fallback용. 상품별 행동 수를 세어 많이 본 순으로 정렬한다.
    @Query("""
            select l.productId as productId, count(l) as activityCount
            from UserActivityLog l
            where l.productId is not null
            group by l.productId
            order by count(l) desc
            """)
    List<ProductActivityCount> findProductActivityCounts(Pageable pageable);

    interface ProductActivityCount {
        Long getProductId();
        long getActivityCount();
    }
}
