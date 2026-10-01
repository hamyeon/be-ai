package com.vintic.backend.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.net.URL;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3UrlPresignerTest {

    private static final String BUCKET = "vintic-mvp-bucket-123";

    @Mock
    private S3Presigner presigner;

    @Mock
    private PresignedGetObjectRequest presignedRequest;

    private S3UrlPresigner newPresigner() {
        return new S3UrlPresigner(presigner, BUCKET);
    }

    @Test
    void 우리_버킷_URL이면_key를_뽑아_presign한_URL을_돌려준다() throws Exception {
        URL presignedUrl = URI.create("https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/"
                + "analysis/shoe.jpg?X-Amz-Signature=abc").toURL();
        when(presignedRequest.url()).thenReturn(presignedUrl);
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedRequest);

        String storedUrl = "https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/analysis/shoe.jpg";
        String result = newPresigner().presign(storedUrl, Duration.ofHours(1));

        assertThat(result).isEqualTo(presignedUrl.toString());
    }

    @Test
    void 요청한_key로_GetObjectRequest를_만든다() {
        URL presignedUrl = uncheckedUrl("https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/a.jpg?sig=1");
        when(presignedRequest.url()).thenReturn(presignedUrl);
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedRequest);

        newPresigner().presign("https://vintic-mvp-bucket-123.s3.ap-northeast-2.amazonaws.com/a.jpg", Duration.ofHours(1));

        var captor = org.mockito.ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        org.mockito.Mockito.verify(presigner).presignGetObject(captor.capture());
        GetObjectRequest getObjectRequest = captor.getValue().getObjectRequest();
        assertThat(getObjectRequest.bucket()).isEqualTo(BUCKET);
        assertThat(getObjectRequest.key()).isEqualTo("a.jpg");
    }

    @Test
    void 우리_버킷_형식이_아니면_원본_URL을_그대로_돌려준다() {
        String foreignUrl = "https://example.com/a.jpg";

        String result = newPresigner().presign(foreignUrl, Duration.ofHours(1));

        assertThat(result).isEqualTo(foreignUrl);
    }

    private URL uncheckedUrl(String value) {
        try {
            return URI.create(value).toURL();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
