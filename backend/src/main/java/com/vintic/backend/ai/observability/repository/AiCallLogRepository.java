package com.vintic.backend.ai.observability.repository;

import com.vintic.backend.ai.observability.domain.AiCallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;

// 지금 이 기록을 읽는 코드는 없다. 조회 화면이 생기기 전까지는 DB에 직접 질의해서 본다.
//
// 미리 조회 메서드를 만들어두지 않는 이유는, 부르는 곳이 없는 쿼리는 실제로 필요한 형태와
// 어긋나 있어도 아무도 모르기 때문이다. 대신 인덱스는 걸어뒀다(AiCallLog 참고) -
// analysis_id로 묶어 보거나 기간별로 집계하는 질의는 인덱스를 그대로 탄다.
public interface AiCallLogRepository extends JpaRepository<AiCallLog, Long> {

    // 보관 기간이 지난 기록 정리. 응답 본문을 통째로 담고 있어 무한정 쌓아둘 수 없다.
    @Modifying
    @Query("delete from AiCallLog l where l.createdAt < :threshold")
    int deleteOlderThan(LocalDateTime threshold);
}
