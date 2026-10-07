package com.vintic.backend.product;

import com.vintic.backend.analyze.service.ProductPricingService;
import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.product.dto.CalculatePriceRequest;
import com.vintic.backend.product.dto.CalculatePriceResponse;
import com.vintic.backend.product.dto.CreateProductRequest;
import com.vintic.backend.product.dto.ProductListResponse;
import com.vintic.backend.product.dto.ProductResponse;
import com.vintic.backend.product.service.ProductRegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 첫 경매를 만드는 유일한 외부 경로는 createProduct()뿐이다(상품+첫 경매를 한 트랜잭션으로
// 함께 저장, ProductRegistrationService 참고) - 이 컨트롤러에 별도의 "경매 생성" 엔드포인트를
// 두지 않는다. 기존 상품의 재경매는 AuctionController의 POST /api/auctions/{id}/relist가 전담한다.
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductPricingService productPricingService;
    private final ProductRegistrationService productRegistrationService;

    public ProductController(
            ProductPricingService productPricingService,
            ProductRegistrationService productRegistrationService
    ) {
        this.productPricingService = productPricingService;
        this.productRegistrationService = productRegistrationService;
    }

    @PostMapping("/calculate-price")
    public ResponseEntity<ApiResponse<CalculatePriceResponse>> calculatePrice(
            @Valid @RequestBody CalculatePriceRequest request,
            @RequestAttribute("currentUserId") Long currentUserId
    ) {
        CalculatePriceResponse response = productPricingService.calculatePrice(request, currentUserId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProductResponse>> createProduct(
            @Valid @RequestBody CreateProductRequest request,
            @RequestAttribute("currentUserId") Long currentUserId
    ) {
        ProductResponse response = productRegistrationService.createProduct(request, currentUserId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProductListResponse>>> getProducts() {
        List<ProductListResponse> response = productRegistrationService.getProducts();
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
