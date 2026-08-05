package com.vintic.backend.ai.vision.agent;

import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.dto.VisionEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisionEvidenceValidatorTest {

    private final VisionEvidenceValidator sut = new VisionEvidenceValidator();

    private VisionEvidence evidence(String field, int imageIndex, String observedText) {
        return new VisionEvidence(field, imageIndex, "tongue_label", observedText, "라벨이 보입니다.", null);
    }

    private VisionAnalysisResult resultWith(List<VisionEvidence> evidence) {
        return new VisionAnalysisResult(
                "Nike", "Air Force 1", "White", 270, "설명", ConditionGrade.B, true,
                0.8, false, List.of(), List.of(), List.of(), evidence);
    }

    @Test
    void 근거가_있는_필드는_그대로_둔다() {
        VisionAnalysisResult result = sut.enforce(resultWith(List.of(
                evidence("brand", 0, null),
                evidence("modelName", 0, null),
                evidence("color", 0, null),
                evidence("size", 0, "270"),
                evidence("boxIncluded", 0, null),
                evidence("conditionGrade", 0, null)
        )), 1);

        assertThat(result.brand()).isEqualTo("Nike");
        assertThat(result.size()).isEqualTo(270);
        assertThat(result.boxIncluded()).isTrue();
        assertThat(result.conditionGrade()).isEqualTo(ConditionGrade.B);
        assertThat(result.warnings()).isEmpty();
        assertThat(result.needsUserConfirmation()).isFalse();
    }

    @Test
    void 근거가_없는_필드는_값을_비우고_사용자_확인이_필요하다고_표시한다() {
        VisionAnalysisResult result = sut.enforce(resultWith(List.of()), 1);

        assertThat(result.brand()).isNull();
        assertThat(result.modelName()).isNull();
        assertThat(result.color()).isNull();
        assertThat(result.size()).isNull();
        assertThat(result.boxIncluded()).isNull();
        assertThat(result.conditionGrade()).isEqualTo(ConditionGrade.UNKNOWN);
        assertThat(result.warnings()).isNotEmpty();
        assertThat(result.needsUserConfirmation()).isTrue();
    }

    @Test
    void 사이즈는_읽어낸_글자가_없으면_근거로_인정하지_않는다() {
        // 라벨을 읽은 게 아니라 "신발이 커 보인다" 식으로 근거를 붙인 경우
        VisionAnalysisResult result = sut.enforce(resultWith(List.of(evidence("size", 0, null))), 1);

        assertThat(result.size()).isNull();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("사이즈"));
    }

    @Test
    void 있지도_않은_이미지를_가리키는_근거는_버린다() {
        // 이미지가 1장뿐인데 두 번째 이미지를 근거로 대는 건 지어낸 인용이다
        VisionAnalysisResult result = sut.enforce(resultWith(List.of(evidence("brand", 1, null))), 1);

        assertThat(result.brand()).isNull();
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void 이미지가_여러_장이면_뒤쪽_이미지를_가리키는_근거도_인정한다() {
        VisionAnalysisResult result = sut.enforce(resultWith(List.of(evidence("brand", 2, null))), 3);

        assertThat(result.brand()).isEqualTo("Nike");
        assertThat(result.evidence()).hasSize(1);
    }

    @Test
    void 이미_UNKNOWN인_등급은_근거가_없어도_경고를_더_붙이지_않는다() {
        VisionAnalysisResult unknownGrade = new VisionAnalysisResult(
                null, null, null, null, "판단 불가", ConditionGrade.UNKNOWN, null,
                0.1, true, List.of(), List.of(), List.of(), List.of());

        VisionAnalysisResult result = sut.enforce(unknownGrade, 1);

        assertThat(result.warnings()).isEmpty();
        assertThat(result.conditionGrade()).isEqualTo(ConditionGrade.UNKNOWN);
    }

    @Test
    void 원래_있던_경고는_유지한다() {
        VisionAnalysisResult withWarning = new VisionAnalysisResult(
                "Nike", null, null, null, null, ConditionGrade.UNKNOWN, null,
                null, false, List.of("3단계 outsole: 밑창 사진이 없습니다."), List.of(), List.of(), List.of());

        VisionAnalysisResult result = sut.enforce(withWarning, 1);

        assertThat(result.warnings()).contains("3단계 outsole: 밑창 사진이 없습니다.");
        assertThat(result.brand()).isNull();
        assertThat(result.warnings()).hasSize(2);
    }
}
