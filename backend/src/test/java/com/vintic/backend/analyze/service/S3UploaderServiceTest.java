package com.vintic.backend.analyze.service;

import com.vintic.backend.ai.vision.image.ImageResizer;
import com.vintic.backend.ai.vision.image.VisionImageProperties;
import com.vintic.backend.common.exception.S3UploadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// #102: 한 장을 표시용 원본 + 분석용 축소본 두 벌로 올리는 동작을 고정한다.
class S3UploaderServiceTest {

    private static final String BUCKET = "vintic-test";

    private S3Client s3Client;
    private VisionImageProperties imageProperties;
    private S3UploaderService service;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        imageProperties = new VisionImageProperties();
        service = new S3UploaderService(s3Client, new ImageResizer(), imageProperties);
        ReflectionTestUtils.setField(service, "bucket", BUCKET);
    }

    @Test
    void 큰_이미지는_원본과_분석용_사본을_각각_올린다() throws IOException {
        MultipartFile image = jpegFile("shoe.jpg", 2000, 1500);

        S3UploaderService.UploadedImage uploaded = service.uploadImage(image);

        List<PutObjectRequest> requests = capturePutRequests(2);
        PutObjectRequest original = requests.get(0);
        PutObjectRequest analysis = requests.get(1);

        assertThat(original.key()).endsWith("_shoe.jpg").doesNotStartWith("analysis/");
        assertThat(original.contentType()).isEqualTo("image/jpeg");
        // 분석용은 항상 JPEG이고 별도 프리픽스에 둔다. 원본 파일명을 남겨 짝을 찾기 쉽게 한다.
        assertThat(analysis.key()).startsWith("analysis/").endsWith("_shoe.jpg");
        assertThat(analysis.contentType()).isEqualTo(ImageResizer.OUTPUT_CONTENT_TYPE);

        assertThat(uploaded.originalUrl()).contains(original.key()).doesNotContain("analysis/");
        assertThat(uploaded.analysisUrl()).contains("analysis/");
        assertThat(uploaded.analysisUrl()).isNotEqualTo(uploaded.originalUrl());
    }

    @Test
    void 이미_작은_이미지는_원본만_올리고_분석용_URL은_원본을_가리킨다() throws IOException {
        MultipartFile image = jpegFile("small.jpg", 400, 300);

        S3UploaderService.UploadedImage uploaded = service.uploadImage(image);

        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(uploaded.analysisUrl()).isEqualTo(uploaded.originalUrl());
    }

    @Test
    void 사본_업로드가_실패해도_원본_URL로_계속_진행한다() throws IOException {
        // 사본은 비용 최적화다. 실패해도 상품 등록과 분석은 돌아야 한다.
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build())
                .thenThrow(S3Exception.builder().message("사본 업로드 실패").build());

        S3UploaderService.UploadedImage uploaded = service.uploadImage(jpegFile("shoe.jpg", 2000, 1500));

        assertThat(uploaded.analysisUrl()).isEqualTo(uploaded.originalUrl());
    }

    @Test
    void 원본_업로드가_실패하면_예외를_던진다() throws IOException {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("원본 업로드 실패").build());

        assertThatThrownBy(() -> service.uploadImage(jpegFile("shoe.jpg", 2000, 1500)))
                .isInstanceOf(S3UploadException.class);
    }

    @Test
    void maxEdge를_0으로_두면_사본을_만들지_않는다() throws IOException {
        // 설정으로 기능을 끌 수 있어야 한다.
        imageProperties.setMaxEdge(0);

        S3UploaderService.UploadedImage uploaded = service.uploadImage(jpegFile("shoe.jpg", 2000, 1500));

        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(uploaded.analysisUrl()).isEqualTo(uploaded.originalUrl());
    }

    @Test
    void 빈_파일은_건너뛴다() throws IOException {
        MultipartFile empty = new MockMultipartFile("images", "empty.jpg", "image/jpeg", new byte[0]);

        List<S3UploaderService.UploadedImage> uploaded =
                service.uploadImages(List.of(empty, jpegFile("shoe.jpg", 400, 300)));

        assertThat(uploaded).hasSize(1);
    }

    private List<PutObjectRequest> capturePutRequests(int expectedCount) {
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(expectedCount)).putObject(captor.capture(), any(RequestBody.class));
        return captor.getAllValues();
    }

    private MultipartFile jpegFile(String name, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            for (int y = 0; y < height; y += 16) {
                for (int x = 0; x < width; x += 16) {
                    graphics.setColor(new Color((x * 7) % 256, (y * 11) % 256, ((x + y) * 3) % 256));
                    graphics.fillRect(x, y, 16, 16);
                }
            }
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return new MockMultipartFile("images", name, "image/jpeg", out.toByteArray());
    }
}
