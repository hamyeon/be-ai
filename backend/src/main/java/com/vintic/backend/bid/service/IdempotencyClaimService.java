package com.vintic.backend.bid.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.bid.domain.Idempotency;
import com.vintic.backend.bid.repository.IdempotencyRepository;
import com.vintic.backend.common.exception.IdempotencyPayloadMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Function;

// claim + 커맨드 실행 + 결과 기록을 하나의 트랜잭션으로 묶는 계층이다.
// 이 빈의 메서드는 반드시 ManualBidService/AutoBidService처럼 이 빈을 주입받아 호출하는
// 다른 빈에서 호출해야 한다. 같은 클래스 안에서 this로 서로를 호출하면 Spring 프록시를
// 우회해 @Transactional이 적용되지 않는다.
//
// PLACE_BID 전용 claimAndPlaceBid/resolveAfterConflict(#32)는 제거했다 - PlaceBidResponse가
// FINAL contract shape로 확장되며 Bid row 하나만으로 원본 응답을 재구성할 수 없어(예: Proxy
// resolution 이후의 highestBidderMasked/proxyResponded 등) resultBidId 기반 replay가 더 이상
// 성립하지 않는다. 지금은 이 제네릭 claimAndExecute/resolveAfterConflict 하나로 모든 커맨드
// (PLACE_BID/CREATE_AUTO_BID/UPDATE_AUTO_BID)가 최초 성공 응답을 JSON snapshot으로 저장했다가
// 그대로 replay한다. 기존 메서드들이 보장하던 트랜잭션 경계/충돌 후 별도 트랜잭션 조회 동작은
// 동일하게 유지된다 - ManualBidIdempotencyMySqlIT로 회귀 검증했다.
//
// #45: command가 Supplier<T>에서 Function<Long, T>로 바뀌었다 - claim insert 직후 확보한
// claim.getId()를 커맨드에 넘겨, AuctionPriceAudit이 raw Idempotency-Key를 복제하지 않고
// 이 PK만 참조로 남길 수 있게 한다. claim/replay/충돌 판정 로직 자체는 전혀 바뀌지 않았다 -
// 순수 배관(plumbing) 변경이다.
@Service
public class IdempotencyClaimService {

    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyClaimService(
            IdempotencyRepository idempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    // command.get()이 던지는 예외(예: AutoBidAlreadyExistsException)는 그대로 전파해 트랜잭션을
    // 롤백시킨다 - 실패한 시도로 idempotency key를 영구히 소모시키지 않기 위함이다(claim insert도
    // 같은 트랜잭션이므로 함께 롤백된다).
    @Transactional
    public <T> T claimAndExecute(
            Long userId, String operationScope, String idempotencyKey, String requestHash,
            Class<T> responseType, Function<Long, T> command
    ) {
        Optional<Idempotency> existing = idempotencyRepository
                .findByUserIdAndOperationScopeAndIdempotencyKey(userId, operationScope, idempotencyKey);
        if (existing.isPresent()) {
            return replaySnapshotOrReject(existing.get(), requestHash, responseType);
        }

        Idempotency claim = Idempotency.claim(userId, operationScope, idempotencyKey, requestHash);
        try {
            // claim insert를 지금 실제로 DB에 반영해야 동시 요청의 UNIQUE 경쟁이 여기서 발생한다.
            // commit 시점까지 미루면 두 요청 모두 커맨드까지 처리한 뒤에야 충돌을 발견하게 된다.
            idempotencyRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException e) {
            // 이 시점 이후로는 같은 트랜잭션에서 추가 DB 작업을 하지 않는다.
            // 그대로 던져서 트랜잭션을 롤백시키고, 조회는 별도 트랜잭션(resolveAfterConflict)에 맡긴다.
            throw new IdempotencyClaimConflictException(e);
        }

        T response = command.apply(claim.getId());
        claim.attachResponseSnapshot(writeSnapshot(response));
        return response;
    }

    @Transactional
    public <T> T resolveAfterConflict(
            Long userId, String operationScope, String idempotencyKey, String requestHash, Class<T> responseType
    ) {
        // UNIQUE 충돌 시점에 이긴 트랜잭션은 이미 commit되어 있음이 보장된다(InnoDB가
        // 같은 unique key의 두 번째 INSERT를 첫 트랜잭션의 commit/rollback까지 블로킹하기 때문).
        // 따라서 여기서는 재조회만으로 충분하고 재시도 루프가 필요 없다.
        Idempotency existing = idempotencyRepository
                .findByUserIdAndOperationScopeAndIdempotencyKey(userId, operationScope, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "UNIQUE 충돌 이후에도 기존 Idempotency row를 찾지 못했습니다. userId=" + userId
                                + ", operationScope=" + operationScope));
        return replaySnapshotOrReject(existing, requestHash, responseType);
    }

    private <T> T replaySnapshotOrReject(Idempotency existing, String requestHash, Class<T> responseType) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyPayloadMismatchException(
                    "동일한 Idempotency-Key로 이전과 다른 요청 내용이 감지되었습니다."
            );
        }
        return readSnapshot(existing.getResponseSnapshot(), responseType);
    }

    private <T> String writeSnapshot(T response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("응답 snapshot 직렬화에 실패했습니다.", e);
        }
    }

    // jackson-datatype-jsr310은 기본적으로 OffsetDateTime 역직렬화 시 ObjectMapper의 기본 timezone에
    // 맞춰 오프셋을 조정한다(ADJUST_DATES_TO_CONTEXT_TIME_ZONE) - ObjectMapper의 기본 timezone을
    // 앱 정책과 동일한 Asia/Seoul로 맞춰뒀으므로(JacksonConfig) 이 조정 자체가 +09:00을 그대로
    // 유지시켜 replay 응답이 최초 응답과 완전히 동일하게 나온다.
    private <T> T readSnapshot(String json, Class<T> responseType) {
        try {
            return objectMapper.readValue(json, responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("응답 snapshot 역직렬화에 실패했습니다.", e);
        }
    }
}
