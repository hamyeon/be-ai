package com.vintic.backend.product;

import com.vintic.backend.common.dto.ApiResponse;
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

    private final PricingService pricingService;
    private final ProductRegistrationService productRegistrationService;

    public ProductController(
            PricingService pricingService,
            ProductRegistrationService productRegistrationService
    ) {
        this.pricingService = pricingService;
        this.productRegistrationService = productRegistrationService;
    }

    @PostMapping("/calculate-price")
    public ResponseEntity<ApiResponse<CalculatePriceResponse>> calculatePrice(
            @Valid @RequestBody CalculatePriceRequest request
    ) {
        PricingRequest pricingRequest = new PricingRequest(
                request.brand(),
                request.modelName(),
                request.color(),
                request.size(),
                request.conditionGrade(),
                request.componentStatus()
        );

        PricingResult result = pricingService.calculate(pricingRequest);

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