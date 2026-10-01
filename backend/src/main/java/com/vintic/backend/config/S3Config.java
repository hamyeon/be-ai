package com.vintic.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class S3Config {
    // 1. yml 파일에 적어둔 키값들을 읽어옴. 로컬 개발은 이 값들을 채워 StaticCredentialsProvider를
    // 쓰고, AWS 배포 환경은 이 값들을 비워둔 채 EC2 인스턴스 IAM 역할(DefaultCredentialsProvider)로
    // 인증한다 - 정적 액세스 키를 배포 환경 env에 넣지 않기 위함이다(credentialsProvider() 참고).
    @Value("${cloud.aws.credentials.access-key:}")
    private String accessKey;

    @Value("${cloud.aws.credentials.secret-key:}")
    private String secretKey;

    @Value("${cloud.aws.region.static}")
    private String region;

    // accessKey/secretKey가 둘 다 비어있으면 EC2 인스턴스 역할(또는 로컬 aws configure 등)로
    // 넘어가는 DefaultCredentialsProvider를 쓴다. 둘 중 하나만 비어있는 반쪽짜리 설정은 흔한
    // 오타/누락이라 조용히 역할 인증으로 넘어가지 않고 그대로 실패하게 둔다(StaticCredentialsProvider가
    // 빈 문자열로 인증을 시도해 명확하게 오류를 낸다).
    private AwsCredentialsProvider credentialsProvider() {
        if (accessKey.isBlank() && secretKey.isBlank()) {
            return DefaultCredentialsProvider.create();
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    // 2. 읽어온 정보로 S3Client 객체(S3과 통신할 수 있음)를 만들어 스프링에 등록.
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider())
                .build();
    }

    // presigned GET URL 발급용(S3UrlPresigner 참고) - 버킷을 public-read로 열지 않고, 필요한
    // 순간(Vision 호출 직전/분석 상태 응답 시)에만 시간 제한된 URL을 만들어 넘긴다.
    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider())
                .build();
    }
}
