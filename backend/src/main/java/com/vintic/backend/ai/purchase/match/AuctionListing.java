package com.vintic.backend.ai.purchase.match;

// Matcher에 넘기는 매물의 텍스트 조각. 설계안 6-2 요청의 listing 부분.
//
// v1은 텍스트만 본다. 설계안에는 imageKeys가 있었지만 Vision 호출은 케이스당 12.6초라
// 5분 주기 Scheduler × Goal × 신규 매물로 곱하면 감당이 안 된다. 사진 판정은 v2.
//
// brand/model/colorway는 Product의 구조화 필드(판매자가 확정한 값), title/description은
// 판매자가 쓴 자유 텍스트다. 설명란에는 무관한 브랜드를 검색용으로 나열하는 매물이 많아
// 규칙 판정은 구조화 필드와 제목을 먼저 보고 설명은 보조로만 쓴다.
public record AuctionListing(
        Long auctionId,
        String brand,
        String model,
        String colorway,
        String title,
        String description
) {

    // 판매자가 확정한 필드 + 제목. 판정의 1차 근거.
    String primaryText() {
        return join(brand, model, colorway, title);
    }

    String allText() {
        return join(brand, model, colorway, title, description);
    }

    private static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(part.trim());
            }
        }
        return out.toString();
    }
}
