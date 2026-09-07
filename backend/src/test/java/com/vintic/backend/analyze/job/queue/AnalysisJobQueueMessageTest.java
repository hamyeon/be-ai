package com.vintic.backend.analyze.job.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisJobQueueMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 직렬화된_메시지는_eventVersion과_analysisId만_가진다() throws Exception {
        AnalysisJobQueueMessage message = AnalysisJobQueueMessage.forJob(123L);

        String json = objectMapper.writeValueAsString(message);

        assertThat(objectMapper.readTree(json).size()).isEqualTo(2);
        assertThat(objectMapper.readTree(json).get("eventVersion").asInt()).isEqualTo(1);
        assertThat(objectMapper.readTree(json).get("analysisId").asLong()).isEqualTo(123L);
        assertThat(json).doesNotContain("objectKey");
    }
}
