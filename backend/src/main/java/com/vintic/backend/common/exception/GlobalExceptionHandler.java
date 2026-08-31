package com.vintic.backend.common.exception;

import com.vintic.backend.common.auth.mock.MockAuthException;
import com.vintic.backend.common.dto.ApiResponse;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("유효하지 않은 요청입니다.");

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(40001, message));
    }

    // mock 인증 실패: X-User-Id 헤더 누락/형식 오류/존재하지 않는 사용자 (401 Unauthorized)
    @ExceptionHandler(MockAuthException.class)
    public ResponseEntity<ApiResponse<Void>> handleMockAuthException(MockAuthException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(40101, e.getMessage()));
    }

    // 필수 요청 헤더 누락 (400 Bad Request) — 없으면 catch-all Exception 핸들러가 500으로 잘못 응답한다.
    // 위 MockAuthException(401)과는 다른 경로다: 이쪽은 컨트롤러 @RequestHeader 바인딩
    // 단계에서 발생하므로 인증 실패가 아니라 요청 형식 문제로 본다.
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestHeaderException(MissingRequestHeaderException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(40004, e.getHeaderName() + " 헤더가 없습니다."));
    }

    // 빈 이미지 에러 처리 (400 Bad Request)
    @ExceptionHandler(InvalidImageException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidImageException(InvalidImageException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(40002, e.getMessage()));
    }

    // S3 업로드 에러 처리 (500 Internal Server Error)
    @ExceptionHandler(S3UploadException.class)
    public ResponseEntity<ApiResponse<Void>> handleS3UploadException(S3UploadException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(50002, e.getMessage()));
    }

    // OpenAI 통신 에러 처리 (500 Internal Server Error)
    @ExceptionHandler(AiApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleAiApiException(AiApiException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(50003, e.getMessage()));
    }

    // 존재하지 않는 분석 세션 (404 Not Found)
    @ExceptionHandler(AnalysisSessionNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleAnalysisSessionNotFoundException(AnalysisSessionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(40401, e.getMessage()));
    }

    // 잘못된 분석 상태에서의 요청 (400 Bad Request)
    @ExceptionHandler(InvalidAnalysisStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidAnalysisStatusException(InvalidAnalysisStatusException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(40003, e.getMessage()));
    }

    // 분석 작업을 Redis Stream에 적재하지 못한 경우 (500 Internal Server Error)
    @ExceptionHandler(AnalysisQueueException.class)
    public ResponseEntity<ApiResponse<Void>> handleAnalysisQueueException(AnalysisQueueException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(50004, e.getMessage()));
    }

    // 존재하지 않는 경매 조회 (404 Not Found)
    // #46: FINAL contract §0-A는 40401=AUCTION_NOT_FOUND, 40402=ORDER_NOT_FOUND로 확정한다.
    // Order 도메인이 아직 없어 40402가 다른 예외에 점유되지 않은 상태를 확인한 뒤 40402→40401로
    // 옮겼다(단독 renumbering, 다른 4xx 코드는 이번에 건드리지 않았다).
    @ExceptionHandler(AuctionNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuctionNotFoundException(AuctionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(40401, e.getMessage()));
    }

    // 존재하지 않는 사용자 참조 (401 Unauthorized)
    // #56-2: FINAL contract §0-A는 40403=BACKUP_OFFER_NOT_FOUND로 확정한다. UserNotFoundException이
    // 그 자리를 점유하고 있었는데, BackupOffer 도메인이 이번에 실제로 40403을 쓰기 시작해 번호가
    // 충돌한다(#56-0/#56-1에서 이미 예견하고 남겨둔 gap). 기존 6개 호출부(AuctionQueryService x2/
    // BidCommandService/AutoBidCommandService/AuctionLikeCommandService/ProductRegistrationService)를
    // 전수 확인한 결과 전부 "MockAuthInterceptor가 인증 시점(401/40101)에 이미 존재를 검증한
    // currentUserId"를 서비스 내부에서 재조회하는 방어적 중복 체크였다 - 별도의 public
    // "USER_NOT_FOUND" semantics가 필요한 신규 요구가 아니므로, #56-0 §9가 정한 대로
    // "인증/current user resolution 실패는 기존 40101 흐름 사용"에 맞춰 40403/404에서 40101/401로
    // 옮긴다. 이 코드가 실제로 응답에 노출될 일은 production에서 거의 없다(인터셉터가 먼저 막는다).
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleUserNotFoundException(UserNotFoundException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(40101, e.getMessage()));
    }

    // 존재하지 않는 차순위 제안 (404 Not Found)
    @ExceptionHandler(BackupOfferNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleBackupOfferNotFoundException(BackupOfferNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(40403, e.getMessage()));
    }

    // 차순위 구매 기한 만료 후 accept 시도 (409 Conflict)
    @ExceptionHandler(BackupOfferExpiredException.class)
    public ResponseEntity<ApiResponse<Void>> handleBackupOfferExpiredException(BackupOfferExpiredException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(40911, e.getMessage()));
    }

    // 이미 처리된(ACCEPTED/DECLINED) 제안에 accept/decline 재시도 (409 Conflict)
    @ExceptionHandler(BackupOfferAlreadyResolvedException.class)
    public ResponseEntity<ApiResponse<Void>> handleBackupOfferAlreadyResolvedException(BackupOfferAlreadyResolvedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(40912, e.getMessage()));
    }

    // 낙찰자가 아닌 사용자의 forfeit 시도 (403 Forbidden)
    @ExceptionHandler(NotAwardeeException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotAwardeeException(NotAwardeeException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(40303, e.getMessage()));
    }

    // 결제 완료 후 낙찰 포기 시도 (409 Conflict)
    @ExceptionHandler(AlreadyPaidException.class)
    public ResponseEntity<ApiResponse<Void>> handleAlreadyPaidException(AlreadyPaidException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(40914, e.getMessage()));
    }

    // 결제 기한 만료 (409 Conflict) - Order를 PAYMENT_EXPIRED로 전이시키는 scheduler(#57)가
    // 아직 없어 이 핸들러는 현재 production 경로로는 도달하지 않는다(PaymentExpiredException 참고).
    @ExceptionHandler(PaymentExpiredException.class)
    public ResponseEntity<ApiResponse<Void>> handlePaymentExpiredException(PaymentExpiredException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(40910, e.getMessage()));
    }

    // 판매자 본인 입찰 시도 (403 Forbidden)
    @ExceptionHandler(SellerCannotBidException.class)
    public ResponseEntity<ApiResponse<Void>> handleSellerCannotBidException(SellerCannotBidException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(40301, e.getMessage()));
    }

    // 입찰 제한 기간 중인 사용자의 입찰 시도 (403 Forbidden)
    @ExceptionHandler(PenaltyRestrictedException.class)
    public ResponseEntity<ApiResponse<Void>> handlePenaltyRestrictedException(PenaltyRestrictedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(40302, e.getMessage()));
    }

    // 현재 최고입찰자의 추가 직접 입찰 시도 (409 Conflict)
    @ExceptionHandler(AlreadyHighestBidderException.class)
    public ResponseEntity<ApiResponse<Void>> handleAlreadyHighestBidderException(AlreadyHighestBidderException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(40901, e.getMessage()));
    }

    // 아직 시작되지 않은 경매에 대한 입찰 시도 (409 Conflict)
    @ExceptionHandler(AuctionNotStartedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuctionNotStartedException(AuctionNotStartedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(40902, e.getMessage()));
    }

    // 종료되었거나 취소된 경매에 대한 입찰 시도 (409 Conflict)
    @ExceptionHandler(AuctionClosedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuctionClosedException(AuctionClosedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(40903, e.getMessage()));
    }

    // 최소 입찰 금액 미만 (409 Conflict)
    @ExceptionHandler(BidAmountTooLowException.class)
    public ResponseEntity<ApiResponse<Void>> handleBidAmountTooLowException(BidAmountTooLowException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(40904, e.getMessage()));
    }

    // 최소금액 이상이지만 bidIncrement 배수로 정렬되지 않음 (409 Conflict)
    @ExceptionHandler(BidNotAlignedException.class)
    public ResponseEntity<ApiResponse<Void>> handleBidNotAlignedException(BidNotAlignedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(40913, e.getMessage()));
    }

    // 동일 Idempotency-Key에 이전과 다른 요청 내용이 감지됨 (409 Conflict)
    @ExceptionHandler(IdempotencyPayloadMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdempotencyPayloadMismatchException(IdempotencyPayloadMismatchException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(40905, e.getMessage()));
    }

    // 등록된 자동입찰이 없음 (404 Not Found)
    @ExceptionHandler(AutoBidNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleAutoBidNotFoundException(AutoBidNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(40404, e.getMessage()));
    }

    // 자동입찰 상한가가 minCapAmount 미만 (409 Conflict)
    @ExceptionHandler(CapTooLowException.class)
    public ResponseEntity<ApiResponse<Void>> handleCapTooLowException(CapTooLowException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(40906, e.getMessage()));
    }

    // ACTIVE/CAP_REACHED 상태에서 상한가를 올리지 않고 수정 시도 (409 Conflict)
    @ExceptionHandler(CapNotIncreasedException.class)
    public ResponseEntity<ApiResponse<Void>> handleCapNotIncreasedException(CapNotIncreasedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(40907, e.getMessage()));
    }

    // 이미 현재 자동입찰 설정이 존재함(RESERVED/ACTIVE/CAP_REACHED) (409 Conflict)
    @ExceptionHandler(AutoBidAlreadyExistsException.class)
    public ResponseEntity<ApiResponse<Void>> handleAutoBidAlreadyExistsException(AutoBidAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(40908, e.getMessage()));
    }

    // Auction row PESSIMISTIC_WRITE 획득 실패(락 대기 타임아웃/데드락) (409 Conflict)
    // - Spring 예외 계층: CannotAcquireLockException(락 대기 타임아웃)과
    //   DeadlockLoserDataAccessException(데드락 희생자)이 모두 이 클래스의 하위 타입이다.
    //   #45에서 이 두 경로만 좁게 매핑한다 - 일반 DataAccessException까지 넓히지 않는다.
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handlePessimisticLockingFailureException(PessimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(40909, "다른 요청과 충돌이 발생했습니다. 잠시 후 다시 시도해주세요."));
    }

    // 존재하지 않는 경로 (404 Not Found)
    //
    // 이 핸들러가 없으면 아래 Exception 포괄 핸들러가 잡아서 500을 준다. 그러면 오타 난 URL과
    // 서버 장애가 응답으로 구분되지 않는다. 헬스체크나 모니터링이 경로를 잘못 치면
    // "앱이 죽었다"로 읽힌다. 관리 엔드포인트를 별도 포트로 분리하면서 실제로 겪었다.
    //
    // 부수 효과로 로그도 정리된다. 포괄 핸들러가 printStackTrace를 호출하기 때문에
    // 그동안 404가 날 때마다 스택트레이스가 찍히고 있었다.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(40400, "존재하지 않는 경로입니다: " + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        // 1. 에러 내용을 콘솔에 강제로 출력
        exception.printStackTrace();

        // 2. 개발자를 위한 상세 메시지 (실제 서비스에서는 보안상 숨겨야 하지만, 지금은 개발 중이니 출력)
        String detailMessage = exception.getMessage();

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(50001, "서버 내부 오류: " + detailMessage));
    }
}