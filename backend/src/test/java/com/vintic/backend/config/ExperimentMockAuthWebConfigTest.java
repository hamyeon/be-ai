package com.vintic.backend.config;

import com.vintic.backend.analyze.job.AnalysisJobController;
import com.vintic.backend.analyze.job.AnalysisJobQueryService;
import com.vintic.backend.analyze.job.ProductAnalysisJob;
import com.vintic.backend.analyze.job.ProductAnalysisJobService;
import com.vintic.backend.common.auth.mock.MockUserRegistry;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ExperimentMockAuthWebConfig가 experiment-api + experiment.auth.mock-enabled=true 조합에서
// 실제로 X-User-Id 헤더를 currentUserId로 채우는지, local의 MockAuthInterceptorTest와 동일한
// 오류 계약(40101)을 유지하는지 확인한다. MockAuthWebConfig(local)는 건드리지 않는다 - 그
// 프로필의 기존 테스트(MockAuthInterceptorTest)는 이 변경과 무관하게 그대로 통과해야 한다.
@WebMvcTest(AnalysisJobController.class)
@Import({ExperimentMockAuthWebConfig.class, MockUserRegistry.class})
@ActiveProfiles("experiment-api")
@TestPropertySource(properties = "experiment.auth.mock-enabled=true")
class ExperimentMockAuthWebConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ProductAnalysisJobService jobService;

    @MockitoBean
    private AnalysisJobQueryService jobQueryService;

    @Test
    void 헤더가_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/analyses/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }

    @Test
    void 헤더가_숫자가_아니면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/analyses/1").header("X-User-Id", "abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }

    @Test
    void 존재하지_않는_유저면_401을_반환한다() throws Exception {
        when(userRepository.existsById(999L)).thenReturn(false);

        mockMvc.perform(get("/api/analyses/1").header("X-User-Id", "999"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }

    @Test
    void 존재하는_유저면_currentUserId가_채워져_서비스로_전달된다() throws Exception {
        when(userRepository.existsById(1L)).thenReturn(true);
        ProductAnalysisJob job = ProductAnalysisJob.create(1L, "analysis-uploads/key", "idem-key");
        when(jobQueryService.getOwnedJob(1L, 1L)).thenReturn(job);

        mockMvc.perform(get("/api/analyses/1").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // currentUserId가 실제로 1L로 채워져 서비스에 그대로 전달됐는지(다른 userId로 오인되지
        // 않는지)를 인자값 검증으로 확인한다 - HTTP 200만으로는 어떤 userId가 쓰였는지 증명하지 못한다.
        verify(jobQueryService).getOwnedJob(eq(1L), eq(1L));
    }
}
