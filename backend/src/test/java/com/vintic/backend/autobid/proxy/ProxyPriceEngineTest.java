package com.vintic.backend.autobid.proxy;

import com.vintic.backend.autobid.domain.EffectiveCapCalculator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 순수 단위 테스트다 - Spring context, repository, DB 없이 ProxyPriceEngine을 직접 new해서 검증한다.
// 모든 시나리오는 bidIncrement=5000을 기본으로 쓴다(별도 명시 없는 한).
class ProxyPriceEngineTest {

    private static final long INCREMENT = 5000L;

    private final ProxyPriceEngine engine = new ProxyPriceEngine();

    private static ProxyCandidate candidate(long userId, long maxAmount, LocalDateTime registeredAt, Long id) {
        return new ProxyCandidate(userId, maxAmount, registeredAt, id);
    }

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime T1 = T0.plusMinutes(1);
    private static final LocalDateTime T2 = T0.plusMinutes(2);

    @Nested
    class 경쟁자없음 {

        @Test
        void NONE_트리거_candidates가_비어있으면_아무것도_바뀌지_않는다() {
            ProxyResolutionInput input = new ProxyResolutionInput(100000L, INCREMENT, new ProxyTrigger.None(), List.of());

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(100000L);
            assertThat(result.finalWinnerUserId()).isNull();
            assertThat(result.priceChanged()).isFalse();
            assertThat(result.resultingAutoBid()).isNull();
            assertThat(result.proxyResponded()).isFalse();
            assertThat(result.candidateResults()).isEmpty();
        }

        @Test
        void MANUAL_트리거_경쟁_AutoBid이_없으면_manual_bidder가_그대로_이긴다() {
            ProxyResolutionInput input = new ProxyResolutionInput(
                    100000L, INCREMENT, new ProxyTrigger.Manual(100000L, 1L), List.of()
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(100000L);
            assertThat(result.finalWinnerUserId()).isEqualTo(1L);
            assertThat(result.priceChanged()).isFalse();
            assertThat(result.resultingAutoBid()).isNull();
            assertThat(result.proxyResponded()).isFalse();
            assertThat(result.candidateResults()).isEmpty();
        }

        @Test
        void AUTO_트리거_currentWinner도_candidates도_없으면_winner는_null로_유지된다() {
            ProxyResolutionInput input = new ProxyResolutionInput(
                    100000L, INCREMENT, new ProxyTrigger.Auto(null), List.of()
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(100000L);
            assertThat(result.finalWinnerUserId()).isNull();
            assertThat(result.priceChanged()).isFalse();
        }

        @Test
        void AUTO_트리거_유일한_entrant는_경쟁상대가_없으면_활성화만_되고_스스로_응찰하지_않는다() {
            ProxyResolutionInput input = new ProxyResolutionInput(
                    105000L, INCREMENT, new ProxyTrigger.Auto(null),
                    List.of(candidate(9L, 200000L, T0, 1L))
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(105000L);
            assertThat(result.finalWinnerUserId()).isNull();
            assertThat(result.priceChanged()).isFalse();
            assertThat(result.resultingAutoBid()).isNull();
            assertThat(result.candidateResults()).containsExactly(new CandidateResult(9L, ProxyEntrantStatus.ACTIVE));
        }
    }

    @Nested
    class Manual_vs_Auto {

        @Test
        void 경쟁자_effectiveCap이_M보다_낮으면_manual이_이기고_경쟁자는_CAP_REACHED다() {
            // effectiveCap(90000, 100000, 5000) = 90000 < M(100000)
            ProxyResolutionInput input = new ProxyResolutionInput(
                    100000L, INCREMENT, new ProxyTrigger.Manual(100000L, 1L),
                    List.of(candidate(2L, 90000L, T0, 10L))
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(100000L);
            assertThat(result.finalWinnerUserId()).isEqualTo(1L);
            assertThat(result.priceChanged()).isFalse();
            assertThat(result.resultingAutoBid()).isNull();
            assertThat(result.proxyResponded()).isFalse();
            assertThat(result.candidateResults()).containsExactly(new CandidateResult(2L, ProxyEntrantStatus.CAP_REACHED));
        }

        @Test
        void 경쟁자_effectiveCap이_M보다_높으면_min_M플러스증분_경쟁자cap으로_반격한다() {
            // effectiveCap(300000, 100000, 5000) = 300000 > M(100000)
            ProxyResolutionInput input = new ProxyResolutionInput(
                    100000L, INCREMENT, new ProxyTrigger.Manual(100000L, 1L),
                    List.of(candidate(2L, 300000L, T0, 10L))
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(105000L); // min(100000+5000, 300000)
            assertThat(result.finalWinnerUserId()).isEqualTo(2L);
            assertThat(result.priceChanged()).isTrue();
            assertThat(result.resultingAutoBid()).isEqualTo(new ResultingAutoBid(2L, 105000L));
            assertThat(result.proxyResponded()).isTrue();
            assertThat(result.candidateResults()).containsExactly(new CandidateResult(2L, ProxyEntrantStatus.ACTIVE));
        }

        @Test
        void 경쟁자_effectiveCap이_M과_같으면_FIRST_IN_WINS로_기존_AutoBid이_M에서_승자를_되찾는다() {
            // effectiveCap(100000, 100000, 5000) = 100000 == M
            ProxyResolutionInput input = new ProxyResolutionInput(
                    100000L, INCREMENT, new ProxyTrigger.Manual(100000L, 1L),
                    List.of(candidate(2L, 100000L, T0, 10L))
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(100000L); // M+증분(105000)으로 밀지 않는다
            assertThat(result.finalWinnerUserId()).isEqualTo(2L); // manual bidder(1L)가 아니라 기존 AutoBid(2L)
            assertThat(result.priceChanged()).isFalse(); // 가격 "값"은 그대로다
            // 하지만 winner는 바뀌었다(manual bidder → 기존 AutoBid) - 이 반격을 기록하는 AUTO
            // Bid row는 priceChanged가 아니라 winner 변경 여부로 저장 대상이 결정돼야 한다.
            assertThat(result.resultingAutoBid()).isEqualTo(new ResultingAutoBid(2L, 100000L));
            assertThat(result.proxyResponded()).isTrue();
            assertThat(result.candidateResults()).containsExactly(new CandidateResult(2L, ProxyEntrantStatus.ACTIVE));
        }

        @Test
        void 여러_경쟁_AutoBid_중_가장_강한_쪽만_반격하고_나머지는_상태만_갱신된다() {
            ProxyResolutionInput input = new ProxyResolutionInput(
                    100000L, INCREMENT, new ProxyTrigger.Manual(100000L, 1L),
                    List.of(
                            candidate(2L, 300000L, T0, 10L), // cap 300000 - 최강
                            candidate(3L, 150000L, T1, 11L)  // cap 150000 - 차강
                    )
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(155000L); // min(300000, 150000+5000)
            assertThat(result.finalWinnerUserId()).isEqualTo(2L);
            assertThat(result.resultingAutoBid()).isEqualTo(new ResultingAutoBid(2L, 155000L));
            assertThat(result.candidateResults()).containsExactlyInAnyOrder(
                    new CandidateResult(2L, ProxyEntrantStatus.ACTIVE),
                    new CandidateResult(3L, ProxyEntrantStatus.CAP_REACHED)
            );
        }
    }

    @Nested
    class Auto_vs_Auto {

        @Test
        void entrant가_경쟁자보다_강하면_entrant가_이기고_경쟁자는_CAP_REACHED다() {
            // currentWinner는 경쟁자(2L) - 경쟁자 cap(120000) < entrant cap(300000)
            ProxyResolutionInput input = new ProxyResolutionInput(
                    105000L, INCREMENT, new ProxyTrigger.Auto(2L),
                    List.of(
                            candidate(9L, 300000L, T1, 1L), // entrant
                            candidate(2L, 120000L, T0, 2L)  // 기존 경쟁자
                    )
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(125000L); // min(300000, 120000+5000)
            assertThat(result.finalWinnerUserId()).isEqualTo(9L);
            assertThat(result.priceChanged()).isTrue();
            assertThat(result.candidateResults()).containsExactlyInAnyOrder(
                    new CandidateResult(9L, ProxyEntrantStatus.ACTIVE),
                    new CandidateResult(2L, ProxyEntrantStatus.CAP_REACHED)
            );
        }

        @Test
        void 경쟁자가_entrant보다_강하면_entrant가_지고_CAP_REACHED가_된다() {
            ProxyResolutionInput input = new ProxyResolutionInput(
                    105000L, INCREMENT, new ProxyTrigger.Auto(2L),
                    List.of(
                            candidate(9L, 200000L, T1, 1L), // entrant
                            candidate(2L, 500000L, T0, 2L)  // 기존 경쟁자
                    )
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(205000L); // min(500000, 200000+5000)
            assertThat(result.finalWinnerUserId()).isEqualTo(2L);
            assertThat(result.candidateResults()).containsExactlyInAnyOrder(
                    new CandidateResult(9L, ProxyEntrantStatus.CAP_REACHED),
                    new CandidateResult(2L, ProxyEntrantStatus.ACTIVE)
            );
        }

        @Test
        void effectiveCap이_같으면_먼저_등록된_쪽이_이기고_가격은_동률cap까지만_오른다() {
            ProxyResolutionInput input = new ProxyResolutionInput(
                    105000L, INCREMENT, new ProxyTrigger.Auto(1L), // earlier가 이미 currentWinner
                    List.of(
                            candidate(1L, 200000L, T0, 1L), // earlier
                            candidate(2L, 200000L, T1, 2L)  // later, 같은 cap이지만 늦게 등록
                    )
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(200000L); // min(200000, 200000+5000)
            assertThat(result.finalWinnerUserId()).isEqualTo(1L);
            assertThat(result.candidateResults()).containsExactlyInAnyOrder(
                    new CandidateResult(1L, ProxyEntrantStatus.ACTIVE),
                    new CandidateResult(2L, ProxyEntrantStatus.CAP_REACHED)
            );
        }

        @Test
        void 삼파전에서는_최강과_차강만으로_가격이_정해지고_승자를_제외한_모두가_CAP_REACHED다() {
            ProxyResolutionInput input = new ProxyResolutionInput(
                    105000L, INCREMENT, new ProxyTrigger.Auto(null),
                    List.of(
                            candidate(1L, 500000L, T0, 1L), // 최강
                            candidate(3L, 400000L, T2, 3L), // 차강(등록은 가장 늦지만 cap이 커서 순위엔 영향 없다)
                            candidate(2L, 300000L, T1, 2L)  // 최약
                    )
            );

            ProxyResolution result = engine.resolve(input);

            // 최강(1)의 cap(500000)과 차강(3, cap 400000) 기준으로만 가격이 정해진다 - 3-way 반복 시뮬레이션 없음.
            // finalPrice(405000)는 항상 차강의 cap(400000)보다 크므로, 차강을 포함한 승자 외 전원이
            // CAP_REACHED가 된다 - "패자는 cap이 남아도 ACTIVE일 수 없다"는 가격 공식의 자연스러운 결과다.
            assertThat(result.finalCurrentPrice()).isEqualTo(405000L); // min(500000, 400000+5000)
            assertThat(result.finalWinnerUserId()).isEqualTo(1L);
            assertThat(result.candidateResults()).containsExactlyInAnyOrder(
                    new CandidateResult(1L, ProxyEntrantStatus.ACTIVE),
                    new CandidateResult(3L, ProxyEntrantStatus.CAP_REACHED),
                    new CandidateResult(2L, ProxyEntrantStatus.CAP_REACHED)
            );
        }

        @Test
        void 자기자신이_이미_currentWinner면_cap을_올려도_스스로에게_응찰하지_않는다() {
            ProxyResolutionInput input = new ProxyResolutionInput(
                    110000L, INCREMENT, new ProxyTrigger.Auto(9L),
                    List.of(candidate(9L, 300000L, T0, 1L))
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(110000L);
            assertThat(result.finalWinnerUserId()).isEqualTo(9L);
            assertThat(result.priceChanged()).isFalse();
            assertThat(result.resultingAutoBid()).isNull();
            assertThat(result.candidateResults()).containsExactly(new CandidateResult(9L, ProxyEntrantStatus.ACTIVE));
        }

        @Test
        void currentWinner가_AutoBid로_뒷받침되지_않는_manual_only_승자면_entrant는_한_단계만_응찰해서_이긴다() {
            ProxyResolutionInput input = new ProxyResolutionInput(
                    150000L, INCREMENT, new ProxyTrigger.Auto(4L), // 4L은 manual-only, candidates에 없다
                    List.of(candidate(9L, 300000L, T0, 1L))
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(155000L); // 150000 + 5000, entrant cap(300000) 아님
            assertThat(result.finalWinnerUserId()).isEqualTo(9L);
            assertThat(result.resultingAutoBid()).isEqualTo(new ResultingAutoBid(9L, 155000L));
        }

        @Test
        void 복수_ACTIVE_dirty_data에서도_실제로_가장_강한_경쟁자_기준으로_정상_판정된다() {
            // currentWinner 기록은 weak(2L)로 잘못 남아있지만, strong(3L)이 실제로는 더 강하다.
            ProxyResolutionInput input = new ProxyResolutionInput(
                    105000L, INCREMENT, new ProxyTrigger.Auto(2L),
                    List.of(
                            candidate(9L, 200000L, T2, 1L), // entrant
                            candidate(2L, 130000L, T0, 2L), // weak, dirty하게 currentWinner로 기록됨
                            candidate(3L, 400000L, T1, 3L)  // strong, 실제로 가장 강함
                    )
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(205000L); // min(400000, 200000+5000)
            assertThat(result.finalWinnerUserId()).isEqualTo(3L);
            assertThat(result.candidateResults()).containsExactlyInAnyOrder(
                    new CandidateResult(9L, ProxyEntrantStatus.CAP_REACHED),
                    new CandidateResult(2L, ProxyEntrantStatus.CAP_REACHED),
                    new CandidateResult(3L, ProxyEntrantStatus.ACTIVE)
            );
        }

        @Test
        void CANCELED는_engine_입력에_아예_존재하지_않으므로_경쟁에_영향을_주지_않는다() {
            // candidates 목록 자체가 이미 CANCELED를 제외하고 넘어온다는 계약(adapter 책임)을
            // 전제로, engine은 넘어온 candidates만으로 정상 계산한다는 것을 확인한다.
            ProxyResolutionInput input = new ProxyResolutionInput(
                    105000L, INCREMENT, new ProxyTrigger.Auto(null),
                    List.of(candidate(9L, 200000L, T0, 1L))
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.candidateResults()).containsExactly(new CandidateResult(9L, ProxyEntrantStatus.ACTIVE));
        }
    }

    @Nested
    class 트리거없는_정산 {

        @Test
        void 예약자가_0명이면_currentPrice가_그대로다() {
            ProxyResolutionInput input = new ProxyResolutionInput(100000L, INCREMENT, new ProxyTrigger.None(), List.of());

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(100000L);
            assertThat(result.resultingAutoBid()).isNull();
        }

        @Test
        void 예약자가_1명이면_최소_한_단계만_응찰하고_maxAmount로_바로_점프하지_않는다() {
            ProxyResolutionInput input = new ProxyResolutionInput(
                    100000L, INCREMENT, new ProxyTrigger.None(),
                    List.of(candidate(1L, 500000L, T0, 1L))
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(105000L); // 500000이 아니라 100000+5000
            assertThat(result.finalWinnerUserId()).isEqualTo(1L);
            assertThat(result.resultingAutoBid()).isEqualTo(new ResultingAutoBid(1L, 105000L));
            assertThat(result.candidateResults()).containsExactly(new CandidateResult(1L, ProxyEntrantStatus.ACTIVE));
        }

        @Test
        void 예약자가_2명이면_최강_차강_기준으로_정산되고_동률이면_FIRST_IN_WINS다() {
            ProxyResolutionInput input = new ProxyResolutionInput(
                    100000L, INCREMENT, new ProxyTrigger.None(),
                    List.of(
                            candidate(1L, 200000L, T0, 1L), // 먼저 등록
                            candidate(2L, 200000L, T1, 2L)  // 같은 cap, 나중 등록
                    )
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(200000L);
            assertThat(result.finalWinnerUserId()).isEqualTo(1L);
            assertThat(result.candidateResults()).containsExactlyInAnyOrder(
                    new CandidateResult(1L, ProxyEntrantStatus.ACTIVE),
                    new CandidateResult(2L, ProxyEntrantStatus.CAP_REACHED)
            );
        }

        @Test
        void 예약자가_2명이고_cap이_다르면_최강이_이기고_차강cap플러스증분까지만_오른다() {
            ProxyResolutionInput input = new ProxyResolutionInput(
                    100000L, INCREMENT, new ProxyTrigger.None(),
                    List.of(
                            candidate(1L, 300000L, T0, 1L),
                            candidate(2L, 150000L, T1, 2L)
                    )
            );

            ProxyResolution result = engine.resolve(input);

            assertThat(result.finalCurrentPrice()).isEqualTo(155000L); // min(300000, 150000+5000)
            assertThat(result.finalWinnerUserId()).isEqualTo(1L);
            assertThat(result.proxyResponded()).isFalse(); // 트리거리스 정산에서는 의미 없는 필드 - false가 기본값
        }
    }

    @Nested
    class 상태전이 {

        // "cap이 finalPrice와 정확히 같으면(초과가 아니면) CAP_REACHED다"라는 strict > 경계는
        // Auto_vs_Auto.effectiveCap이_같으면_먼저_등록된_쪽이_이긴다()에서 이미 검증한다 - 동률 낙선자의
        // cap이 finalPrice와 정확히 같은 채로 CAP_REACHED를 받는 것이 바로 그 경계 케이스다.

        @Test
        void CAP_REACHED였던_candidate도_cap이_충분히_오르면_다시_ACTIVE로_판정된다() {
            // engine은 "이전 상태"를 모른다 - 매번 cap 대 finalPrice만으로 새로 계산한다는 것을
            // 증명한다(=CAP_REACHED가 terminal이 아니라는 근거).
            ProxyResolutionInput before = new ProxyResolutionInput(
                    105000L, INCREMENT, new ProxyTrigger.Auto(2L),
                    List.of(
                            candidate(1L, 110000L, T0, 1L), // 낮은 cap - CAP_REACHED 예상
                            candidate(2L, 120000L, T1, 2L)
                    )
            );
            ProxyResolution beforeResult = engine.resolve(before);
            assertThat(beforeResult.candidateResults()).contains(new CandidateResult(1L, ProxyEntrantStatus.CAP_REACHED));

            // 같은 사용자(1L)가 cap을 대폭 올려 재입력하면(=changeMaxAmount 이후 재호출), 같은 engine이
            // 같은 candidate를 ACTIVE로 판정한다.
            ProxyResolutionInput after = new ProxyResolutionInput(
                    105000L, INCREMENT, new ProxyTrigger.Auto(2L),
                    List.of(
                            candidate(1L, 500000L, T0, 1L),
                            candidate(2L, 120000L, T1, 2L)
                    )
            );
            ProxyResolution afterResult = engine.resolve(after);
            assertThat(afterResult.candidateResults()).contains(new CandidateResult(1L, ProxyEntrantStatus.ACTIVE));
        }
    }

    @Nested
    class 불변식 {

        private final List<ProxyResolutionInput> scenarios = List.of(
                new ProxyResolutionInput(100000L, INCREMENT, new ProxyTrigger.None(), List.of()),
                new ProxyResolutionInput(100000L, INCREMENT, new ProxyTrigger.Manual(100000L, 1L), List.of()),
                new ProxyResolutionInput(100000L, INCREMENT, new ProxyTrigger.Manual(100000L, 1L),
                        List.of(candidate(2L, 90000L, T0, 10L))),
                new ProxyResolutionInput(100000L, INCREMENT, new ProxyTrigger.Manual(100000L, 1L),
                        List.of(candidate(2L, 300000L, T0, 10L))),
                new ProxyResolutionInput(105000L, INCREMENT, new ProxyTrigger.Auto(2L),
                        List.of(candidate(9L, 300000L, T1, 1L), candidate(2L, 120000L, T0, 2L))),
                new ProxyResolutionInput(100000L, INCREMENT, new ProxyTrigger.None(),
                        List.of(candidate(1L, 500000L, T0, 1L)))
        );

        @Test
        void finalPrice는_항상_initial_currentPrice_이상이다() {
            for (ProxyResolutionInput input : scenarios) {
                ProxyResolution result = engine.resolve(input);
                assertThat(result.finalCurrentPrice()).isGreaterThanOrEqualTo(input.currentPrice());
            }
        }

        @Test
        void winner가_Auto_candidate라면_finalPrice는_그_winner의_effectiveCap을_넘지_않는다() {
            for (ProxyResolutionInput input : scenarios) {
                ProxyResolution result = engine.resolve(input);
                if (result.finalWinnerUserId() == null) {
                    continue;
                }
                input.candidates().stream()
                        .filter(c -> c.userId().equals(result.finalWinnerUserId()))
                        .findFirst()
                        .ifPresent(winnerCandidate -> {
                            long cap = EffectiveCapCalculator.calculate(
                                    winnerCandidate.maxAmount(), input.currentPrice(), input.bidIncrement()
                            );
                            assertThat(result.finalCurrentPrice()).isLessThanOrEqualTo(cap);
                        });
            }
        }

        @Test
        void AUTO_Bid_금액은_해당_candidate의_maxAmount를_넘지_않는다() {
            for (ProxyResolutionInput input : scenarios) {
                ProxyResolution result = engine.resolve(input);
                ResultingAutoBid resultingAutoBid = result.resultingAutoBid();
                if (resultingAutoBid == null) {
                    continue;
                }
                ProxyCandidate winnerCandidate = input.candidates().stream()
                        .filter(c -> c.userId().equals(resultingAutoBid.winnerUserId()))
                        .findFirst()
                        .orElseThrow();
                assertThat(resultingAutoBid.amount()).isLessThanOrEqualTo(winnerCandidate.maxAmount());
            }
        }

        @Test
        void 같은_입력이면_항상_같은_결과다() {
            for (ProxyResolutionInput input : scenarios) {
                ProxyResolutionInput copy = new ProxyResolutionInput(
                        input.currentPrice(), input.bidIncrement(), input.trigger(), List.copyOf(input.candidates())
                );
                assertThat(engine.resolve(copy)).isEqualTo(engine.resolve(input));
            }
        }

        @Test
        void AUTO_Bid_금액은_항상_currentPrice_bidIncrement_배수_그리드를_따른다() {
            for (ProxyResolutionInput input : scenarios) {
                ProxyResolution result = engine.resolve(input);
                ResultingAutoBid resultingAutoBid = result.resultingAutoBid();
                if (resultingAutoBid == null) {
                    continue;
                }
                long diff = resultingAutoBid.amount() - input.currentPrice();
                assertThat(diff % input.bidIncrement()).isZero();
                assertThat(diff).isPositive();
            }
        }
    }
}
