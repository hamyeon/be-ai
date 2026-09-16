package com.vintic.backend.ai.vision.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ImageResizerTest {

    private static final int MAX_EDGE = 768;

    private final ImageResizer resizer = new ImageResizer();

    @ParameterizedTest
    @CsvSource({
            // 원본 가로, 원본 세로, 기대 가로, 기대 세로
            "3024, 4032,  576, 768",   // 폰 세로 사진
            "4032, 3024,  768, 576",   // 폰 가로 사진
            "1000, 1000,  768, 768",   // 정사각
            "2000,  500,  768, 192",   // 가로로 긴 사진
    })
    void 긴_변을_기준으로_비율을_지키며_줄인다(int width, int height, int expectedWidth, int expectedHeight) throws IOException {
        ImageResizer.Resized resized = resizer.resize(jpeg(width, height), MAX_EDGE).orElseThrow();

        assertThat(resized.width()).isEqualTo(expectedWidth);
        assertThat(resized.height()).isEqualTo(expectedHeight);
        assertThat(Math.max(resized.width(), resized.height())).isEqualTo(MAX_EDGE);
        assertThat(resized.contentType()).isEqualTo("image/jpeg");
        // 돌려준 크기가 실제 바이트와 맞는지도 확인한다.
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(resized.bytes()));
        assertThat(decoded.getWidth()).isEqualTo(expectedWidth);
        assertThat(decoded.getHeight()).isEqualTo(expectedHeight);
    }

    @ParameterizedTest
    @CsvSource({"768, 768", "600, 400", "300, 300", "100, 50"})
    void 이미_충분히_작은_이미지는_손대지_않는다(int width, int height) throws IOException {
        // 비어 있음 = "원본을 그대로 쓰라". 확대하면 정보는 안 늘고 토큰만 는다.
        assertThat(resizer.resize(jpeg(width, height), MAX_EDGE)).isEmpty();
    }

    @Test
    void EXIF_회전이_적용된_사진은_바로_선_상태로_줄인다() throws IOException {
        // orientation=6은 "시계방향 90도 돌려서 보여라"다. 저장된 픽셀은 1200x900(가로)이지만
        // 사람이 보는 방향은 900x1200(세로)다. EXIF를 무시하면 768x576이 나오고 신발이 눕는다.
        byte[] rotated = jpegWithExifOrientation(1200, 900, 6);

        ImageResizer.Resized resized = resizer.resize(rotated, MAX_EDGE).orElseThrow();

        assertThat(resized.width()).isEqualTo(576);
        assertThat(resized.height()).isEqualTo(768);
    }

    @Test
    void EXIF가_정방향이면_그대로_둔다() throws IOException {
        byte[] upright = jpegWithExifOrientation(1200, 900, 1);

        ImageResizer.Resized resized = resizer.resize(upright, MAX_EDGE).orElseThrow();

        assertThat(resized.width()).isEqualTo(768);
        assertThat(resized.height()).isEqualTo(576);
    }

    @Test
    void 투명_PNG는_검은색이_아니라_흰_배경으로_합성된다() throws IOException {
        // JPEG는 알파를 못 담는다. 그냥 쓰면 투명 영역이 검게 나오고, 검은 배경은 신발 윤곽·색상
        // 판정을 방해한다.
        byte[] transparent = transparentPng(1000, 1000);

        ImageResizer.Resized resized = resizer.resize(transparent, MAX_EDGE).orElseThrow();

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(resized.bytes()));
        Color center = new Color(decoded.getRGB(decoded.getWidth() / 2, decoded.getHeight() / 2));
        // JPEG 압축으로 정확히 255는 아닐 수 있어 여유를 둔다. 검은색(0)과는 확연히 구분된다.
        assertThat(center.getRed()).isGreaterThan(240);
        assertThat(center.getGreen()).isGreaterThan(240);
        assertThat(center.getBlue()).isGreaterThan(240);
    }

    @Test
    void 읽을_수_없는_바이트는_예외_대신_빈_값을_돌려준다() {
        // 리사이즈는 비용 최적화다. 여기서 예외가 나가면 업로드와 분석 전체가 실패한다.
        assertThat(resizer.resize("이미지가 아닌 바이트".getBytes(), MAX_EDGE)).isEmpty();
        assertThat(resizer.resize(new byte[]{0x00, 0x01, 0x02}, MAX_EDGE)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"0", "-1"})
    void maxEdge가_0이하면_리사이즈하지_않는다(int maxEdge) throws IOException {
        // 설정으로 기능을 끌 수 있어야 한다.
        assertThat(resizer.resize(jpeg(3024, 4032), maxEdge)).isEmpty();
    }

    @Test
    void null이나_빈_바이트도_예외를_내지_않는다() {
        assertThat(resizer.resize(null, MAX_EDGE)).isEmpty();
        assertThat(resizer.resize(new byte[0], MAX_EDGE)).isEmpty();
    }

    @Test
    void 줄인_사본은_원본보다_훨씬_작다() throws IOException {
        byte[] source = jpeg(3024, 4032);

        ImageResizer.Resized resized = resizer.resize(source, MAX_EDGE).orElseThrow();

        assertThat(resized.bytes().length).isLessThan(source.length);
    }

    // --- 픽스처 ---

    private byte[] jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            // 단색이면 JPEG가 극단적으로 압축돼 크기 비교가 무의미해진다. 무늬를 넣는다.
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
        return out.toByteArray();
    }

    private byte[] transparentPng(int width, int height) throws IOException {
        // 전부 투명한 이미지. 합성이 없으면 JPEG 변환 시 전체가 검게 된다.
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // ImageIO는 EXIF를 써주지 않으므로 JPEG의 SOI 바로 뒤에 APP1(Exif) 세그먼트를 직접 끼워 넣는다.
    // orientation 태그(0x0112) 하나만 담은 최소 구성이다.
    private byte[] jpegWithExifOrientation(int width, int height, int orientation) throws IOException {
        byte[] base = jpeg(width, height);

        byte[] tiff = new byte[]{
                'I', 'I',                                    // 리틀엔디안
                0x2A, 0x00,                                  // TIFF 매직
                0x08, 0x00, 0x00, 0x00,                      // IFD0 오프셋 = 8
                0x01, 0x00,                                  // 항목 1개
                0x12, 0x01,                                  // 태그 0x0112 (Orientation)
                0x03, 0x00,                                  // 타입 SHORT
                0x01, 0x00, 0x00, 0x00,                      // 개수 1
                (byte) orientation, 0x00, 0x00, 0x00,        // 값 (SHORT는 앞 2바이트)
                0x00, 0x00, 0x00, 0x00                       // 다음 IFD 없음
        };
        byte[] header = {'E', 'x', 'i', 'f', 0x00, 0x00};
        int segmentLength = 2 + header.length + tiff.length;  // 길이 필드 자신을 포함한다

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(base, 0, 2);                                // SOI (FF D8)
        out.write(0xFF);
        out.write(0xE1);                                      // APP1
        out.write((segmentLength >> 8) & 0xFF);
        out.write(segmentLength & 0xFF);
        out.write(header);
        out.write(tiff);
        out.write(base, 2, base.length - 2);                  // 나머지 원본
        return out.toByteArray();
    }
}
