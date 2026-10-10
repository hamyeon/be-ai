package com.vintic.backend.analyze.domain;

import com.vintic.backend.common.exception.InvalidAnalysisStatusException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 상품 등록 전 Vision -> (사용자 확인) -> Pricing으로 이어지는 AI 분석 진행 상태를 추적하는 세션.
// Vision/Pricing 각 단계의 성공/실패 결과를 저장해 장애 추적과 두 API 요청 간 연결(analysisId)을 가능하게 한다.
@Entity
@Table(name = "product_analysis_session")
@Getter
public class ProductAnalysisSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 분석을 요청한 사용자(#analyze가 인증 필수로 바뀌면서 추가) - getStatus() 조회 시
    // 소유자 검증에 쓴다(ProductAnalyzeService 참고). 기존 행(익명 시절 생성된 세션)은 null일
    // 수 있어 nullable로 둔다 - 신규 시연 환경은 빈 DB에서 시작하므로 실질적으로는 항상 채워진다.
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    private AnalysisStatus status;

    @ElementCollection
    @CollectionTable(
            name = "product_analysis_session_image_urls",
            joinColumns = @JoinColumn(name = "session_id")
    )
    @Column(name = "image_url", length = 1000)
    private List<String> imageUrls = new ArrayList<>();

    @Lob
    @Column(name = "vision_result_json", columnDefinition = "LONGTEXT")
    private String visionResultJson;

    // #106: 3단계 분석 도중의 잠정 결과(VisionProgress). VISION_PROCESSING 동안만 채워지고 끝나면 비운다.
    @Lob
    @Column(name = "vision_progress_json", columnDefinition = "LONGTEXT")
    private String visionProgressJson;

    // 판매자가 Vision 결과를 확인/수정해 Pricing 요청에 실제로 전달한 최종 입력값
    @Lob
    @Column(name = "confirmed_input_json", columnDefinition = "LONGTEXT")
    private String confirmedInputJson;

    @Lob
    @Column(name = "pricing_result_json", columnDefinition = "LONGTEXT")
    private String pricingResultJson;

    @Enumerated(EnumType.STRING)
    private AnalysisFailureStage failureStage;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

    // 현재 Vision 처리 시도를 식별하는 fencing token. claim/reclaimVisionProcessing()에서 새로
    // 발급하고, completeVision/failVision()이 이 값과 호출자가 들고 있는 token이 같은지 확인한다.
    // 다른 시도(재선점)가 이미 이 값을 바꿔놨다면 이전 시도의 결과는 반영되지 않는다 - 기존 행에는
    // NULL이 들어가는데, 이는 "현재 이 세션을 소유한 시도가 없다"는 의미라 항상 안전한 기본값이다.
    @Column(name = "vision_processing_token", length = 64)
    private String visionProcessingToken;

    // VISION_FAILED가 실패 전용 Redis Stream에 발행됐는지. NOT NULL 컬럼을 기존 행이 있는
    // 테이블에 ddl-auto:update로 추가하려면 DEFAULT가 있어야 한다(Auction.extensionCount와
    // 동일 원칙) - DEFAULT 0(false)는 기존 행(이 컬럼이 없던 시절 실패한 세션, 애초에 발행
    // 대상이 아니었음)에도 안전하고, 새로 실패하는 세션도 항상 false로 시작해 정확히 한 번
    // (또는 재시도 구간에서 드물게 두 번, at-least-once)만 발행된다. VisionFailureStreamRecorder 참고.
    @Column(name = "vision_failure_stream_published", columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean visionFailureStreamPublished;

    // 진짜로 Vision을 호출했다가 재시도 가치가 있는 이유로 실패한 횟수. Redis가 추적하는 배달
    // 횟수(PendingMessage.getTotalDeliveryCount())는 executor 포화처럼 Vision을 아예 시도조차
    // 못한 재전달도 함께 세기 때문에, "분석 자체가 반복 실패했다"를 판단하는 근거로 쓸 수 없다 -
    // 이 컬럼이 그 용도의 최소한의 별도 카운트다(AnalysisTaskConsumer.handleVisionFailure 참고).
    // reclaim으로 다른 Worker가 이어받아도 리셋하지 않는다 - "이 세션을 몇 번이나 실제로
    // 시도했는지"를 세는 것이라 어느 Worker가 시도했는지와는 무관하다.
    @Column(name = "vision_failure_attempt_count", columnDefinition = "INT NOT NULL DEFAULT 0")
    private int visionFailureAttemptCount;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    // #127: 사용자가 취소한 시각. null이면 취소되지 않은 세션이다.
    private LocalDateTime cancelledAt;

    // #127: 이 세션이 상품 등록(ProductRegistrationService.createProduct)에 "확정 사용"된 시각.
    // AnalysisStatus.COMPLETED(Pricing 완료)와는 별개 개념이다 - COMPLETED 이후에도 사용자가
    // 등록을 미루거나 포기할 수 있고, 그동안은 여전히 취소가 가능해야 하기 때문이다. null이면
    // 아직 어떤 상품 등록에도 쓰이지 않았다는 뜻이고, 세션당 최대 한 번만 채워진다(같은 세션으로
    // 상품을 두 번 등록할 수 없다). cancel()과 confirmRegistration()이 같은 행 잠금
    // (ProductAnalysisSessionRepository.findByIdForUpdate) 안에서 이 필드와 status를 함께 확인해
    // 취소 확정과 등록 확정 중 먼저 커밋된 쪽이 이긴다.
    private LocalDateTime registeredAt;

    protected ProductAnalysisSession() {
    }

    public static ProductAnalysisSession create(Long userId) {
        ProductAnalysisSession session = new ProductAnalysisSession();
        session.userId = userId;
        session.status = AnalysisStatus.CREATED;
        session.startedAt = LocalDateTime.now();
        return session;
    }

    // 조회자가 이 세션의 생성자인지 확인한다. 타인의 taskId로 분석 결과(brand/imageUrls 등)를
    // 볼 수 없게 하는 것이 목적이다 - ProductAnalyzeService.getStatus()가 호출한다.
    public boolean isOwnedBy(Long userId) {
        return Objects.equals(this.userId, userId);
    }

    public void markImageUploaded(List<String> imageUrls) {
        this.imageUrls = new ArrayList<>(imageUrls);
        this.status = AnalysisStatus.IMAGE_UPLOADED;
    }

    public void failImageUpload(String message) {
        this.status = AnalysisStatus.IMAGE_UPLOAD_FAILED;
        this.failureStage = AnalysisFailureStage.IMAGE_UPLOAD;
        this.failureMessage = message;
    }

    public void markQueued() {
        this.status = AnalysisStatus.QUEUED;
    }

    public void failQueueing(String message) {
        this.status = AnalysisStatus.QUEUE_FAILED;
        this.failureStage = AnalysisFailureStage.QUEUE;
        this.failureMessage = message;
    }

    // Consumer가 새로 배달된(재전달이 아닌) 큐 메시지를 처리할 때 호출한다. QUEUED 상태가 아니면
    // 이미 처리됐거나(중복 전달) 아직 큐 적재 전인 것이므로, 같은 Vision 분석이 두 번 실행되지
    // 않도록 여기서 막는다. token은 이번 처리 시도의 fencing token으로, completeVision/failVision
    // 이 나중에 이 값과 일치하는지 확인한다.
    public void claimVisionProcessing(String token) {
        if (status != AnalysisStatus.QUEUED) {
            throw new InvalidAnalysisStatusException(
                    "Vision 분석을 시작할 수 없는 분석 상태입니다. 현재 상태: " + status
            );
        }
        this.status = AnalysisStatus.VISION_PROCESSING;
        this.visionProcessingToken = token;
    }

    // 분석 도중 끝난 단계까지의 잠정 결과를 남긴다(#106). 분석 중일 때만 받는다 - 늦게 도착한 진행 기록이
    // 이미 끝났거나 실패로 정리된 세션에 섞이면 안 된다. 받았으면 true.
    public boolean recordVisionProgress(String visionProgressJson) {
        if (status != AnalysisStatus.VISION_PROCESSING) {
            return false;
        }
        this.visionProgressJson = visionProgressJson;
        return true;
    }

    // PEL(pending entries list) 회수 전용 진입점. claimVisionProcessing()과 달리 QUEUED뿐 아니라
    // 이미 VISION_PROCESSING인 세션도 재선점 대상으로 허용한다 - 이전 Worker가 죽었을 수 있는
    // 세션을 새 Worker가 넘겨받는 경로이기 때문이다. token을 새로 발급해 이전 시도의 token을
    // 무효화하므로, 이전 Worker가 뒤늦게 완료를 시도해도 완료 시점의 token 비교에서 막힌다.
    public void reclaimVisionProcessing(String token) {
        if (status != AnalysisStatus.QUEUED && status != AnalysisStatus.VISION_PROCESSING) {
            throw new InvalidAnalysisStatusException(
                    "회수할 수 없는 분석 상태입니다. 현재 상태: " + status
            );
        }
        this.status = AnalysisStatus.VISION_PROCESSING;
        this.visionProcessingToken = token;
    }

    public void completeVision(String token, String visionResultJson) {
        requireOwnedVisionProcessing(token);
        this.visionResultJson = visionResultJson;
        this.visionProgressJson = null; // 최종 결과가 생기면 잠정 결과는 의미가 없다
        this.status = AnalysisStatus.AWAITING_USER_CONFIRMATION;
        this.visionProcessingToken = null;
    }

    public void failVision(String token, String message) {
        requireOwnedVisionProcessing(token);
        this.status = AnalysisStatus.VISION_FAILED;
        this.visionProgressJson = null;
        this.failureStage = AnalysisFailureStage.VISION;
        this.failureMessage = message;
        this.visionProcessingToken = null;
    }

    // 실패 Stream 발행에 성공한 뒤에만 호출한다(VisionFailureStreamRecorder 참고). VISION_FAILED가
    // 아닌 세션에 호출하는 것은 호출부 버그이므로 방어적으로 막는다.
    public void markVisionFailureStreamPublished() {
        if (status != AnalysisStatus.VISION_FAILED) {
            throw new InvalidAnalysisStatusException(
                    "VISION_FAILED가 아닌 세션은 실패 Stream 발행 여부를 표시할 수 없습니다. 현재 상태: " + status
            );
        }
        this.visionFailureStreamPublished = true;
    }

    // 재시도 가치가 있는 실제 Vision 실패를 셀 때 호출한다(AnalysisTaskConsumer 참고). 소유권
    // 검사는 completeVision/failVision과 동일한 기준(VISION_PROCESSING + token 일치)을 쓴다 -
    // 이미 재선점된 뒤의 늦은 시도가 카운트를 잘못 올리는 것을 막는다.
    public void incrementVisionFailureAttemptCount(String token) {
        requireOwnedVisionProcessing(token);
        this.visionFailureAttemptCount++;
    }

    // completeVision/failVision의 공통 가드. 지금 VISION_PROCESSING이고 호출자가 들고 있는
    // token이 이 세션을 현재 소유한 시도의 token과 같아야 한다 - 다르면 다른 시도(재선점)가
    // 이미 소유권을 가져갔다는 뜻이므로, 늦게 끝난 이전 시도의 결과로 최신 시도를 덮어쓰지 않는다.
    private void requireOwnedVisionProcessing(String token) {
        if (status != AnalysisStatus.VISION_PROCESSING || !Objects.equals(visionProcessingToken, token)) {
            throw new InvalidAnalysisStatusException(
                    "Vision 처리 소유권이 없거나 이미 종료된 세션입니다. 현재 상태: " + status
            );
        }
    }

    public void startPricing() {
        if (status != AnalysisStatus.AWAITING_USER_CONFIRMATION) {
            throw new InvalidAnalysisStatusException(
                    "가격 계산을 요청할 수 없는 분석 상태입니다. 현재 상태: " + status
            );
        }
        this.status = AnalysisStatus.PRICING_PROCESSING;
    }

    public void recordConfirmedInput(String confirmedInputJson) {
        this.confirmedInputJson = confirmedInputJson;
    }

    // #127: PRICING_PROCESSING 중 사용자가 취소하면 상태가 PRICING_PROCESSING을 벗어나므로, 그
    // 사이 외부 Pricing 호출이 뒤늦게 성공해도 이 가드가 막아 결과가 저장되지 않는다
    // (PricingAttemptCoordinator가 같은 행 잠금 안에서 호출한다 - completeVision과 동일한 원칙).
    public void completePricing(String pricingResultJson) {
        if (status != AnalysisStatus.PRICING_PROCESSING) {
            throw new InvalidAnalysisStatusException(
                    "Pricing을 완료할 수 없는 분석 상태입니다. 현재 상태: " + status
            );
        }
        this.pricingResultJson = pricingResultJson;
        this.status = AnalysisStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    // completePricing과 같은 이유로 가드를 둔다 - 취소된 뒤 도착한 Pricing 실패 응답이
    // CANCELLED를 PRICING_FAILED로 되돌리면 안 된다.
    public void failPricing(String message) {
        if (status != AnalysisStatus.PRICING_PROCESSING) {
            throw new InvalidAnalysisStatusException(
                    "Pricing 실패를 기록할 수 없는 분석 상태입니다. 현재 상태: " + status
            );
        }
        this.status = AnalysisStatus.PRICING_FAILED;
        this.failureStage = AnalysisFailureStage.PRICING;
        this.failureMessage = message;
    }

    // #127: 사용자가 이미지 분석 화면에서 X를 눌러 취소한다. 이미 상품 등록에 확정 사용된
    // 세션(registeredAt != null)은 등록된 상품·경매를 건드리지 않기 위해 취소를 거절한다.
    // 그 밖에는 상태와 무관하게(QUEUED든 VISION_PROCESSING이든 COMPLETED든 이미 실패한
    // 세션이든) 취소할 수 있다 - COMPLETED는 Pricing 완료일 뿐 등록 확정이 아니므로 막을
    // 이유가 없다. 이미 CANCELLED면 같은 성공 상태를 그대로 반환하도록 아무 것도 하지 않는다
    // (반복 취소 멱등). vision/pricing 관련 중간·최종 결과를 모두 비우고 처리 토큰을
    // 무효화한다 - status가 더 이상 VISION_PROCESSING/QUEUED/PRICING_PROCESSING이 아니게 되므로
    // claim/reclaim/complete/failVision/completePricing/failPricing의 기존 상태 가드가 토큰 값과
    // 무관하게 이 시점 이후의 모든 시도를 ALREADY_FINALIZED로 자동 분류한다.
    public void cancel() {
        if (status == AnalysisStatus.CANCELLED) {
            return;
        }
        if (registeredAt != null) {
            throw new InvalidAnalysisStatusException(
                    "이미 상품 등록에 사용된 분석 세션은 취소할 수 없습니다. 현재 상태: " + status
            );
        }
        this.status = AnalysisStatus.CANCELLED;
        this.visionResultJson = null;
        this.visionProgressJson = null;
        this.confirmedInputJson = null;
        this.pricingResultJson = null;
        this.visionProcessingToken = null;
        this.cancelledAt = LocalDateTime.now();
    }

    // #127: ProductRegistrationService.createProduct()가 같은 행 잠금 안에서 호출해 이 세션을
    // "등록에 확정 사용됨"으로 표시한다. 취소된 세션은 등록에 쓸 수 없고, 이미 다른 상품 등록에
    // 쓰인 세션(registeredAt != null)도 재사용할 수 없다(세션당 상품 1개) - 이 메서드가 던지는
    // 예외로 호출부(ProductRegistrationService) 트랜잭션 전체가 롤백되어 Product/Auction도
    // 저장되지 않는다.
    public void confirmRegistration() {
        if (status == AnalysisStatus.CANCELLED) {
            throw new InvalidAnalysisStatusException(
                    "취소된 분석 세션은 상품 등록에 사용할 수 없습니다. 현재 상태: " + status
            );
        }
        if (registeredAt != null) {
            throw new InvalidAnalysisStatusException("이미 다른 상품 등록에 사용된 분석 세션입니다.");
        }
        this.registeredAt = LocalDateTime.now();
    }
}
