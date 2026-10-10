package com.vintic.backend.ai.vision.image;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

// 분석용 사진을 서버가 직접 받아 data URL(base64)로 바꾼다.
//
// 왜 필요한가:
//   URL을 넘기면 벤더 서버가 S3에서 사진을 직접 가져간다. 3단계가 동시에 나가면 같은 사진을 세 번 가져간다.
//   서버가 한 번 받아 요청에 실어 보내면 그 왕복이 사라진다 - 대신 서버의 다운로드 시간이 앞에 붙는다.
//   어느 쪽이 빠른지는 하네스로 잰다.
//
//   단계마다 다른 해상도를 쓰려면(실루엣만 512px 등) 서버가 바이트를 가지고 있어야 한다.
//   Claude에는 OpenAI의 detail=low 같은 해상도 옵션이 없어서, 줄인 사본을 직접 만들어 보내는 수밖에 없다.
//
// 실패해도 예외를 던지지 않는다. 받지 못한 사진은 원래 URL을 그대로 쓴다 - 최적화 때문에 분석이 실패하면 안 된다.
@Component
@Slf4j
public class VisionImageLoader {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ImageResizer imageResizer;

    public VisionImageLoader(ImageResizer imageResizer) {
        this.imageResizer = imageResizer;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // bytes가 null이면 받지 못한 사진이다. 순서는 입력 URL 순서 그대로다(근거의 imageIndex가 이 순서를 가리킨다).
    public record LoadedImage(String sourceUrl, byte[] bytes) {

        boolean loaded() {
            return bytes != null && bytes.length > 0;
        }
    }

    // 모든 사진을 동시에 받는다. 분석 한 건의 사진 수가 많지 않아(최대 10장) 따로 제한하지 않는다.
    public List<LoadedImage> load(List<String> urls) {
        List<CompletableFuture<LoadedImage>> futures = urls.stream().map(this::loadAsync).toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private CompletableFuture<LoadedImage> loadAsync(String url) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(new LoadedImage(url, null));
        }
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        log.warn("분석용 사진을 받지 못해 URL을 그대로 씁니다. status={}", response.statusCode());
                        return new LoadedImage(url, null);
                    }
                    return new LoadedImage(url, response.body());
                })
                .exceptionally(error -> {
                    // presigned URL에는 서명이 붙어 있으니 URL은 남기지 않는다.
                    log.warn("분석용 사진을 받지 못해 URL을 그대로 씁니다. message={}", error.getMessage());
                    return new LoadedImage(url, null);
                });
    }

    // maxEdge > 0이면 그 크기로 줄인 사본을, 아니면 받은 바이트 그대로를 data URL로 만든다.
    // 받지 못했거나 벤더가 받는 형식(jpeg/png/gif/webp)이 아니면 원래 URL을 돌려준다.
    public String toImageInput(LoadedImage image, int maxEdge) {
        if (!image.loaded()) {
            return image.sourceUrl();
        }
        byte[] bytes = image.bytes();
        if (maxEdge > 0) {
            Optional<ImageResizer.Resized> resized = imageResizer.resize(bytes, maxEdge);
            if (resized.isPresent()) {
                bytes = resized.get().bytes();
            }
        }
        Optional<String> mediaType = sniffMediaType(bytes);
        if (mediaType.isEmpty()) {
            log.warn("지원하지 않는 이미지 형식이라 URL을 그대로 씁니다. bytes={}", bytes.length);
            return image.sourceUrl();
        }
        return "data:%s;base64,%s".formatted(mediaType.get(), Base64.getEncoder().encodeToString(bytes));
    }

    // 응답 Content-Type은 믿지 않는다. S3 객체는 업로드 때 받은 값을 그대로 돌려주고, 그 값이
    // application/octet-stream인 경우가 있다. 파일 앞부분(매직 넘버)으로 판단한다.
    static Optional<String> sniffMediaType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return Optional.of("image/png");
        }
        if (bytes.length >= 4 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return Optional.of("image/gif");
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }
}
