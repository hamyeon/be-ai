package com.vintic.backend.analyze.job;

import com.vintic.backend.analyze.job.processor.AnalysisInput;
import com.vintic.backend.analyze.job.processor.AnalysisPayload;
import com.vintic.backend.analyze.job.processor.AnalysisProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.io.UncheckedIOException;

// contracts.md §2 POST /api/analyses/sync: "Day13 동기 대 비동기 비교용, 비동기 경로와 같은
// Real/Fake Processor 사용". SqsAnalysisJobHandler와 동일하게 S3 GetObject로 읽은 실제 객체
// 내용만 AnalysisProcessor에 넘긴다(ADR-17 - Processor는 S3에 직접 접근하지 않는다).
//
// async 경로(SqsAnalysisJobHandler)와 의도적으로 다른 점: ProductAnalysisJob 생성/claim/상태
// 전이, AnalysisResult 저장, SQS 발행을 전혀 하지 않는다 - Frozen 계약에 sync의 DB/큐 동작이
// 적혀있지 않아 임의로 추가하지 않았다. 순수하게 "S3에서 읽어서 Processor에 넘기고 결과를
// 그대로 반환"만 한다. 재시도/영구 오류 분류(AnalysisTransientFailureException/
// AnalysisPermanentFailureException)도 SQS 재배달을 전제로 한 async 전용 개념이라 여기서는
// 구분하지 않고 그대로 호출자에게 전파한다 - GlobalExceptionHandler의 기존 catch-all(500)이
// 처리한다.
@Service
public class SyncAnalysisService {

    private final S3Client s3Client;
    private final AnalysisProcessor analysisProcessor;
    private final String bucket;

    public SyncAnalysisService(
            S3Client s3Client,
            AnalysisProcessor analysisProcessor,
            @Value("${cloud.aws.s3.bucket}") String bucket
    ) {
        this.s3Client = s3Client;
        this.analysisProcessor = analysisProcessor;
        this.bucket = bucket;
    }

    public AnalysisPayload analyze(String objectKey) {
        byte[] imageContent;
        try (ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build())) {
            imageContent = s3Object.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("S3 객체 본문을 읽는 중 오류가 발생했습니다. objectKey=" + objectKey, e);
        }

        // sync 요청은 job이 없어 analysisId가 없다 - AnalysisInput.analysisId()는 async 전용 식별자이므로
        // null을 넘긴다(FakeAnalysisProcessor는 null이어도 "fake-result-null"을 반환할 뿐 예외를 던지지 않는다).
        return analysisProcessor.process(new AnalysisInput(null, imageContent));
    }
}
