package com.vintic.backend.auction.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.dto.AuctionCancelResponse;
import com.vintic.backend.auction.dto.ChangeStartPriceResponse;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.common.exception.AuctionCancelWindowClosedException;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.AuctionSellerMismatchException;
import com.vintic.backend.common.exception.StartPriceChangeWindowClosedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

// 판매자가 SCHEDULED 경매를 관리하는 두 동작(취소/시작가 수정)을 담당한다. 둘 다 같은 패턴을
// 따른다: Auction row를 FOR UPDATE로 잠근 뒤(기존 AuctionRepository.findByIdForUpdate 관례
// 재사용) 최신 status/startAt을 다시 확인하고, 서비스가 시간 정책을 먼저 검증한 뒤에만 도메인
// 메서드(cancel()/changeStartPrice())를 호출한다 - 도메인 메서드의 상태 가드는 프로그래밍 오류
// 방지용일 뿐, 정상 경로에서 그 가드에 걸릴 일이 없다(AuctionStartService.startIfDue()와 동일한
// "서비스가 먼저 확인" 원칙).
@Service
public class AuctionManagementService {

    private final AuctionRepository auctionRepository;
    private final Clock clock;

    public AuctionManagementService(AuctionRepository auctionRepository, Clock clock) {
        this.auctionRepository = auctionRepository;
        this.clock = clock;
    }

    // 정책: "경매가 실제 시작하기 전까지만" 취소할 수 있다 - now < startAt.
    @Transactional
    public AuctionCancelResponse cancel(Long auctionId, Long currentUserId) {
        Auction auction = findForUpdate(auctionId);
        requireOwner(auction, currentUserId);

        LocalDateTime now = LocalDateTime.now(clock);
        if (auction.getStatus() != AuctionStatus.SCHEDULED || !auction.getStartAt().isAfter(now)) {
            throw new AuctionCancelWindowClosedException(
                    "경매가 시작되기 전까지만 취소할 수 있습니다. auctionId: " + auctionId
            );
        }

        auction.cancel();
        return new AuctionCancelResponse(auction.getId(), auction.getStatus().name());
    }

    // 정책: 시작가는 "경매 시작 1시간 전까지만" 수정할 수 있다 - now <= startAt - 1h.
    @Transactional
    public ChangeStartPriceResponse changeStartPrice(Long auctionId, Long currentUserId, Long newStartPrice) {
        Auction auction = findForUpdate(auctionId);
        requireOwner(auction, currentUserId);

        LocalDateTime now = LocalDateTime.now(clock);
        if (auction.getStatus() != AuctionStatus.SCHEDULED || now.isAfter(auction.getStartAt().minusHours(1))) {
            throw new StartPriceChangeWindowClosedException(
                    "경매 시작 1시간 전까지만 시작가를 수정할 수 있습니다. auctionId: " + auctionId
            );
        }

        auction.changeStartPrice(newStartPrice);
        return new ChangeStartPriceResponse(auction.getId(), auction.getStartPrice());
    }

    private Auction findForUpdate(Long auctionId) {
        return auctionRepository.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: " + auctionId));
    }

    private void requireOwner(Auction auction, Long currentUserId) {
        if (!auction.getProduct().getSeller().getId().equals(currentUserId)) {
            throw new AuctionSellerMismatchException(
                    "본인 경매만 관리할 수 있습니다. auctionId: " + auction.getId()
            );
        }
    }
}
