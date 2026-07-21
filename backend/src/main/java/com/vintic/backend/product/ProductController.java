package com.vintic.backend.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.common.exception.AiApiException;
import com.vintic.backend.common.exception.AnalysisSessionNotFoundException;
import com.vintic.backend.product.dto.CalculatePriceRequest;
import com.vintic.backend.product.dto.CalculatePriceResponse;
import com.vintic.backend.product.dto.CreateProductRequest;
import com.vintic.backend.product.dto.ProductListResponse;
import com.vintic.backend.product.dto.ProductResponse;
import com.vintic.backend.product.pricing.PricingRequest;
import com.vintic.backend.product.pricing.PricingResult;
import com.vintic.backend.product.pricing.PricingService;
import com.vintic.backend.product.service.ProductRegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final int FAILURE_MESSAGE_MAX_LENGTH = 1000;

    private final PricingService pricingService;
    private final ProductRegistrationService productRegistrationService;
    private final ProductAnalysisSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    public ProductController(
            PricingService pricingService,
            ProductRegistrationService productRegistrationService,
            ProductAnalysisSessionRepository sessionRepository,
            ObjectMapper objectMapper
    ) {
        this.pricingService = pricingService;
        this.productRegistrationService = productRegistrationService;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/calculate-price")
    public ResponseEntity<ApiResponse<CalculatePriceResponse>> calculatePrice(
            @Valid @RequestBody CalculatePriceRequest request
    ) {
        ProductAnalysisSession session = sessionRepository.findById(request.analysisId())
                .orElseThrow(() -> new AnalysisSessionNotFoundException(
                        "분석 세션을 찾을 수 없습니다. analysisId: " + request.analysisId()
                ));

        session.startPricing();
        sessionRepository.save(session);

        PricingRequest pricingRequest = new PricingRequest(
                request.brand(),
                request.modelName(),
                request.color(),
                request.size(),
                request.conditionGrade(),
                request.componentStatus()
        );

        PricingResult result;
        try {
            result = pricingService.calculate(pricingRequest);
        } catch (RuntimeException e) {
            session.failPricing(truncate(e.getMessage()));
            sessionRepository.save(session);
            throw e;
        }

        session.completePricing(toJson(result));
        sessionRepository.save(session);

        CalculatePriceResponse response = new CalculatePriceResponse(
                result.recommendedPrice(),
                result.baseMarketPrice(),
                result.kreamAveragePrice(),
                result.ebayAveragePrice(),
                result.minRecommendedPrice(),
                result.maxRecommendedPrice(),
                result.priceRange(),
                result.reason(),
                toCalculateMatches(result.kreamMatches()),
                toCalculateMatches(result.ebayMatches())
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private List<CalculatePriceResponse.MatchedMarketPrice> toCalculateMatches(
            List<PricingResult.MatchedMarketPrice> matches
    ) {
        return matches.stream()
                .map(match -> new CalculatePriceResponse.MatchedMarketPrice(
                        match.source(),
                        match.brand(),
                        match.modelName(),
                        match.color(),
                        match.size(),
                        match.conditionGrade(),
                        match.componentStatus(),
                        match.price(),
                        match.url()
                ))
                .toList();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AiApiException("가격 계산 결과를 저장하는 중 오류가 발생했습니다.");
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > FAILURE_MESSAGE_MAX_LENGTH
                ? message.substring(0, FAILURE_MESSAGE_MAX_LENGTH)
                : message;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProductResponse>> createProduct(
            @Valid @RequestBody CreateProductRequest request
    ) {
        ProductResponse response = productRegistrationService.createProduct(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProductListResponse>>> getProducts() {
        List<ProductListResponse> response = productRegistrationService.getProducts();
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
