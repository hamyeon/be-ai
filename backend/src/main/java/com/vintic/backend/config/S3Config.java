package com.vintic.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Config {
    // 1. yml 파일에 적어둔 키값들을 읽어옴.
    @Value("${cloud.aws.credentials.access-key}")
    private String accessKey;

    @Value("${cloud.aws.credentials.secret-key}")
    private String secretKey;

    @Value("${cloud.aws.region.static}")
    private String region;

    // Presigned URL 발급 전용 endpoint override(LocalStack). 비어있으면 실제 AWS 기본 엔드포인트를 쓴다.
    @Value("${cloud.aws.s3.presign.endpoint-override:}")
    private String presignEndpointOverride;

    // 2. 읽어온 정보로 S3Client 객체(S3과 통신할 수 있음)를 만들어 스프링에 등록.
    @Bean
    public S3Client s3Client() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);

        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();
    }

    // Presigned PUT URL 발급 전용 클라이언트. 자격증명을 하드코딩하지 않고 DefaultCredentialsProvider
    // (EC2 Role 등 기본 체인)를 쓴다 - 위 s3Client()의 static credentials와는 의도적으로 다르다.
    @Bean
    public S3Presigner s3Presigner() {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (presignEndpointOverride != null && !presignEndpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(presignEndpointOverride));
        }
        return builder.build();
    }
}
