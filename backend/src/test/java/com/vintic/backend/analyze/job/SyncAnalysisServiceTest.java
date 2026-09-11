package com.vintic.backend.analyze.job;

import com.vintic.backend.analyze.job.processor.AnalysisInput;
import com.vintic.backend.analyze.job.processor.AnalysisPayload;
import com.vintic.backend.analyze.job.processor.AnalysisProcessor;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

// SyncAnalysisService는 SqsAnalysisJobHandler(async)와 동일한 AnalysisProcessor 빈을
// 한 번만 호출하고, DB(ProductAnalysisJobRepository/AnalysisResultRepository)나
// QueuePublisher는 아예 의존성으로 갖지 않는다 - 필드 자체가 없으므로 "SQS를 호출하지
// 않는다"는 구조적으로 보장된다. 여기서는 그 유일한 동작(S3 GetObject -> Processor.process()
// 정확히 1회 -> 결과 그대로 반환)만 검증한다.
class SyncAnalysisServiceTest {

    private static final String BUCKET = "test-bucket";
    private static final String OBJECT_KEY = "uploads/test.jpg";
    private static final byte[] IMAGE_BYTES = {1, 2, 3, 4};

    @Test
    void S3에서_읽은_내용으로_Processor를_정확히_한번_호출하고_결과를_그대로_반환한다() {
        S3Client s3Client = mock(S3Client.class);
        AnalysisProcessor processor = mock(AnalysisProcessor.class);
        AnalysisPayload expected = new AnalysisPayload("sync-result");

        ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(IMAGE_BYTES))
        );
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);
        when(processor.process(any(AnalysisInput.class))).thenReturn(expected);

        SyncAnalysisService service = new SyncAnalysisService(s3Client, processor, BUCKET);
        AnalysisPayload actual = service.analyze(OBJECT_KEY);

        assertThat(actual).isEqualTo(expected);

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client, times(1)).getObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(requestCaptor.getValue().key()).isEqualTo(OBJECT_KEY);

        var inputCaptor = org.mockito.ArgumentCaptor.forClass(AnalysisInput.class);
        verify(processor, times(1)).process(inputCaptor.capture());
        assertThat(inputCaptor.getValue().imageContent()).isEqualTo(IMAGE_BYTES);
        // times(1) 검증이 이미 "정확히 한 번만" 호출됐음을 보장한다 - 그 외 상호작용도 없다.
        verifyNoMoreInteractions(processor);
    }
}
