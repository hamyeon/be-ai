package com.vintic.backend.analyze.job.worker;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

// Day4 3단계 필수 시나리오 2(maxReceiveCount 이전 crash) 전용 최소 child-process 지원 클래스.
// Spring/S3/SQS를 전혀 부팅하지 않는다 - 이 시나리오가 검증해야 하는 순간은 딱 하나,
// "DB claim이 실제로 Commit된 뒤 결과 저장 전에 프로세스가 죽는다"이고 그 순간은 SQL 한 문장의
// 원자적 UPDATE(ProductAnalysisJobRepository.claimForProcessing과 동일한 조건부 UPDATE)로만
// 재현하면 충분하다. 부모 테스트가 이미 SQS receiveMessage로 visibility 타이머를 실제로 시작시킨
// 뒤 이 프로세스를 띄우고, DB claim이 실제로 반영됐음을 stdout으로 확인한 다음
// Process.destroyForcibly()로 강제 종료한다 - 이 프로세스는 그 시점까지 정말 살아있는 별도
// OS 프로세스이므로 "실제 kill" 요구사항을 충족한다.
//
// claimForProcessing의 SQL을 그대로 복제해서 쓴다(프로덕션 코드를 이 클래스가 재사용할 방법이
// 없다 - Spring Data JPA 리포지토리는 컨테이너 없이 호출할 수 없다). 두 곳의 SQL이 벌어지지
// 않도록 ProductAnalysisJobRepository.claimForProcessing의 쿼리를 바꿀 때는 이 클래스도 함께
// 확인해야 한다.
public final class ClaimAndCrashMain {

    private static final String CLAIM_SQL = """
            UPDATE product_analysis_jobs
            SET status = 'PROCESSING',
                processing_started_at = UTC_TIMESTAMP(6),
                worker_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND (
                   status IN ('PENDING', 'QUEUED', 'PUBLISH_FAILED')
                   OR (
                       status = 'PROCESSING'
                       AND (
                            processing_started_at IS NULL
                            OR TIMESTAMPDIFF(MICROSECOND, processing_started_at, UTC_TIMESTAMP(6)) > ?
                       )
                   )
              )
            """;

    private ClaimAndCrashMain() {
    }

    // args: jdbcUrl jdbcUser jdbcPassword analysisId workerId staleAfterMicros
    public static void main(String[] args) throws Exception {
        String jdbcUrl = args[0];
        String jdbcUser = args[1];
        String jdbcPassword = args[2];
        long analysisId = Long.parseLong(args[3]);
        String workerId = args[4];
        long staleAfterMicros = Long.parseLong(args[5]);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
             PreparedStatement statement = connection.prepareStatement(CLAIM_SQL)) {
            statement.setString(1, workerId);
            statement.setLong(2, analysisId);
            statement.setLong(3, staleAfterMicros);
            int affected = statement.executeUpdate();
            // 부모 프로세스가 이 줄을 읽고 claim이 실제로 Commit됐는지 확인한다(autoCommit
            // 기본값 true이므로 executeUpdate 반환 시점에 이미 Commit돼 있다).
            System.out.println("CLAIMED:" + affected);
            System.out.flush();
        }

        // 결과 저장/완료 통보를 절대 하지 않고 그대로 멈춰 있는다 - 부모가 강제 종료할 때까지
        // "죽은 채로 멈춘 Worker"를 흉내낸다.
        Thread.sleep(Long.MAX_VALUE);
    }
}
