package com.vintic.backend.analyze.service;

import com.vintic.backend.ai.vision.image.ImageResizer;
import com.vintic.backend.ai.vision.image.VisionImageProperties;
import com.vintic.backend.common.exception.S3UploadException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// S3에 이미지를 저장하고 URL을 조립해 돌려주는 서비스.
//
// #102: 한 장을 두 벌로 올린다.
//   - 원본: 구매자에게 보이는 상품 사진(Product.imageUrls로 이어진다). 화질을 건드리지 않는다.
//   - 분석용 사본: Vision에 넘길 축소본(analysis/ 프리픽스). 이미지 토큰이 해상도에 정비례하고
//     3단계 분석이 같은 이미지를 3번 보내므로, 원본을 그대로 넘기면 비용이 곱절로 든다.
//
// 축소가 필요 없거나(이미 작음) 실패하면 분석용 URL 자리에 원본 URL을 넣는다. 리사이즈는
// 비용 최적화일 뿐이라 그것 때문에 업로드나 분석이 실패하면 안 된다.
@Service
@RequiredArgsConstructor
@Slf4j
public class S3UploaderService {

    // 분석용 사본을 원본과 같은 버킷의 별도 프리픽스에 둔다. 수명주기 정책을 따로 걸거나
    // 한꺼번에 비울 때 원본을 건드리지 않기 위해서다.
    private static final String ANALYSIS_KEY_PREFIX = "analysis/";

    private final S3Client s3Client;
    private final ImageResizer imageResizer;
    private final VisionImageProperties imageProperties;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    // 표시용 원본 URL과 분석용 URL의 쌍. 분석용이 없으면 원본 URL이 그대로 들어간다.
    public record UploadedImage(String originalUrl, String analysisUrl) {
    }

    public List<UploadedImage> uploadImages(List<MultipartFile> images) {
        List<UploadedImage> uploaded = new ArrayList<>();
        for (MultipartFile image : images) {
            // 빈 파일이 섞여 들어오면 무시하고 다음 파일 진행
            if (image == null || image.isEmpty()) {
                continue;
            }
            uploaded.add(uploadImage(image));
        }
        return uploaded;
    }

    public UploadedImage uploadImage(MultipartFile image) {
        byte[] source = readBytes(image);

        // 파일명 추출 및 변환
        String originalFilename = image.getOriginalFilename();
        String uniqueFilename = UUID.randomUUID() + "_" + originalFilename; // 난수 붙이기 (예: 1234_신발.jpg)

        String originalUrl = putObject(uniqueFilename, image.getContentType(), source);
        String analysisUrl = uploadAnalysisCopy(uniqueFilename, source).orElse(originalUrl);

        return new UploadedImage(originalUrl, analysisUrl);
    }

    private Optional<String> uploadAnalysisCopy(String uniqueFilename, byte[] source) {
        Optional<ImageResizer.Resized> resized = imageResizer.resize(source, imageProperties.getMaxEdge());
        if (resized.isEmpty()) {
            return Optional.empty();
        }
        ImageResizer.Resized copy = resized.get();
        try {
            String url = putObject(analysisKey(uniqueFilename), copy.contentType(), copy.bytes());
            log.debug("분석용 사본 업로드 - {}x{}, {}KB", copy.width(), copy.height(), copy.bytes().length / 1024);
            return Optional.of(url);
        } catch (RuntimeException e) {
            // 사본 업로드 실패가 상품 등록을 막으면 안 된다. 원본으로 분석하면 비용만 더 든다.
            log.warn("분석용 사본 업로드에 실패해 원본으로 분석합니다. message={}", e.getMessage());
            return Optional.empty();
        }
    }

    // 항상 JPEG로 저장되므로 확장자를 .jpg로 맞춘다. 원본 파일명을 남겨 두면 S3에서 짝을 찾기 쉽다.
    private String analysisKey(String uniqueFilename) {
        int dot = uniqueFilename.lastIndexOf('.');
        String base = dot > 0 ? uniqueFilename.substring(0, dot) : uniqueFilename;
        return ANALYSIS_KEY_PREFIX + base + "." + ImageResizer.OUTPUT_FORMAT;
    }

    private String putObject(String key, String contentType, byte[] body) {
        try {
            // S3 업로드 요청서 작성
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucket) // 어느 버킷에?
                    .key(key) // 무슨 이름으로?
                    .contentType(contentType) // 파일 종류? (이미지)
                    .build();

            // 파일 전송 (진짜 업로드 명령)
            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(body));

            // URL 조립
            // S3 기본 주소 + 버킷 이름 + 지역(서울) + 파일이름을 합침
            return "https://" + bucket + ".s3.ap-northeast-2.amazonaws.com/" + key;
        } catch (Exception e) {
            // S3 에러 발생 시 전용 에러로 바꿔서 던지기
            throw new S3UploadException("S3 이미지 업로드 중 문제가 발생했습니다.");
        }
    }

    // 리사이즈와 업로드가 같은 바이트를 봐야 해서 한 번만 읽어 들고 다닌다.
    // MultipartFile의 InputStream을 두 번 여는 구현에 의존하지 않기 위해서이기도 하다.
    private byte[] readBytes(MultipartFile image) {
        try {
            return image.getBytes();
        } catch (Exception e) {
            throw new S3UploadException("S3 이미지 업로드 중 문제가 발생했습니다.");
        }
    }
}
