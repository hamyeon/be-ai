package com.vintic.backend.ai.purchase.match;

// 매물이 Goal의 모델에 해당하는지 판정한다. 설계안 6-4의 격리 인터페이스.
//
// 계약:
//   - matched는 모델 일치 여부만 본다(등급·예산·사이즈는 pre-filter가 구조화 필드로 끝냄).
//   - 실패(API 오류 등)는 예외로 올린다. 설계안 6-2: 실패한 후보는 이번 scan에서 제외하고
//     다음 scan에서 재평가한다. 파서와 달리 규칙 기반 fallback을 끼우지 않는다 - 잘못된
//     matched=true는 잘못된 AutoBid로 이어지므로 "모르면 넘어간다"가 맞다.
//   - 결과는 (goal, auction) 단위로 저장해 재호출하지 않는다(백엔드 담당).
public interface ListingMatcher {

    MatchResult evaluate(MatchGoal goal, AuctionListing listing);
}
