package com.vintic.backend.analyze.job.upload;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

// Day10 배포 실패 회귀 테스트: 실제 S3Presigner(목이 아님)로 presignPutObject()를 호출해
// AWS SDK v2 모듈 버전이 섞였을 때(s3/sqs 2.25.11 vs sdk-core/auth 등 2.31.78) 발생했던
// "Cannot invoke ...ClientEndpointProvider.isEndpointOverridden() because ep is null" NPE를
// 재현/검증한다. presignPutObject()는 서명을 로컬에서 계산할 뿐 네트워크 호출을 하지 않으므로
// 고정 테스트 credentials만으로 안전하게 호출할 수 있다.
class PresignedUploadServiceSdkCompatibilityTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void 실제_S3Presigner로_presignPutObject를_호출해도_SDK_버전_혼합_NPE가_발생하지_않는다() {
        S3Presigner presigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .build();

        PresignedUploadService service = new PresignedUploadService(presigner, FIXED_CLOCK, "test-bucket", 600);

        PresignedUploadResponse response = service.createPresignedUpload();

        assertThat(response.uploadUrl())
                .startsWith("https://test-bucket.s3.ap-northeast-2.amazonaws.com/")
                .contains("X-Amz-Signature=");
        assertThat(response.objectKey()).isNotBlank();
    }
}
