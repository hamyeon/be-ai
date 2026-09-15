package com.vintic.backend.ai.vision.image;

import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.filters.ImageFilter;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

// Vision 분석용 이미지를 긴 변 기준으로 줄인다 (#102).
//
// 왜 필요한가:
//   이미지 토큰은 해상도에 정비례한다. Claude는 (가로 x 세로)/750, OpenAI는 detail=high일 때
//   512px 타일 수로 센다. 3단계 분석이라 같은 이미지를 3번 보내므로 원본을 그대로 넘기면
//   케이스당 이미지 토큰이 곱절로 늘어난다. 폰 사진(3024x4032)은 장당 2,459토큰,
//   768px로 줄이면 786토큰이다.
//
// 표시용 원본은 건드리지 않는다. 같은 이미지가 Product.imageUrls로 구매자에게 노출되므로
// 화질을 낮추면 안 된다. 이 클래스가 만드는 건 분석 전용 사본이다.
//
// 세 가지를 반드시 처리한다.
//   1. EXIF 회전 - ImageIO는 EXIF orientation을 무시해서 세로 사진이 눕는다. 누운 신발 사진은
//      판정을 나쁘게 만든다. Thumbnailator가 읽기 단계에서 보정한다(useExifOrientation).
//   2. 투명 PNG - JPEG는 알파를 못 담아 투명 영역이 검게 변한다. 흰 배경으로 합성한다.
//   3. 확대 금지 - 원본이 이미 작으면 손대지 않는다. 확대해봐야 정보는 안 늘고 토큰만 는다.
@Component
@Slf4j
public class ImageResizer {

    public static final String OUTPUT_FORMAT = "jpg";
    public static final String OUTPUT_CONTENT_TYPE = "image/jpeg";

    // 0.85는 육안으로 구분이 어려우면서 파일 크기가 크게 줄어드는 구간이다. 이 값 자체가
    // 토큰 수에 영향을 주지는 않는다(토큰은 해상도로만 결정된다) - 전송량과 저장 비용만 줄인다.
    private static final double OUTPUT_QUALITY = 0.85;

    public record Resized(byte[] bytes, int width, int height, String contentType) {
    }

    // 줄인 사본을 돌려준다. 비어 있으면 "원본을 그대로 쓰라"는 뜻이다 - 이미 충분히 작거나,
    // 읽지 못했거나, 변환에 실패한 경우다. 예외를 던지지 않는다: 리사이즈는 비용 최적화이고,
    // 그것 때문에 업로드나 분석이 실패하면 안 된다.
    public Optional<Resized> resize(byte[] source, int maxEdge) {
        if (source == null || source.length == 0 || maxEdge <= 0) {
            return Optional.empty();
        }

        Optional<int[]> dimensions = readDimensions(source);
        if (dimensions.isEmpty()) {
            log.warn("이미지 크기를 읽지 못해 리사이즈를 건너뜁니다. bytes={}", source.length);
            return Optional.empty();
        }
        int width = dimensions.get()[0];
        int height = dimensions.get()[1];
        if (Math.max(width, height) <= maxEdge) {
            // EXIF 회전으로 가로세로가 뒤바뀌어도 긴 변 길이는 같으므로 이 판단은 회전과 무관하다.
            log.debug("이미 충분히 작아 원본을 사용합니다. {}x{} <= {}", width, height, maxEdge);
            return Optional.empty();
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.of(new ByteArrayInputStream(source))
                    .size(maxEdge, maxEdge)          // 비율을 지키며 maxEdge x maxEdge 안에 맞춘다
                    .keepAspectRatio(true)
                    .useExifOrientation(true)        // 기본값이지만 의도를 코드에 남긴다
                    .addFilter(new WhiteBackgroundFilter())
                    .outputFormat(OUTPUT_FORMAT)
                    .outputQuality(OUTPUT_QUALITY)
                    .toOutputStream(out);

            byte[] resized = out.toByteArray();
            Optional<int[]> resultSize = readDimensions(resized);
            int resultWidth = resultSize.map(d -> d[0]).orElse(0);
            int resultHeight = resultSize.map(d -> d[1]).orElse(0);

            log.debug("리사이즈 완료 {}x{} -> {}x{}, {}KB -> {}KB",
                    width, height, resultWidth, resultHeight, source.length / 1024, resized.length / 1024);
            return Optional.of(new Resized(resized, resultWidth, resultHeight, OUTPUT_CONTENT_TYPE));
        } catch (IOException | RuntimeException e) {
            log.warn("이미지 리사이즈에 실패해 원본을 사용합니다. {}x{}, message={}", width, height, e.getMessage());
            return Optional.empty();
        }
    }

    // 전체를 디코딩하지 않고 헤더만 읽어 크기를 구한다. 폰 사진 한 장을 BufferedImage로 펼치면
    // 수십 MB라, 줄일 필요가 없는 이미지까지 메모리에 올릴 이유가 없다.
    private Optional<int[]> readDimensions(byte[] source) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (input == null) {
                return Optional.empty();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                return Optional.of(new int[]{reader.getWidth(0), reader.getHeight(0)});
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    // 알파 채널이 있으면 흰 배경 위에 합성한다. JPEG로 그냥 쓰면 투명 영역이 검게 나오고,
    // 검은 배경은 신발 윤곽·색상 판정을 방해한다.
    private static final class WhiteBackgroundFilter implements ImageFilter {

        @Override
        public BufferedImage apply(BufferedImage image) {
            if (!image.getColorModel().hasAlpha()) {
                return image;
            }
            BufferedImage flattened =
                    new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = flattened.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, flattened.getWidth(), flattened.getHeight());
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            return flattened;
        }
    }
}
