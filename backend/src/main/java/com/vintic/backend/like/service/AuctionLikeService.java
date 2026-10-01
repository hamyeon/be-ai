package com.vintic.backend.like.service;

import com.vintic.backend.like.dto.LikeResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// #55: Controller의 얇은 진입점이다(ManualBidService/AutoBidService와 동일한 역할 분담).
// @Transactional을 직접 갖지 않고, 실제 트랜잭션 경계는 AuctionLikeCommandService가 갖는다.
//
// FINAL contract §19/§20은 POST/DELETE 각각의 성공 shape(liked/likeCount)와 404만 정의하고,
// "이미 좋아요한 상태에서 다시 POST" / "좋아요가 없는 상태에서 DELETE"에 대한 별도 실패 코드를
// 정의하지 않는다. 계약이 정의하지 않은 에러를 임의로 만들지 않는 가장 보수적인 해석으로, 두
// 경우 모두 멱등하게 처리한다 - 재요청해도 에러 없이 현재 상태를 그대로 반환한다.
@Service
public class AuctionLikeService {

    private final AuctionLikeCommandService auctionLikeCommandService;

    public AuctionLikeService(AuctionLikeCommandService auctionLikeCommandService) {
        this.auctionLikeCommandService = auctionLikeCommandService;
    }

    public LikeResponse like(Long auctionId, Long userId) {
        try {
            return auctionLikeCommandService.like(auctionId, userId);
        } catch (DataIntegrityViolationException e) {
            // 사전 exists-check로 대부분의 중복(같은 사용자의 순차 재요청)은 걸러지지만, 진짜
            // 동시 요청 race는 uk_auction_like_auction_user UNIQUE 제약이 최종적으로 막는다.
            // 진 쪽도 "결과적으로 좋아요가 등록된 상태"이므로 그대로 성공(liked=true)으로
            // 흡수하는 것이 위에서 정한 멱등 정책과 일관된다 - 새 트랜잭션에서 현재 상태를
            // 다시 읽는다(InnoDB가 이긴 쪽의 커밋을 보장한 뒤에만 이 예외가 발생한다).
            return auctionLikeCommandService.currentLikeState(auctionId);
        }
    }

    public LikeResponse unlike(Long auctionId, Long userId) {
        return auctionLikeCommandService.unlike(auctionId, userId);
    }
}
