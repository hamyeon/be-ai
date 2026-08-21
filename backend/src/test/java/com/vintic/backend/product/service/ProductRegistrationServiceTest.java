package com.vintic.backend.product.service;

import com.vintic.backend.common.exception.UserNotFoundException;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductRegistrationServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserRepository userRepository;

    // 등록 시 추천용 벡터를 만든다. 벡터 생성 자체는 ProductVectorServiceTest가 검증하므로
    // 여기서는 등록 흐름을 막지 않는지만 본다.
    @Mock
    private ProductVectorService productVectorService;

    private ProductRegistrationService sut;

    private final CreateProductRequest request = new CreateProductRequest(
            List.of("https://example.com/a.jpg", "https://example.com/b.jpg", "https://example.com/c.jpg"),
            "Nike", "Dunk Low", "Panda", 270, "B", "PARTIAL",
            300000, 350000, "285,000원 ~ 315,000원", 290000, "사유", "설명"
    );

    private void initSut() {
        sut = new ProductRegistrationService(productRepository, userRepository, productVectorService);
    }

    @Test
    void 존재하는_유저ID로_상품을_등록하면_seller로_설정된다() {
        initSut();
        User seller = User.register("seller@vintic.local", "seller", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
        when(productRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse response = sut.createProduct(request, 1L);

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getSeller()).isEqualTo(seller);
        assertThat(response.brand()).isEqualTo("Nike");
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
        User seller = User.register("seller@vintic.local", "seller", null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(seller));
        when(productRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        sut.createProduct(request, 1L);

        verify(productVectorService).refresh(any());
    }
}
