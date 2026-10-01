package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.price.PriceEstimate;
import com.vintic.backend.auction.domain.Auction;

// PurchaseGoalCandidateFinder의 내부 결과. Day 4(Matcher 호출·순위·cap 계산)가 재사용할 수 있게
// 사전 필터를 통과한 시점의 서버 시세를 함께 들고 있다 - Day 4가 같은 Product를 위해 시세를
// 다시 계산하지 않아도 된다. 공개 API 응답이 아니다.
public record PurchaseGoalCandidate(Auction auction, PriceEstimate priceEstimate) {
}
