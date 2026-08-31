package com.vintic.backend.backupoffer.service;

import com.vintic.backend.bid.domain.Bid;

import java.util.List;
import java.util.Optional;

// #56-0 확정: 차순위 후보는 rank 2, 3까지만이다(rank 4 이하는 후보 아님). forfeit(최초 생성,
// rank1 다음 -> rank2)과 decline(다음 순위 생성, rank2 다음 -> rank3)이 이 로직을 공유한다.
//
// #56-2 시점엔 forfeit 전용으로 ranked.get(1)이 하드코딩돼 있었다 - decline이 "지금 거절된
// offer가 몇 등이었는지"에 따라 다음 순위를 계산해야 하는 이번 범위에서 일반화했다. ranked는
// BidRepository.findLatestBidPerUserOrderedByRank() 결과(§0.12 FIRST-IN WINS로 이미 정렬됨)를
// 그대로 받는다 - 새 정렬 규칙을 만들지 않는다.
public final class BackupCandidateSelector {

    public static final int MAX_BACKUP_RANK = 3;

    private BackupCandidateSelector() {
    }

    // afterRank(1-based) 바로 다음 순위의 후보를 반환한다. 그 다음 순위가 MAX_BACKUP_RANK를
    // 넘거나 그만큼 입찰자 자체가 없으면 후보 없음.
    public static Optional<Bid> next(List<Bid> ranked, int afterRank) {
        int nextRank = afterRank + 1;
        if (nextRank > MAX_BACKUP_RANK || nextRank > ranked.size()) {
            return Optional.empty();
        }
        return Optional.of(ranked.get(nextRank - 1));
    }

    // 특정 사용자의 순위(1-based)를 찾는다. decline은 "이 offer의 후보가 몇 등이었는지" 알아야만
    // next()에 넘길 afterRank를 계산할 수 있다 - BackupOffer 엔티티 자체엔 rank를 저장하지
    // 않으므로(#56-2 결정: accept 이전엔 쓰이지 않는 값을 얼려둘 이유가 없음) 매번 ranked에서
    // 다시 찾는다.
    public static Optional<Integer> rankOf(List<Bid> ranked, Long userId) {
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).getUser().getId().equals(userId)) {
                return Optional.of(i + 1);
            }
        }
        return Optional.empty();
    }
}
