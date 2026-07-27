package com.vintic.backend.curation;

import com.vintic.backend.curation.dto.CurationItem;
import com.vintic.backend.curation.dto.CurationResponse;
import org.springframework.stereotype.Service;

import java.util.List;

// 현재는 프론트엔드 연동을 위한 더미 데이터만 반환한다.
// 실제 추천/큐레이션 로직(ai/search 기반 등)이 준비되면 이 메서드 내부만 교체하면 되고,
// CurationResponse/CurationItem 스키마는 그대로 유지한다.
@Service
public class CurationService {

    public List<CurationResponse> getCurations() {
        return List.of(
                new CurationResponse(
                        "recently-popular",
                        "최근 인기 상품",
                        "최근 조회수와 거래가 많은 상품이에요",
                        List.of(
                                new CurationItem(1L, "Nike", "Dunk Low", "Nike Dunk Low Panda", 189000, "https://example.com/images/1.jpg"),
                                new CurationItem(2L, "Jordan", "1 Retro High", "Jordan 1 Retro High Lost and Found", 320000, "https://example.com/images/2.jpg")
                        )
                ),
                new CurationResponse(
                        "under-200000",
                        "20만원 이하 추천 상품",
                        "부담 없는 가격대의 추천 상품이에요",
                        List.of(
                                new CurationItem(1L, "Nike", "Dunk Low", "Nike Dunk Low Panda", 189000, "https://example.com/images/1.jpg"),
                                new CurationItem(3L, "New Balance", "993", "New Balance 993 Gray", 175000, "https://example.com/images/3.jpg")
                        )
                ),
                new CurationResponse(
                        "auction-ending-soon",
                        "경매 마감 임박 상품",
                        "곧 경매가 마감되는 상품이에요",
                        List.of(
                                new CurationItem(4L, "Adidas", "Samba OG", "Adidas Samba OG Cloud White", 210000, "https://example.com/images/4.jpg")
                        )
                ),
                new CurationResponse(
                        "brand-nike",
                        "나이키 추천 상품",
                        "나이키 브랜드 추천 상품이에요",
                        List.of(
                                new CurationItem(1L, "Nike", "Dunk Low", "Nike Dunk Low Panda", 189000, "https://example.com/images/1.jpg"),
                                new CurationItem(5L, "Nike", "Air Force 1 Low", "Nike Air Force 1 Low White", 165000, "https://example.com/images/5.jpg")
                        )
                )
        );
    }
}
