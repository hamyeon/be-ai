package com.vintic.backend.product.repository;

import com.vintic.backend.product.domain.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findAllByOrderByCreatedAtDesc();

    // 경매 등록(ProductRegistrationService의 첫 경매 등록, AuctionRelistService의 재경매) 전용.
    // "이 상품에 대한 활성 경매/총 등록 횟수"를
    // 확인하고 새 Auction을 만드는 전체 흐름을 이 락으로 직렬화한다 - AuctionRepository.
    // findByIdForUpdate()(경매 도메인의 기존 관례)와 동일한 목적의 Product 버전이다. 이 락을
    // 트랜잭션의 첫 statement로 먼저 획득해야, 뒤이은 AuctionRepository.findAllByProductId()가
    // 그 시점의 최신 커밋 상태를 안전하게 읽을 수 있다(#45/#46 교훈 - lock 이후의 첫 non-locking
    // read가 read view를 확립하므로, 락보다 먼저 실행되는 non-locking read가 없어야 한다).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :productId")
    Optional<Product> findByIdForUpdate(@Param("productId") Long productId);
}