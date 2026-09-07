package com.vintic.backend.analyze.job.upload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresignedUploadServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZONE);

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private PresignedPutObjectRequest presignedPutObjectRequest;

    private PresignedUploadService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new PresignedUploadService(s3Presigner, FIXED_CLOCK, "test-bucket", 600);
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenReturn(presignedPutObjectRequest);
        when(presignedPutObjectRequest.url())
                .thenReturn(URI.create("https://test-bucket.s3.amazonaws.com/signed?X-Amz-Signature=abc").toURL());
    }

    @Test
    void 응답에_objectKey_uploadUrl_expiresAt이_모두_포함된다() {
        PresignedUploadResponse response = service.createPresignedUpload();

        assertThat(response.objectKey()).isNotBlank();
        assertThat(response.uploadUrl()).isEqualTo("https://test-bucket.s3.amazonaws.com/signed?X-Amz-Signature=abc");
        assertThat(response.expiresAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK).plusSeconds(600));
    }

    // objectKey가 클라이언트 입력이 아니라 서버에서 매 호출마다 새로 생성됨을 확인한다 -
    // 이 서비스에는 objectKey를 파라미터로 받는 경로 자체가 없다.
    @Test
    void 호출할_때마다_objectKey가_서버에서_새로_생성된다() {
        PresignedUploadResponse first = service.createPresignedUpload();
        PresignedUploadResponse second = service.createPresignedUpload();

        assertThat(first.objectKey()).isNotEqualTo(second.objectKey());
    }
}
