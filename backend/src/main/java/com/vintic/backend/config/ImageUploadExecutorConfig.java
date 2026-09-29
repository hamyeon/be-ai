package com.vintic.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 사진을 S3에 올릴 때 쓰는 스레드 풀(#106).
//
// 사진 한 장당 원본과 분석용 사본 두 번을 올려서, 3장이면 업로드 6번을 줄줄이 기다린다.
// 그 시간이 사용자가 분석 결과를 받기까지의 시간에 그대로 더해진다.
//
// 요청 스레드가 이미 하나 붙어 있으니 풀은 작게 둔다. 계산이 아니라 네트워크 대기라 코어 수와는 무관하다.
@Configuration
public class ImageUploadExecutorConfig {

    public static final String IMAGE_UPLOAD_EXECUTOR = "imageUploadExecutor";

    @Bean(name = IMAGE_UPLOAD_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService imageUploadExecutor(@Value("${cloud.aws.s3.upload-concurrency:4}") int concurrency) {
        return Executors.newFixedThreadPool(Math.max(1, concurrency), runnable -> {
            Thread thread = new Thread(runnable, "image-upload");
            thread.setDaemon(true);
            return thread;
        });
    }
}
