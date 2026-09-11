package com.vintic.backend.analyze.job.worker;

// Poller가 Handler에 넘기는 최소 수신 객체. receiptHandle은 삭제 책임이 있는 poller만 보유하고
// handler에는 넘기지 않는다 - handler는 처리 결과(DELETE/RETAIN)만 결정한다.
//
// approximateReceiveCount는 SQS의 ApproximateReceiveCount system attribute를 그대로 담는다 -
// Processor가 실제로 몇 번 실행됐는지가 아니라 SQS가 이 메시지를 몇 번 배달 시도했는지의 근사치다
// (claim 실패, non-stale PROCESSING 재노출도 이 값을 증가시킬 수 있다). Poller가 속성을 못
// 받았거나 파싱에 실패하면 null이다 - Handler는 null을 "재시도 소진 여부를 알 수 없음"으로
// 보수적으로 취급해 RETAIN한다.
public record ReceivedQueueMessage(String messageId, String body, Integer approximateReceiveCount) {
}
