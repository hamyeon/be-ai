package com.vintic.backend.analyze.job.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

// objectKey는 클라이언트가 정하지 않고 여기서만 생성한다 - 클라이언트가 임의 경로에 덮어쓰거나
// 다른 사용자의 objectKey를 추측해 충돌시키는 것을 막기 위함이다.
@Service
public class PresignedUploadService {

    private static final String OBJECT_KEY_PREFIX = "analysis-uploads/";

    private final S3Presigner s3Presigner;
    private final Clock clock;
    private final String bucket;
    private final long expirySeconds;

    public PresignedUploadService(
            S3Presigner s3Presigner,
            Clock clock,
            @Value("${cloud.aws.s3.bucket}") String bucket,
            @Value("${cloud.aws.s3.presign.expiry-seconds:600}") long expirySeconds
    ) {
        this.s3Presigner = s3Presigner;
        this.clock = clock;
        this.bucket = bucket;
        this.expirySeconds = expirySeconds;
    }

    public PresignedUploadResponse createPresignedUpload() {
        String objectKey = OBJECT_KEY_PREFIX + UUID.randomUUID();

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(expirySeconds))
                .putObjectRequest(putObjectRequest)
                .build();

        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);

        // presigned URL 자체가 서명된 업로드 권한이다 - 절대 로그에 남기지 않는다.
        return new PresignedUploadResponse(
                objectKey,
                presigned.url().toString(),
                LocalDateTime.now(clock).plusSeconds(expirySeconds)
        );
    }
}
