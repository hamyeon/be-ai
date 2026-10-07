package com.vintic.backend.ai.vision.agent;

import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.dto.VisionEvidence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// 근거가 없는 값을 결과에서 떨어뜨린다.
//
// "근거를 대라"를 프롬프트로만 지시하면 지켜질 때도 있고 아닐 때도 있다. 그래서 응답을 받은 뒤
// 코드에서 한 번 더 거른다. 근거가 없는 필드는 값을 지우고 사유를 warnings에 남긴 뒤
// needsUserConfirmation을 켜서, 사용자가 직접 확인하는 흐름으로 넘긴다. warnings는 화면에 그대로
// 노출되므로 사용자에게 할 말로 쓴다.
//
// 값을 지우는 게 손해처럼 보일 수 있지만, 확인되지 않은 사이즈를 그대로 넘겨 가격까지 계산되는 것보다
// 비워두고 물어보는 쪽이 낫다.
@Component
@Slf4j
public class VisionEvidenceValidator {

    public VisionAnalysisResult enforce(VisionAnalysisResult result, int imageCount) {
        List<VisionEvidence> evidence = usable(result.evidence(), imageCount);
        List<String> warnings = new ArrayList<>(result.warnings() == null ? List.of() : result.warnings());

        String brand = result.brand();
        if (brand != null && !hasEvidence(evidence, "brand")) {
            warnings.add("브랜드를 사진에서 확인하지 못했어요. 직접 입력해 주세요.");
            brand = null;
        }

        String modelName = result.modelName();
        if (modelName != null && !hasEvidence(evidence, "modelName")) {
            warnings.add("모델명을 사진에서 확인하지 못했어요. 직접 입력해 주세요.");
            modelName = null;
        }

        String color = result.color();
        if (color != null && !hasEvidence(evidence, "color")) {
            warnings.add("색상을 사진에서 확인하지 못했어요. 직접 입력해 주세요.");
            color = null;
        }

        // 사이즈는 근거가 있는 것만으로 부족하고, 라벨에서 실제로 읽어낸 글자가 있어야 한다.
        // 사진에는 크기 기준이 없어서 눈대중 추정은 원리적으로 불가능하기 때문이다.
        Integer size = result.size();
        if (size != null && !hasReadTextEvidence(evidence, "size")) {
            warnings.add("사이즈 표기를 읽지 못했어요. 라벨이나 밑창 사진을 추가하거나 직접 입력해 주세요.");
            size = null;
        }

        Boolean boxIncluded = result.boxIncluded();
        if (boxIncluded != null && !hasEvidence(evidence, "boxIncluded")) {
            warnings.add("박스 포함 여부를 사진에서 확인하지 못했어요. 직접 선택해 주세요.");
            boxIncluded = null;
        }

        ConditionGrade conditionGrade = result.conditionGrade();
        if (conditionGrade != null && conditionGrade != ConditionGrade.UNKNOWN
                && !hasEvidence(evidence, "conditionGrade")) {
            warnings.add("상품 상태 등급을 사진에서 판단하지 못했어요. 직접 선택해 주세요.");
            conditionGrade = ConditionGrade.UNKNOWN;
        }

        // warnings에는 앞 단계가 넘긴 "라벨이 안 보임" 사유도 섞여 있으므로,
        // 이번에 새로 붙은 것만 세야 실제로 제거된 필드 수가 나온다.
        int carriedOverWarnings = result.warnings() == null ? 0 : result.warnings().size();
        int droppedFieldCount = warnings.size() - carriedOverWarnings;
        boolean droppedSomething = droppedFieldCount > 0;
        if (droppedSomething) {
            log.warn("근거가 없어 제거된 Vision 필드가 있습니다. 제거된 필드 수={}", droppedFieldCount);
        }

        // 모델의 자기 판단(3단계 needsUserConfirmation)만 믿으면 안 된다. 단계를 동시에 돌리면 3단계는
        // 2단계가 사이즈를 못 읽은 걸 모른 채 false를 낼 수 있다. 처음부터 null로 온 값은 위에서 "지운" 것으로
        // 세지 않으므로, 가격 계산에 필요한 값이 비었거나 경고가 하나라도 있으면 여기서 켠다.
        boolean missingRequiredField = brand == null || modelName == null || color == null || size == null
                || conditionGrade == null || conditionGrade == ConditionGrade.UNKNOWN;
        boolean needsUserConfirmation = Boolean.TRUE.equals(result.needsUserConfirmation()) || droppedSomething
                || missingRequiredField || !warnings.isEmpty();

        return new VisionAnalysisResult(
                brand, modelName, color, size,
                result.conditionDescription(), conditionGrade, boxIncluded,
                result.confidence(), needsUserConfirmation,
                List.copyOf(warnings), result.candidates(), result.defects(), evidence
        );
    }

    // 형식이 깨진 근거는 근거로 치지 않는다.
    // 특히 imageIndex가 실제 이미지 개수를 벗어나면 있지도 않은 사진을 가리키는 것이므로 버린다.
    private List<VisionEvidence> usable(List<VisionEvidence> evidence, int imageCount) {
        if (evidence == null) {
            return List.of();
        }
        return evidence.stream()
                .filter(item -> item != null && item.field() != null && !item.field().isBlank())
                .filter(item -> item.imageIndex() != null && item.imageIndex() >= 0 && item.imageIndex() < imageCount)
                .filter(item -> item.observation() != null && !item.observation().isBlank())
                .toList();
    }

    private boolean hasEvidence(List<VisionEvidence> evidence, String field) {
        return evidence.stream().anyMatch(item -> field.equals(item.field()));
    }

    private boolean hasReadTextEvidence(List<VisionEvidence> evidence, String field) {
        return evidence.stream()
                .filter(item -> field.equals(item.field()))
                .anyMatch(item -> item.observedText() != null && !item.observedText().isBlank());
    }
}
