package com.vintic.backend.product.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.common.exception.InvalidAuctionTimeException;
import com.vintic.backend.common.exception.UserNotFoundException;
import com.vintic.backend.common.util.BidIncrementPolicy;
import com.vintic.backend.common.util.S3UrlPresigner;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.dto.CreateProductRequest;
import com.vintic.backend.product.dto.ProductResponse;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.recommendation.service.ProductVectorService;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductRegistrationServiceTest {

    // 2026-08-18 20:00:00 KST 고정.
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-18T11:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final OffsetDateTime NOW_KST = OffsetDateTime.now(FIXED_CLOCK);

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserRepository userRepository;

    // 등록 시 추천용 벡터를 만든다. 벡터 생성 자체는 ProductVectorServiceTest가 검증하므로
    // 여기서는 등록 흐름을 막지 않는지만 본다.
    @Mock
    private ProductVectorService productVectorService;

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private S3UrlPresigner s3UrlPresigner;

    private ProductRegistrationService sut;

    private final CreateProductRequest request = new CreateProductRequest(
            List.of("https://example.com/a.jpg", "https://example.com/b.jpg", "https://example.com/c.jpg"),
            "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
            300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명",
            10000L, NOW_KST.plusHours(2), NOW_KST.plusHours(3)
    );

    private void initSut() {
        // presign 자체는 이 테스트의 관심사가 아니다 - 입력 URL을 그대로 돌려줘 등록 흐름
        // 검증에 영향을 주지 않는다(S3UrlPresignerTest가 presign 동작을 검증한다).
        lenient().when(s3UrlPresigner.presign(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        sut = new ProductRegistrationService(
                productRepository, userRepository, productVectorService, auctionRepository, FIXED_CLOCK, s3UrlPresigner
        );
    }

    private void stubHappyPath() {
        User seller = User.register("seller@vintic.local", "seller", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
        when(productRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(auctionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 존재하는_유저ID로_상품을_등록하면_seller로_설정된다() {
        initSut();
        stubHappyPath();

        ProductResponse response = sut.createProduct(request, 1L);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getSeller().getEmail()).isEqualTo("seller@vintic.local");
        assertThat(response.brand()).isEqualTo("Nike");
    }

    @Test
    void 첫_경매가_상품과_함께_SCHEDULED로_생성되고_시작가는_판매희망가와_별개로_반영된다() {
        initSut();
        stubHappyPath();

        ProductResponse response = sut.createProduct(request, 1L);

        ArgumentCaptor<Auction> captor = ArgumentCaptor.forClass(Auction.class);
        verify(auctionRepository).save(captor.capture());
        Auction savedAuction = captor.getValue();

        assertThat(response.auctionStatus()).isEqualTo("SCHEDULED");
        assertThat(savedAuction.getStartPrice()).isEqualTo(10000L);
        assertThat(savedAuction.getStartPrice()).isNotEqualTo(request.sellingPrice().longValue());
        assertThat(response.sellingPrice()).isEqualTo(290000);
    }

    @Test
    void 경매별_입찰단위는_요청과_무관하게_고정값이_적용된다() {
        initSut();
        stubHappyPath();

        ProductResponse response = sut.createProduct(request, 1L);

        assertThat(response.bidIncrement()).isEqualTo(BidIncrementPolicy.DEFAULT_BID_INCREMENT);
    }

    @Test
    void 경매_진행시간이_1시간_미만이면_상품_저장까지_포함해_전체가_실패한다() {
        initSut();
        User seller = User.register("seller@vintic.local", "seller", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
        when(productRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CreateProductRequest invalid = new CreateProductRequest(
                request.imageUrls(), request.brand(), request.modelName(), request.color(), request.size(),
                request.conditionGrade(), request.componentStatus(), request.recommendedPrice(),
                request.baseMarketPrice(), request.priceRange(), request.sellingPrice(), request.reason(),
                request.sellerDescription(),
                request.auctionStartPrice(), NOW_KST.plusHours(1), NOW_KST.plusHours(1).plusMinutes(59).plusSeconds(59)
        );

        assertThatThrownBy(() -> sut.createProduct(invalid, 1L))
                .isInstanceOf(InvalidAuctionTimeException.class);

        verify(auctionRepository, never()).save(any());
    }

    @Test
    void 존재하지_않는_유저ID로_상품을_등록하면_예외가_발생한다() {
        initSut();
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.createProduct(request, 999L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void 상품을_등록하면_추천용_벡터를_만든다() {
        initSut();
        stubHappyPath();

        sut.createProduct(request, 1L);

        verify(productVectorService).refresh(any());
    }
}
