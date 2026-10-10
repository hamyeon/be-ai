package com.vintic.backend.ai.vision.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class VisionImageLoaderTest {

    private static final String SOURCE_URL = "https://example.com/a.png";

    private final VisionImageLoader loader = new VisionImageLoader(new ImageResizer());

    @Test
    void maxEdge가_있으면_줄인_사본을_jpeg_data_URL로_만든다() throws IOException {
        VisionImageLoader.LoadedImage image = new VisionImageLoader.LoadedImage(SOURCE_URL, png(1000, 500));

        String input = loader.toImageInput(image, 512);

        assertThat(input).startsWith("data:image/jpeg;base64,");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(
                Base64.getDecoder().decode(input.substring(input.indexOf(',') + 1))));
        assertThat(decoded.getWidth()).isEqualTo(512);
        assertThat(decoded.getHeight()).isEqualTo(256);
    }

    @Test
    void maxEdge가_0이면_받은_바이트를_원래_형식_그대로_싣는다() throws IOException {
        byte[] bytes = png(100, 100);
        VisionImageLoader.LoadedImage image = new VisionImageLoader.LoadedImage(SOURCE_URL, bytes);

        String input = loader.toImageInput(image, 0);

        assertThat(input).isEqualTo("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes));
    }

    @Test
    void 받지_못한_사진은_원래_URL을_쓴다() {
        assertThat(loader.toImageInput(new VisionImageLoader.LoadedImage(SOURCE_URL, null), 512)).isEqualTo(SOURCE_URL);
    }

    @Test
    void 벤더가_받지_않는_형식이면_원래_URL을_쓴다() {
        // HEIC 같은 형식은 매직 넘버로 걸러지고, 줄이기도 실패하므로 URL로 되돌아간다.
        VisionImageLoader.LoadedImage image = new VisionImageLoader.LoadedImage(SOURCE_URL, "not an image".getBytes());

        assertThat(loader.toImageInput(image, 0)).isEqualTo(SOURCE_URL);
    }

    @Test
    void 매직_넘버로_형식을_판단한다() {
        assertThat(VisionImageLoader.sniffMediaType(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).contains("image/jpeg");
        assertThat(VisionImageLoader.sniffMediaType("GIF89a".getBytes())).contains("image/gif");
        assertThat(VisionImageLoader.sniffMediaType("RIFF\0\0\0\0WEBPVP8 ".getBytes())).contains("image/webp");
        assertThat(VisionImageLoader.sniffMediaType(new byte[]{1, 2, 3})).isEmpty();
    }

    @Test
    void 잘못된_URL은_예외_없이_받지_못한_사진이_된다() {
        assertThat(loader.load(java.util.List.of("not a url")))
                .singleElement()
                .satisfies(image -> assertThat(image.bytes()).isNull());
    }

    private byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
