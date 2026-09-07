package com.vintic.backend.analyze.job;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ProductAnalysisJobService/AnalysisJobQueryService의 반환 상태가 HTTP 상태 코드로 어떻게
// 매핑되는지에 집중한다 - 실제 Publish/DB 동작은 ProductAnalysisJobServiceMySqlIT/
// ProductAnalysisJobServiceTest가 이미 검증한다.
@WebMvcTest(AnalysisJobController.class)
class AnalysisJobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductAnalysisJobService jobService;

    @MockitoBean
    private AnalysisJobQueryService jobQueryService;

    private ProductAnalysisJob jobWith(Long id, AnalysisJobStatus status) {
        ProductAnalysisJob job = ProductAnalysisJob.create(1L, "uploads/a.jpg", "key");
        ReflectionTestUtils.setField(job, "id", id);
        ReflectionTestUtils.setField(job, "status", status);
        return job;
    }

    @Test
    void Idempotency_Key_헤더가_없으면_400이다() throws Exception {
        mockMvc.perform(post("/api/analyses")
                        .requestAttr("currentUserId", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"uploads/a.jpg\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 신규_요청은_202를_반환한다() throws Exception {
        when(jobService.submit(anyLong(), anyString(), anyString()))
                .thenReturn(jobWith(1L, AnalysisJobStatus.QUEUED));

        mockMvc.perform(post("/api/analyses")
                        .requestAttr("currentUserId", 1L)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"uploads/a.jpg\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.analysisId").value(1))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    void COMPLETED면_200을_반환한다() throws Exception {
        when(jobService.submit(anyLong(), anyString(), anyString()))
                .thenReturn(jobWith(1L, AnalysisJobStatus.COMPLETED));

        mockMvc.perform(post("/api/analyses")
                        .requestAttr("currentUserId", 1L)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"uploads/a.jpg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    // FAILED는 PUBLISH_FAILED(발행 자체가 안 됨)와 달리 큐에 들어간 뒤 처리가 끝난 상태라
    // 503이 아니라 200으로 응답한다(임시 방어값, Day3~4에서 실패 상세 계약과 함께 재확인).
    @Test
    void FAILED면_200을_반환한다() throws Exception {
        when(jobService.submit(anyLong(), anyString(), anyString()))
                .thenReturn(jobWith(1L, AnalysisJobStatus.FAILED));

        mockMvc.perform(post("/api/analyses")
                        .requestAttr("currentUserId", 1L)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"uploads/a.jpg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value(1))
                .andExpect(jsonPath("$.data.status").value("FAILED"));
    }

    @Test
    void publish_실패면_503이면서_analysisId를_포함한다() throws Exception {
        when(jobService.submit(anyLong(), anyString(), anyString()))
                .thenReturn(jobWith(1L, AnalysisJobStatus.PUBLISH_FAILED));

        mockMvc.perform(post("/api/analyses")
                        .requestAttr("currentUserId", 1L)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"uploads/a.jpg\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.analysisId").value(1))
                .andExpect(jsonPath("$.data.status").value("PUBLISH_FAILED"));
    }

    // Worker가 markQueued/markPublishFailed보다 먼저 PROCESSING/COMPLETED로 바꿔서 서비스가 그
    // 실제 상태를 반환하는 경우 - Controller는 자기가 기대한 상태가 아니라 서비스가 반환한 실제
    // 상태를 그대로 상태 코드에 반영해야 한다.
    @Test
    void Worker가_먼저_PROCESSING으로_바꾼_경우_202를_반환한다() throws Exception {
        when(jobService.submit(anyLong(), anyString(), anyString()))
                .thenReturn(jobWith(1L, AnalysisJobStatus.PROCESSING));

        mockMvc.perform(post("/api/analyses")
                        .requestAttr("currentUserId", 1L)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"uploads/a.jpg\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }

    @Test
    void 상태_조회에_성공한다() throws Exception {
        when(jobQueryService.getOwnedJob(1L, 1L)).thenReturn(jobWith(1L, AnalysisJobStatus.QUEUED));

        mockMvc.perform(get("/api/analyses/{analysisId}", 1L).requestAttr("currentUserId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value(1))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    void 다른_사용자의_job_조회는_403이다() throws Exception {
        when(jobQueryService.getOwnedJob(eq(1L), eq(2L)))
                .thenThrow(new ProductAnalysisJobAccessDeniedException("접근 권한이 없습니다."));

        mockMvc.perform(get("/api/analyses/{analysisId}", 1L).requestAttr("currentUserId", 2L))
                .andExpect(status().isForbidden());
    }

    @Test
    void 존재하지_않는_job_조회는_404이다() throws Exception {
        when(jobQueryService.getOwnedJob(eq(999L), any()))
                .thenThrow(new ProductAnalysisJobNotFoundException("존재하지 않습니다."));

        mockMvc.perform(get("/api/analyses/{analysisId}", 999L).requestAttr("currentUserId", 1L))
                .andExpect(status().isNotFound());
    }
}
