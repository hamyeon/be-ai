package com.vintic.backend.like.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.UserNotFoundException;
import com.vintic.backend.like.domain.AuctionLike;
import com.vintic.backend.like.dto.LikeResponse;
import com.vintic.backend.like.repository.AuctionLikeRepository;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// #55: 트랜잭션 경계를 갖는 실제 작업 계층이다. AuctionLikeService(오케스트레이션)가 이 빈을
// 프록시를 통해 호출한다 - IdempotencyClaimService/ManualBidService(#32)와 같은 이유로 분리했다:
// 같은 클래스 안에서 this로 서로를 호출하면 Spring 프록시를 우회해 @Transactional이
// 적용되지 않는다.
//
// like()가 uk_auction_like_auction_user UNIQUE 위반으로 실패하면 예외를 그대로 던져 이
// 트랜잭션을 깨끗하게 롤백시킨다(같은 트랜잭션/영속성 컨텍스트를 계속 쓰려고 하면 실제 동시
// 요청에서 500으로 샌다는 것을 실측했다 - 처음에는 catch 후 같은 트랜잭션에서 count를 이어
// 읽으려 했으나 실패했고, REQUIRES_NEW로 격리한 두 번째 시도는 @DataJpaTest처럼 아직 커밋되지
// 않은 fixture를 다른 커넥션에서 보지 못해 실패했다). 대신 InnoDB가 같은 unique key의 두 번째
// INSERT를 첫 트랜잭션의 commit까지 블로킹한다는 성질(#41 AutoBidConcurrencyMySqlIT와 동일
// 근거)을 이용해, 충돌한 쪽은 currentLikeState()라는 완전히 새 트랜잭션에서 그 시점에 이미
// 커밋된 최신 상태를 다시 읽는다.
@Service
class AuctionLikeCommandService {

    private final AuctionRepository auctionRepository;
    private final UserRepository userRepository;
    private final AuctionLikeRepository auctionLikeRepository;

    AuctionLikeCommandService(
            AuctionRepository auctionRepository,
            UserRepository userRepository,
            AuctionLikeRepository auctionLikeRepository
    ) {
        this.auctionRepository = auctionRepository;
        this.userRepository = userRepository;
        this.auctionLikeRepository = auctionLikeRepository;
    }

    @Transactional
    public LikeResponse like(Long auctionId, Long userId) {
        if (!auctionRepository.existsById(auctionId)) {
            throw new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId);
        }
        if (!auctionLikeRepository.existsByAuctionIdAndUserId(auctionId, userId)) {
            if (!userRepository.existsById(userId)) {
                throw new UserNotFoundException("존재하지 않는 사용자입니다. userId: " + userId);
            }
            Auction auction = auctionRepository.getReferenceById(auctionId);
            User user = userRepository.getReferenceById(userId);
            // 동시 요청 race는 uk_auction_like_auction_user UNIQUE 제약이 최종적으로 막는다 -
            // 여기서 실패하면 잡지 않고 그대로 던져 이 트랜잭션을 롤백시킨다(호출자가
            // currentLikeState()로 새 트랜잭션에서 재조회한다).
            auctionLikeRepository.saveAndFlush(AuctionLike.create(auction, user));
        }
        return currentLikeState(auctionId);
    }

    @Transactional
    public LikeResponse currentLikeState(Long auctionId) {
        long likeCount = auctionLikeRepository.countByAuctionId(auctionId);
        return new LikeResponse(true, (int) likeCount);
    }

    @Transactional
    public LikeResponse unlike(Long auctionId, Long userId) {
        if (!auctionRepository.existsById(auctionId)) {
            throw new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId);
        }
        auctionLikeRepository.findByAuctionIdAndUserId(auctionId, userId)
                .ifPresent(auctionLikeRepository::delete);
        long likeCount = auctionLikeRepository.countByAuctionId(auctionId);
        return new LikeResponse(false, (int) likeCount);
    }
}
