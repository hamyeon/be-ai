package com.vintic.backend.recommendation.repository;

import com.vintic.backend.recommendation.domain.ProductVector;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductVectorRepository extends JpaRepository<ProductVector, Long> {
}
