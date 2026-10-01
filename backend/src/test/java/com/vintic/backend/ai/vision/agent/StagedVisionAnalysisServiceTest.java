package com.vintic.backend.ai.vision.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.observability.domain.AiCallFailureType;
import com.vintic.backend.ai.observability.domain.AiCallLog;
import com.vintic.backend.ai.observability.service.AiCallLogger;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.vision.client.ChatCompletionClient;
import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import com.vintic.backend.ai.vision.client.VisionImageDetail;
import com.vintic.backend.ai.vision.client.VisionProviderProperties;
import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.dto.VisionProgress;
import com.vintic.backend.common.exception.AiApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StagedVisionAnalysisServiceTest {

    private static final List<String> IMAGE_URLS = List.of("https://example.com/a.jpg");

    @Mock
    private ChatCompletionClient visionClient;

    @Mock
    private AiCallLogger aiCallLogger;

    private StagedVisionAnalysisService newService() {
        return newService(new VisionStageProperties());
    }

    private StagedVisionAnalysisService newService(VisionStageProperties stageProperties) {
        return new StagedVisionAnalysisService(
                visionClient, new ObjectMapper(), new VisionEvidenceValidator(),
                new PromptTemplateLoader(), stageProperties, new VisionProviderProperties(), aiCallLogger,
                stageExecutor);
    }

    // 기본은 호출한 스레드에서 그대로 돌린다. 진짜 동시 실행이 필요한 테스트만 스레드 풀을 넣는다.
    private Executor stageExecutor = Runnable::run;

    private VisionStageProperties stagePropertiesWith(VisionStageProperties.ExecutionMode mode) {
        VisionStageProperties properties = new VisionStageProperties();
        properties.setExecutionMode(mode);
        return properties;
    }

    private VisionStageProperties parallelStageProperties() {
        return stagePropertiesWith(VisionStageProperties.ExecutionMode.PARALLEL_LABEL_CONDITION);
    }

    private VisionChatResponse responseOf(String content) {
        return new VisionChatResponse(content, 100, 50, 1000L);
    }

    private static final String SILHOUETTE_JSON = """
            {
              "silhouette": "high-top leather sneaker",
              "brand": "Nike",
              "modelName": "Air Force 1",
              "color": "White",
              "candidates": [],
              "evidence": [
                {"field": "brand", "imageIndex": 0, "region": "side_logo",
                 "observedText": null, "observation": "옆면에 스우시 로고가 있습니다.", "sourceChunkId": null},
                {"field": "modelName", "imageIndex": 0, "region": "overall",
                 "observedText": null, "observation": "에어포스 1 특유의 실루엣입니다.", "sourceChunkId": null},
                {"field": "color", "imageIndex": 0, "region": "upper",
                 "observedText": null, "observation": "갑피 전체가 흰색입니다.", "sourceChunkId": null}
              ],
              "unreadable": []
            }
            """;

    private static final String LABEL_JSON = """
            {
              "size": 270,
              "sizeLabelText": "US 9 / 27cm",
              "modelCode": "315122-111",
              "brand": null,
              "modelName": "Air Force 1 '07",
              "boxIncluded": null,
              "evidence": [
                {"field": "size", "imageIndex": 0, "region": "tongue_label",
                 "observedText": "US 9 / 27cm", "observation": "텅 라벨에 사이즈가 적혀 있습니다.", "sourceChunkId": null},
                {"field": "modelName", "imageIndex": 0, "region": "tongue_label",
                 "observedText": "AIR FORCE 1 '07", "observation": "텅 라벨에 모델명이 적혀 있습니다.", "sourceChunkId": null}
              ],
              "unreadable": [
                {"field": "boxIncluded", "reason": "사진에 박스가 찍혀 있지 않습니다."}
              ]
            }
            """;

    private static final String CONDITION_JSON = """
            {
              "conditionGrade": "B",
              "conditionDescription": "앞코 주름과 밑창 오염이 보입니다.",
              "defects": [
                {"type": "crease", "location": "toe_box", "severity": "moderate", "description": "앞코에 주름이 있습니다."}
              ],
              "confidence": 0.75,
              "needsUserConfirmation": false,
              "evidence": [
                {"field": "conditionGrade", "imageIndex": 0, "region": "toe_box",
                 "observedText": null, "observation": "앞코에 접힌 자국이 여러 개 보입니다.", "sourceChunkId": null},
                {"field": "defects", "imageIndex": 0, "region": "outsole",
                 "observedText": null, "observation": "밑창 바닥에 때가 껴 있습니다.", "sourceChunkId": null}
              ],
              "unreadable": []
            }
            """;

    private void stubAllStages() {
        when(visionClient.complete(any()))
                .thenReturn(responseOf(SILHOUETTE_JSON))
                .thenReturn(responseOf(LABEL_JSON))
                .thenReturn(responseOf(CONDITION_JSON));
    }

    @Test
    void 세_단계를_순서대로_호출하고_결과를_합친다() {
        stubAllStages();

        VisionAnalysisResult result = newService().analyze(new VisionAnalysisRequest(IMAGE_URLS));

        verify(visionClient, times(3)).complete(any());
        assertThat(result.brand()).isEqualTo("Nike");
        // 라벨에서 정정된 모델명이 실루엣 추정값을 이긴다
        assertThat(result.modelName()).isEqualTo("Air Force 1 '07");
        assertThat(result.color()).isEqualTo("White");
        assertThat(result.size()).isEqualTo(270);
        assertThat(result.conditionGrade()).isEqualTo(ConditionGrade.B);
        assertThat(result.defects()).hasSize(1);
        assertThat(result.evidence()).hasSize(7);
    }

    @Test
    void 기본_설정은_라벨_판독_단계부터_해상도를_올린다() {
        stubAllStages();

        newService().analyze(new VisionAnalysisRequest(IMAGE_URLS));

        List<VisionChatRequest> requests = capturedRequests();
        assertThat(requests.get(0).detail()).isEqualTo(VisionImageDetail.LOW);   // 실루엣은 저해상도로 충분
        assertThat(requests.get(1).detail()).isEqualTo(VisionImageDetail.HIGH);  // 라벨 글자 판독
        assertThat(requests.get(2).detail()).isEqualTo(VisionImageDetail.HIGH);  // 오염/마모 확인
    }

    @Test
    void 단계별_해상도와_응답_한도를_설정으로_바꿀_수_있다() {
        // detail은 정확도와 비용을 맞바꾸는 값이라 재배포 없이 조정할 수 있어야 하고,
        // high가 값을 하는지 비교 측정하려면 밖에서 바꿀 수 있어야 한다.
        VisionStageProperties properties = new VisionStageProperties();
        properties.getLabel().setDetail(VisionImageDetail.LOW);
        properties.getCondition().setDetail(VisionImageDetail.LOW);
        properties.getCondition().setMaxOutputTokens(2000);
        stubAllStages();

        newService(properties).analyze(new VisionAnalysisRequest(IMAGE_URLS));

        List<VisionChatRequest> requests = capturedRequests();
        assertThat(requests).allSatisfy(request ->
                assertThat(request.detail()).isEqualTo(VisionImageDetail.LOW));
        assertThat(requests.get(2).maxOutputTokens()).isEqualTo(2000);
    }

    @Test
    void 모든_단계가_응답_스키마를_고정한다() {
        stubAllStages();

        newService().analyze(new VisionAnalysisRequest(IMAGE_URLS));

        assertThat(capturedRequests()).allSatisfy(request -> {
            assertThat(request.responseSchema()).isNotNull();
            assertThat(request.responseSchema().name()).matches("[a-zA-Z0-9_-]+");
            assertThat(request.responseSchema().schemaJson()).contains("additionalProperties");
        });
    }

    @Test
    void 앞_단계_결과를_뒤_단계_입력으로_넘긴다() {
        stubAllStages();

        newService().analyze(new VisionAnalysisRequest(IMAGE_URLS));

        List<VisionChatRequest> requests = capturedRequests();
        assertThat(requests.get(0).userText()).isNull();
        assertThat(requests.get(1).userText()).contains("Air Force 1").contains("1단계");
        assertThat(requests.get(2).userText()).contains("1단계").contains("2단계").contains("US 9 / 27cm");
    }

    @Test
    void 라벨을_읽지_못하면_사이즈를_비운다() {
        String labelWithoutSize = """
                {
                  "size": null, "sizeLabelText": null, "modelCode": null,
                  "brand": null, "modelName": null, "boxIncluded": null,
                  "evidence": [],
                  "unreadable": [{"field": "size", "reason": "사이즈 라벨이 사진에 없습니다."}]
                }
                """;
        when(visionClient.complete(any()))
                .thenReturn(responseOf(SILHOUETTE_JSON))
                .thenReturn(responseOf(labelWithoutSize))
                .thenReturn(responseOf(CONDITION_JSON));

        VisionAnalysisResult result = newService().analyze(new VisionAnalysisRequest(IMAGE_URLS));

        assertThat(result.size()).isNull();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("사이즈 라벨이 사진에 없습니다."));
        assertThat(result.brand()).isEqualTo("Nike");  // 다른 단계 결과는 영향받지 않는다
    }

    @Test
    void 근거_없이_채워진_값은_저장_전에_제거된다() {
        // 스키마는 통과했지만 evidence를 비운 채 값만 채워 보낸 응답
        String silhouetteWithoutEvidence = """
                {
                  "silhouette": "sneaker", "brand": "Nike", "modelName": "Air Force 1", "color": "White",
                  "candidates": [], "evidence": [], "unreadable": []
                }
                """;
        when(visionClient.complete(any()))
                .thenReturn(responseOf(silhouetteWithoutEvidence))
                .thenReturn(responseOf(LABEL_JSON))
                .thenReturn(responseOf(CONDITION_JSON));

        VisionAnalysisResult result = newService().analyze(new VisionAnalysisRequest(IMAGE_URLS));

        assertThat(result.brand()).isNull();
        assertThat(result.color()).isNull();
        assertThat(result.needsUserConfirmation()).isTrue();
        // 2단계가 근거와 함께 읽어낸 값은 남는다
        assertThat(result.modelName()).isEqualTo("Air Force 1 '07");
        assertThat(result.size()).isEqualTo(270);
    }

    @Test
    void 단계가_끝날_때마다_검증된_잠정_결과를_알린다() {
        // #106: 1단계 후에는 실루엣 추정, 2단계 후에는 라벨로 보정한 값과 사이즈. 3단계 후에는 알리지 않는다(최종 결과가 대신한다).
        stubAllStages();
        List<VisionProgress> progress = new ArrayList<>();

        newService().analyze(new VisionAnalysisRequest(IMAGE_URLS), progress::add);

        assertThat(progress).containsExactly(
                new VisionProgress(1, 3, "Nike", "Air Force 1", "White", null),
                new VisionProgress(2, 3, "Nike", "Air Force 1 '07", "White", 270));
    }

    @Test
    void 잠정_결과도_근거_없는_값은_지운_뒤에_알린다() {
        // 나중에 검증기에서 사라질 브랜드를 사용자가 먼저 보면 안 된다.
        String silhouetteWithoutEvidence = """
                {
                  "silhouette": "sneaker", "brand": "Nike", "modelName": "Air Force 1", "color": "White",
                  "candidates": [], "evidence": [], "unreadable": []
                }
                """;
        when(visionClient.complete(any()))
                .thenReturn(responseOf(silhouetteWithoutEvidence))
                .thenReturn(responseOf(LABEL_JSON))
                .thenReturn(responseOf(CONDITION_JSON));
        List<VisionProgress> progress = new ArrayList<>();

        newService().analyze(new VisionAnalysisRequest(IMAGE_URLS), progress::add);

        assertThat(progress.get(0)).isEqualTo(new VisionProgress(1, 3, null, null, null, null));
    }

    @Test
    void 진행_알림이_실패해도_분석은_끝까지_간다() {
        stubAllStages();

        VisionAnalysisResult result = newService().analyze(new VisionAnalysisRequest(IMAGE_URLS), progress -> {
            throw new RuntimeException("진행 기록 저장 실패");
        });

        verify(visionClient, times(3)).complete(any());
        assertThat(result.conditionGrade()).isEqualTo(ConditionGrade.B);
    }

    @Test
    void 프롬프트_버전을_바꾸면_그_버전의_프롬프트와_스키마로_부른다() {
        // #106: 출력을 줄인 v3를 v2와 같은 코드로 하네스에서 비교하려면 설정만으로 바꿀 수 있어야 한다.
        VisionProviderProperties providerProperties = new VisionProviderProperties();
        providerProperties.setPromptVersion("v3");
        stubAllStages();

        new StagedVisionAnalysisService(
                visionClient, new ObjectMapper(), new VisionEvidenceValidator(), new PromptTemplateLoader(),
                new VisionStageProperties(), providerProperties, aiCallLogger, stageExecutor)
                .analyze(new VisionAnalysisRequest(IMAGE_URLS));

        List<VisionChatRequest> requests = capturedRequests();
        assertThat(requests).extracting(request -> request.responseSchema().name())
                .containsExactly("vision_silhouette_v3", "vision_label_v3", "vision_condition_v3");
        assertThat(requests.get(0).systemPrompt()).contains("Keep the output short");
        assertThat(capturedLogs(3)).allSatisfy(log -> assertThat(log.getPromptVersion()).isEqualTo("v3"));
    }

    @Test
    void 동시_실행을_켜면_3단계가_라벨_결과를_기다리지_않는다() {
        // #106: 2·3단계를 겹쳐 분석 한 건을 2단계 시간만큼 줄인다. 대신 3단계 맥락에 라벨 결과가 없다.
        stubAllStages();

        VisionAnalysisResult result = newService(parallelStageProperties()).analyze(new VisionAnalysisRequest(IMAGE_URLS));

        List<VisionChatRequest> requests = capturedRequests();
        assertThat(requests.get(2).userText())
                .contains("1단계(전체 형태) 결과")
                .doesNotContain("2단계(라벨/로고) 결과");
        // 합쳐진 결과는 순차 실행과 같아야 한다
        assertThat(result.modelName()).isEqualTo("Air Force 1 '07");
        assertThat(result.size()).isEqualTo(270);
        assertThat(result.conditionGrade()).isEqualTo(ConditionGrade.B);
    }

    @Test
    void 세_단계를_모두_동시에_돌리면_앞_단계_결과가_없다고_알리고_결과는_같게_합친다() {
        // #106: 분석 시간이 세 단계의 합이 아니라 가장 느린 한 단계가 된다. 요청마다 단계가 다른 스키마를 쓰므로
        // 호출 순서가 아니라 스키마 이름으로 응답을 골라 준다.
        when(visionClient.complete(any())).thenAnswer(invocation -> {
            VisionChatRequest request = invocation.getArgument(0);
            return switch (request.responseSchema().name()) {
                case "vision_silhouette_v2" -> responseOf(SILHOUETTE_JSON);
                case "vision_label_v2" -> responseOf(LABEL_JSON);
                default -> responseOf(CONDITION_JSON);
            };
        });

        VisionAnalysisResult result = newService(stagePropertiesWith(VisionStageProperties.ExecutionMode.ALL_PARALLEL))
                .analyze(new VisionAnalysisRequest(IMAGE_URLS));

        List<VisionChatRequest> requests = capturedRequests();
        assertThat(requests).hasSize(3);
        assertThat(requests).filteredOn(request -> !request.responseSchema().name().contains("silhouette"))
                .allSatisfy(request -> assertThat(request.userText())
                        .contains("없음")
                        .doesNotContain("1단계(전체 형태) 결과"));
        assertThat(result.brand()).isEqualTo("Nike");
        assertThat(result.modelName()).isEqualTo("Air Force 1 '07");
        assertThat(result.size()).isEqualTo(270);
        assertThat(result.conditionGrade()).isEqualTo(ConditionGrade.B);
    }

    @Test
    void 동시_실행에서도_진행_알림은_뒤로_가지_않는다() {
        // 라벨이 1단계보다 먼저 끝나도 화면이 2단계 -> 1단계로 되돌아가면 안 된다.
        stageExecutor = java.util.concurrent.Executors.newFixedThreadPool(3);
        when(visionClient.complete(any())).thenAnswer(invocation -> {
            VisionChatRequest request = invocation.getArgument(0);
            return switch (request.responseSchema().name()) {
                case "vision_silhouette_v2" -> {
                    Thread.sleep(100); // 1단계가 가장 늦게 끝나게 한다
                    yield responseOf(SILHOUETTE_JSON);
                }
                case "vision_label_v2" -> responseOf(LABEL_JSON);
                default -> responseOf(CONDITION_JSON);
            };
        });
        List<VisionProgress> progress = java.util.Collections.synchronizedList(new ArrayList<>());

        newService(stagePropertiesWith(VisionStageProperties.ExecutionMode.ALL_PARALLEL))
                .analyze(new VisionAnalysisRequest(IMAGE_URLS), progress::add);

        assertThat(progress).extracting(VisionProgress::completedStages).isSorted();
        assertThat(progress).extracting(VisionProgress::completedStages).doesNotHaveDuplicates();
    }

    @Test
    void 동시_실행_중_한_단계가_실패해도_다른_단계를_끝까지_기다린_뒤_예외를_던진다() {
        // 먼저 끝난 쪽에서 바로 던지면 남은 호출이 비용만 쓰고 기록도 안 남는다.
        stageExecutor = java.util.concurrent.Executors.newFixedThreadPool(2);
        when(visionClient.complete(any()))
                .thenReturn(responseOf(SILHOUETTE_JSON))
                .thenThrow(new AiApiException("OpenAI Vision API 오류 (status=429)"))
                .thenReturn(responseOf(CONDITION_JSON));

        assertThatThrownBy(() -> newService(parallelStageProperties()).analyze(new VisionAnalysisRequest(IMAGE_URLS)))
                .isInstanceOf(AiApiException.class);

        verify(visionClient, times(3)).complete(any());
    }

    @Test
    void 중간_단계에서_실패하면_예외가_전파된다() {
        when(visionClient.complete(any()))
                .thenReturn(responseOf(SILHOUETTE_JSON))
                .thenThrow(new AiApiException("OpenAI Vision API 오류 (status=429)"));

        assertThatThrownBy(() -> newService().analyze(new VisionAnalysisRequest(IMAGE_URLS)))
                .isInstanceOf(AiApiException.class);
    }

    @Test
    void 응답이_스키마와_맞지_않으면_AiApiException으로_바꾼다() {
        when(visionClient.complete(any())).thenReturn(responseOf("이건 JSON이 아닙니다"));

        assertThatThrownBy(() -> newService().analyze(new VisionAnalysisRequest(IMAGE_URLS)))
                .isInstanceOf(AiApiException.class);
    }

    @Test
    void 세_단계를_각각_별도_기록으로_남긴다() {
        // 분석 한 건에 호출이 세 번이라, 어느 단계가 느렸는지 보려면 단계별로 남아야 한다.
        stubAllStages();

        newService().analyze(new VisionAnalysisRequest(IMAGE_URLS, 42L));

        List<AiCallLog> logs = capturedLogs(3);
        assertThat(logs).extracting(AiCallLog::getStage)
                .containsExactly("silhouette", "label", "condition");
        assertThat(logs).allSatisfy(log -> {
            assertThat(log.isSuccess()).isTrue();
            assertThat(log.getAnalysisId()).isEqualTo(42L);
            assertThat(log.getPromptVersion()).isEqualTo("v2");
            assertThat(log.getModelName()).isEqualTo("gpt-4o");
            assertThat(log.getPromptTokens()).isEqualTo(100);
            assertThat(log.getCompletionTokens()).isEqualTo(50);
            assertThat(log.getLatencyMs()).isEqualTo(1000L);
        });
    }

    @Test
    void API_호출이_실패하면_실패_기록을_남긴다() {
        when(visionClient.complete(any()))
                .thenReturn(responseOf(SILHOUETTE_JSON))
                .thenThrow(new AiApiException("OpenAI Vision API 오류 (status=429)"));

        assertThatThrownBy(() -> newService().analyze(new VisionAnalysisRequest(IMAGE_URLS)))
                .isInstanceOf(AiApiException.class);

        AiCallLog failed = capturedLogs(2).get(1);
        assertThat(failed.getStage()).isEqualTo("label");
        assertThat(failed.isSuccess()).isFalse();
        assertThat(failed.getFailureType()).isEqualTo(AiCallFailureType.API_ERROR);
    }

    @Test
    void 파싱에_실패하면_응답_원문과_함께_기록한다() {
        // 파싱이 깨진 응답일수록 원문이 필요하다. 무엇이 왔는지 봐야 스키마를 고칠 수 있다.
        when(visionClient.complete(any())).thenReturn(responseOf("이건 JSON이 아닙니다"));

        assertThatThrownBy(() -> newService().analyze(new VisionAnalysisRequest(IMAGE_URLS)))
                .isInstanceOf(AiApiException.class);

        AiCallLog failed = capturedLogs(1).get(0);
        assertThat(failed.getFailureType()).isEqualTo(AiCallFailureType.PARSE_ERROR);
        assertThat(failed.getResponseBody()).isEqualTo("이건 JSON이 아닙니다");
    }

    // "기록 실패가 분석을 막지 않는다"는 AiCallLoggerTest에서 검증한다.
    // record()가 어떤 경우에도 예외를 던지지 않는 게 AiCallLogger의 계약이라,
    // 호출부마다 방어 코드를 두는 대신 계약을 지키는 쪽을 테스트한다.

    private List<AiCallLog> capturedLogs(int expectedCount) {
        ArgumentCaptor<AiCallLog> captor = ArgumentCaptor.forClass(AiCallLog.class);
        verify(aiCallLogger, times(expectedCount)).record(captor.capture());
        return captor.getAllValues();
    }

    private List<VisionChatRequest> capturedRequests() {
        ArgumentCaptor<VisionChatRequest> captor = ArgumentCaptor.forClass(VisionChatRequest.class);
        verify(visionClient, times(3)).complete(captor.capture());
        return captor.getAllValues();
    }
}
