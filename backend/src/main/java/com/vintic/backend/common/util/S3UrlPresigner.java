package com.vintic.backend.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

// S3UploaderService가 저장해 둔 공개 형식 URL(https://<bucket>.s3.<region>.amazonaws.com/<key>)을
// 그때그때 presigned GET URL로 바꾼다. 버킷을 public-read로 열지 않고(퍼블릭 액세스 차단 유지),
// 실제로 필요한 순간(Vision 호출 직전/분석 상태 조회 응답)에만 시간 제한된 접근을 내준다 -
// AnalysisTaskConsumer(분석 경로), ProductAnalyzeService.getStatus(표시 경로)가 호출한다.
//
// 서명된 URL 자체는 저장하지 않는다 - 원본 key(또는 공개 형식 URL)만 DB/Redis에 남기고, 응답할
// 때마다 새로 서명한다(TTL이 지나도 재사용 가능하도록 만들지 않기 위해서다).
@Component
@Slf4j
public class S3UrlPresigner {

    private final S3Presigner presigner;
    private final String bucket;

    public S3UrlPresigner(S3Presigner presigner, @Value("${cloud.aws.s3.bucket}") String bucket) {
        this.presigner = presigner;
        this.bucket = bucket;
    }

    public String presign(String storedUrl, Duration ttl) {
        String key = extractKey(storedUrl);
        if (key == null) {
            // 우리가 만든 형식이 아니면(예: 테스트 픽스처의 example.com URL) 그대로 돌려준다 -
            // presign 대상이 아닌 값을 오류로 막을 이유는 없다.
            return storedUrl;
        }

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(getObjectRequest)
                .build();

        // 서명 자체(쿼리스트링)는 절대 로그에 남기지 않는다 - key만 남긴다.
        log.debug("S3 객체를 presign합니다. bucket={}, key={}, ttlSeconds={}", bucket, key, ttl.getSeconds());

        return presigner.presignGetObject(presignRequest).url().toString();
    }

    private String extractKey(String storedUrl) {
        try {
            URI uri = URI.create(storedUrl);
            String host = uri.getHost();
            if (host == null || !host.startsWith(bucket + ".s3.")) {
                return null;
            }
            String path = uri.getPath();
            return (path != null && path.startsWith("/")) ? path.substring(1) : path;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
