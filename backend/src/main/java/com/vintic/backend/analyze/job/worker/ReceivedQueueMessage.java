package com.vintic.backend.analyze.job.worker;

// Poller가 Handler에 넘기는 최소 수신 객체. receiptHandle은 삭제 책임이 있는 poller만 보유하고
// handler에는 넘기지 않는다 - handler는 처리 결과(DELETE/RETAIN)만 결정한다.
//
// approximateReceiveCount는 SQS의 ApproximateReceiveCount system attribute를 그대로 담는다 -
// Processor가 실제로 몇 번 실행됐는지가 아니라 SQS가 이 메시지를 몇 번 배달 시도했는지의 근사치다
// (claim 실패, non-stale PROCESSING 재노출도 이 값을 증가시킬 수 있다). Poller가 속성을 못
// 받았거나 파싱에 실패하면 null이다 - Handler는 null을 "재시도 소진 여부를 알 수 없음"으로
// 보수적으로 취급해 RETAIN한다.
//
// sentTimestampEpochMillis는 SQS의 SentTimestamp system attribute(발행 시각)를, 받지
// 못했거나 파싱에 실패하면 null이다. receivedAtEpochMillis는 Poller가 이 메시지를 실제로
// 받은 시각(System.currentTimeMillis())이다 - queueWaitMs()가 이 둘로 "발행부터 수신까지"
// 걸린 시간을 계산한다.
public record ReceivedQueueMessage(
        String messageId,
        String body,
        Integer approximateReceiveCount,
        Long sentTimestampEpochMillis,
        long receivedAtEpochMillis) {

    // SentTimestamp를 확인할 수 없으면(null) queueWaitMs도 null이다 - 계산할 기준이 없다는
    // 뜻이지 대기시간이 0이라는 뜻이 아니므로 0으로 대체하지 않는다. 시계 오차 등으로 sent가
    // received보다 늦게 보일 수 있어 음수는 0으로 clamp한다.
    public Long queueWaitMs() {
        if (sentTimestampEpochMillis == null) {
            return null;
        }
        return Math.max(0L, receivedAtEpochMillis - sentTimestampEpochMillis);
    }
}
