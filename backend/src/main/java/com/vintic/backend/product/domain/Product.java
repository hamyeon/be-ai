package com.vintic.backend.product.domain;

import com.vintic.backend.user.domain.User;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    // #55 N+1 audit: Similar처럼 여러 Product를 한 번에 조회한 뒤 각 item의 imageUrls(thumbnail)에
    // 접근하는 경로가 있다. @ElementCollection은 LAZY라 기본값으로는 item 개수만큼 SELECT가
    // 반복된다(실측: 회귀 테스트로 확인) - 이 컬렉션만 join fetch하면 Pageable(LIMIT)과 함께
    // 쓸 수 없어(collection fetch + firstResult/maxResults 문제) 대신 @BatchSize로 여러
    // product의 imageUrls를 한 번의 IN 쿼리로 묶어 로딩한다. 페이징 쿼리 자체의 구조는 바꾸지 않는다.
    @ElementCollection
    @CollectionTable(
            name = "product_image_urls",
            joinColumns = @JoinColumn(name = "product_id")
    )
    @Column(name = "image_url", nullable = false, length = 1000)
    @BatchSize(size = 20)
    private List<String> imageUrls = new ArrayList<>();

    private String brand;
    private String model;
    private String colorway;
    private Integer sizeKr;
    private String conditionGrade;
    private String componentStatus;

    private Integer recommendedPrice;
    private Integer baseMarketPrice;
    private String priceRange;
    private Integer finalPrice;

    @Column(length = 1000)
    private String reason;

    @Column(length = 1000)
    private String description;

    private LocalDateTime createdAt;

    protected Product() {
    }

    public Product(
            User seller,
            List<String> imageUrls,
            String brand,
            String model,
            String colorway,
            Integer sizeKr,
            String conditionGrade,
            String componentStatus,
            Integer recommendedPrice,
            Integer baseMarketPrice,
            String priceRange,
            Integer finalPrice,
            String reason,
            String description
    ) {
        this.seller = seller;
        this.imageUrls = new ArrayList<>(imageUrls);
        this.brand = brand;
        this.model = model;
        this.colorway = colorway;
        this.sizeKr = sizeKr;
        this.conditionGrade = conditionGrade;
        this.componentStatus = componentStatus;
        this.recommendedPrice = recommendedPrice;
        this.baseMarketPrice = baseMarketPrice;
        this.priceRange = priceRange;
        this.finalPrice = finalPrice;
        this.reason = reason;
        this.description = description;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public User getSeller() {
        return seller;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public String getBrand() {
        return brand;
    }

    public String getModel() {
        return model;
    }

    public String getColorway() {
        return colorway;
    }

    public Integer getSizeKr() {
        return sizeKr;
    }

    public String getConditionGrade() {
        return conditionGrade;
    }

    public String getComponentStatus() {
        return componentStatus;
    }

    public Integer getRecommendedPrice() {
        return recommendedPrice;
    }

    public Integer getBaseMarketPrice() {
        return baseMarketPrice;
    }

    public String getPriceRange() {
        return priceRange;
    }

    public Integer getFinalPrice() {
        return finalPrice;
    }

    public String getReason() {
        return reason;
    }

    public String getDescription() {
        return description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}