package com.vintic.backend.ai.vision.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.vision.client.OpenAiVisionClient;
import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import com.vintic.backend.ai.vision.client.VisionImageDetail;
import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.common.exception.AiApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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
    private OpenAiVisionClient visionClient;

    private StagedVisionAnalysisService newService() {
        return new StagedVisionAnalysisService(
                visionClient, new ObjectMapper(), new VisionEvidenceValidator(), new PromptTemplateLoader());
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
    void 단계별로_필요한_만큼만_해상도를_올린다() {
        stubAllStages();

        newService().analyze(new VisionAnalysisRequest(IMAGE_URLS));

        List<VisionChatRequest> requests = capturedRequests();
        assertThat(requests.get(0).detail()).isEqualTo(VisionImageDetail.LOW);   // 실루엣은 저해상도로 충분
        assertThat(requests.get(1).detail()).isEqualTo(VisionImageDetail.HIGH);  // 라벨 글자 판독
        assertThat(requests.get(2).detail()).isEqualTo(VisionImageDetail.HIGH);  // 오염/마모 확인
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

    private List<VisionChatRequest> capturedRequests() {
        ArgumentCaptor<VisionChatRequest> captor = ArgumentCaptor.forClass(VisionChatRequest.class);
        verify(visionClient, times(3)).complete(captor.capture());
        return captor.getAllValues();
    }
}
