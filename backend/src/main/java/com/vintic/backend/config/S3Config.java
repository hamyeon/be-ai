package com.vintic.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
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

    // GetObject/PutObject 등 실제 S3 호출용 endpoint override(LocalStack, Day5 실험 전용).
    // presign.endpoint-override와 별도 키인 이유: presigned URL은 외부(브라우저/curl)가
    // 호스트에서 열어야 하므로 host-reachable 주소(예: http://localhost:4566)를 쓰고,
    // 이 S3Client는 컨테이너 안에서만 쓰이므로 docker 내부 hostname(예: http://localstack:4566)을
    // 쓴다 - 같은 LocalStack이라도 두 값이 다를 수 있다. 비어있으면(기본값) 기존과 동일하게
    // 실제 AWS 기본 엔드포인트를 쓴다 - 이 프로젝트의 실제 AWS 배포 동작은 바뀌지 않는다.
    @Value("${cloud.aws.s3.endpoint-override:}")
    private String s3EndpointOverride;

    // 2. 읽어온 정보로 S3Client 객체(S3과 통신할 수 있음)를 만들어 스프링에 등록.
    //
    // 자격증명은 endpoint override 유무로 갈린다 - endpoint override가 비어있으면(실제 AWS)
    // EC2 IAM Role 등 기본 체인(DefaultCredentialsProvider)을 쓴다. 채워져 있으면(LocalStack)
    // LocalStack이 검증하지 않는 고정 문자열 static credentials를 그대로 쓴다(기존 동작 유지).
    // 실제 AWS 배포 컨테이너에는 AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY를 넣지 않으므로
    // accessKey/secretKey는 그 경로에서는 비어있는 채로 무시된다.
    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region));

        if (s3EndpointOverride != null && !s3EndpointOverride.isBlank()) {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
            builder.credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .endpointOverride(URI.create(s3EndpointOverride))
                    // LocalStack에 path-style(http://host:port/bucket/key)로 접근한다 -
                    // 가상 호스트 스타일(bucket.host)은 로컬 endpoint override에서 DNS가 없어 동작하지 않는다.
                    .forcePathStyle(true);
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    // Presigned PUT URL 발급 전용 클라이언트. 자격증명을 하드코딩하지 않고 DefaultCredentialsProvider
    // (EC2 Role 등 기본 체인)를 쓴다 - 위 s3Client()의 static credentials와는 의도적으로 다르다.
    @Bean
    public S3Presigner s3Presigner() {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (presignEndpointOverride != null && !presignEndpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(presignEndpointOverride))
                    // Day5 3단계에서 실제로 발견: s3Client()와 같은 이유로 이 Presigner도
                    // path-style을 강제해야 한다 - 강제하지 않으면 가상 호스트 스타일
                    // (bucket.localhost:4566) URL이 발급되는데, 로컬 환경에 그 서브도메인에 대한
                    // DNS가 없어 실제 HTTP PUT이 404로 실패하는 것을 직접 확인했다.
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }
}
