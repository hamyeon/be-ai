package com.vintic.backend.analyze.job;

import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.analyze.job.queue.QueuePublisher;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// Day2 3단계 검증: POST /api/analyses, GET /api/analyses/{analysisId}가 실제 MySQL(Testcontainers)과
// HTTP 왕복으로 계약대로 동작하는지 확인한다. QueuePublisher는 성공/실패를 결정적으로 재현하기 위해
// mock으로 대체한다(실제 Publish 동작 자체는 ProductAnalysisJobServiceMySqlIT/SqsQueuePublisherLocalStackIT가
// 이미 검증했다). VisionAnalysisService도 mock으로 대체해 이 경로에서 AI 코드가 호출되지 않음을 확인한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class AnalysisJobControllerMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private QueuePublisher queuePublisher;

    @MockitoBean
    private VisionAnalysisService visionAnalysisService;

    private Long persistUser() {
        return userRepository.save(User.register(
                "job-user-" + System.nanoTime() + "@vintic.local", "nick", null
        )).getId();
    }

    private HttpEntity<Map<String, Object>> requestEntity(Long userId, String idempotencyKey, String objectKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return new HttpEntity<>(Map.of("objectKey", objectKey), headers);
    }

    private ResponseEntity<ApiResponse<AnalysisJobResponse>> submit(Long userId, String idempotencyKey, String objectKey) {
        return restTemplate.exchange(
                "/api/analyses", HttpMethod.POST,
                requestEntity(userId, idempotencyKey, objectKey),
                new ParameterizedTypeReference<>() {
                }
        );
    }

    @Test
    void Idempotency_Key_헤더가_없으면_400이다() {
        Long userId = persistUser();

        ResponseEntity<ApiResponse<AnalysisJobResponse>> response = submit(userId, null, "uploads/a.jpg");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 신규_요청은_202와_analysisId를_반환한다() {
        Long userId = persistUser();

        ResponseEntity<ApiResponse<AnalysisJobResponse>> response = submit(userId, "key-1", "uploads/a.jpg");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().data().analysisId()).isNotNull();
        assertThat(response.getBody().data().status()).isEqualTo("QUEUED");
        verify(queuePublisher).publish(any());
    }

    @Test
    void 같은_key_진행중_재요청은_같은_analysisId로_202를_반환한다() {
        Long userId = persistUser();

        ResponseEntity<ApiResponse<AnalysisJobResponse>> first = submit(userId, "key-2", "uploads/a.jpg");
        ResponseEntity<ApiResponse<AnalysisJobResponse>> second = submit(userId, "key-2", "uploads/b.jpg");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getBody().data().analysisId()).isEqualTo(first.getBody().data().analysisId());
        verify(queuePublisher, times(1)).publish(any());
    }

    @Test
    void COMPLETED_job은_재요청해도_200을_반환한다() {
        Long userId = persistUser();
        ResponseEntity<ApiResponse<AnalysisJobResponse>> first = submit(userId, "key-3", "uploads/a.jpg");
        Long analysisId = first.getBody().data().analysisId();
        jdbcTemplate.update("update product_analysis_jobs set status = 'COMPLETED' where id = ?", analysisId);

        ResponseEntity<ApiResponse<AnalysisJobResponse>> second = submit(userId, "key-3", "uploads/a.jpg");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().data().status()).isEqualTo("COMPLETED");
    }

    @Test
    void publish_실패는_503이면서_analysisId를_포함한다() {
        Long userId = persistUser();
        doThrow(new RuntimeException("SQS timeout")).when(queuePublisher).publish(any());

        ResponseEntity<ApiResponse<AnalysisJobResponse>> response = submit(userId, "key-4", "uploads/a.jpg");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().data().analysisId()).isNotNull();
        assertThat(response.getBody().data().status()).isEqualTo("PUBLISH_FAILED");
    }

    @Test
    void 상태_조회에_성공한다() {
        Long userId = persistUser();
        Long analysisId = submit(userId, "key-5", "uploads/a.jpg").getBody().data().analysisId();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(userId));
        ResponseEntity<ApiResponse<AnalysisJobResponse>> response = restTemplate.exchange(
                "/api/analyses/" + analysisId, HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().analysisId()).isEqualTo(analysisId);
    }

    @Test
    void 다른_사용자의_job_조회는_차단된다() {
        Long owner = persistUser();
        Long other = persistUser();
        Long analysisId = submit(owner, "key-6", "uploads/a.jpg").getBody().data().analysisId();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(other));
        ResponseEntity<ApiResponse<AnalysisJobResponse>> response = restTemplate.exchange(
                "/api/analyses/" + analysisId, HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {
                }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void 요청_처리_중_Vision_서비스는_호출되지_않는다() {
        Long userId = persistUser();

        submit(userId, "key-7", "uploads/a.jpg");

        verifyNoInteractions(visionAnalysisService);
    }
}
