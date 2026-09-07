package com.vintic.backend.analyze.job.upload;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UploadController.class)
class UploadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PresignedUploadService presignedUploadService;

    @Test
    void 응답에_objectKey_uploadUrl_expiresAt이_모두_포함된다() throws Exception {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 9, 7, 1, 0, 0);
        when(presignedUploadService.createPresignedUpload()).thenReturn(
                new PresignedUploadResponse("analysis-uploads/abc-123", "https://bucket.s3.amazonaws.com/signed", expiresAt)
        );

        mockMvc.perform(post("/api/uploads/presigned-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.objectKey").value("analysis-uploads/abc-123"))
                .andExpect(jsonPath("$.data.uploadUrl").value("https://bucket.s3.amazonaws.com/signed"))
                .andExpect(jsonPath("$.data.expiresAt").exists());
    }
}
