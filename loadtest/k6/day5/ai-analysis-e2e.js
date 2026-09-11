// Day5 4단계 시나리오 3: AI 분석 E2E - presigned URL 발급 -> 실제 S3(LocalStack) PUT -> 실제
// 비동기 분석 제출 -> bounded polling -> COMPLETED. Processor는 experiment 환경의
// FakeAnalysisProcessor를 쓴다(analysis.processor.fake.delay-ms) - 실제 외부 AI를 호출하지 않는다.
//
// 대상은 docker-compose.experiment.yml 스택(API 프로필 local,experiment-api, Day5 3단계에서
// 이미 실제로 검증됨)이다 - local:8080의 실제 경매 도메인과는 별개 스택이다.
//
// 사전 준비:
//   1) backend 디렉터리에서 정확히 이 명령으로 스택이 떠 있어야 한다:
//        docker compose -f docker-compose.experiment.yml up -d --build
//   2) X-User-Id로 쓸 실제 users row가 vintic_experiment DB에 있어야 한다(MockUserRegistry가
//      존재 여부를 조회한다 - 존재하지 않는 임의 헤더는 그냥 인증 실패로 거절된다).
//
// 실행 예:
//   BASE_URL=http://localhost:8090 VUs=1 duration=30s USER_ID=910001 k6 run ai-analysis-e2e.js
import http from 'k6/http';
import { readCommonEnv, requestPresignedUrl, uploadToS3, submitAsyncAnalysis, boundedPollAnalysisStatus, newE2eMetrics, maskUrl } from './common.js';

const env = readCommonEnv({ baseUrl: 'http://localhost:8090' });
const USER_ID = __ENV.USER_ID || '910001';
const POLL_MAX_ATTEMPTS = parseInt(__ENV.POLL_MAX_ATTEMPTS || '40', 10);
const POLL_INTERVAL_SECONDS = parseFloat(__ENV.POLL_INTERVAL_SECONDS || '3');

export const options = {
  scenarios: {
    ai_analysis_e2e: {
      executor: 'constant-vus',
      vus: env.vus,
      duration: env.duration,
      exec: 'aiAnalysisE2e',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};

const metrics = newE2eMetrics('ai_analysis');

export function aiAnalysisE2e() {
  const start = Date.now();

  const presigned = requestPresignedUrl(env.baseUrl);
  if (!presigned) {
    console.log(`ai_analysis FAIL stage=presign baseUrl=${env.baseUrl}`);
    metrics.successRate.add(false);
    return;
  }
  console.log(`ai_analysis presign ok objectKey=${presigned.objectKey} uploadUrl=${maskUrl(presigned.uploadUrl)}`);

  const dummyBytes = `k6-day5-ai-analysis-dummy-${__VU}-${__ITER}-${Date.now()}`;
  const putOk = uploadToS3(presigned.uploadUrl, dummyBytes);
  if (!putOk) {
    console.log(`ai_analysis FAIL stage=s3_put objectKey=${presigned.objectKey}`);
    metrics.successRate.add(false);
    return;
  }

  const idempotencyKey = `k6-day5-ai-${__VU}-${__ITER}-${Math.random().toString(36).slice(2)}`;
  const submitted = submitAsyncAnalysis(env.baseUrl, USER_ID, presigned.objectKey, idempotencyKey);
  if (!submitted || !submitted.analysisId) {
    console.log(`ai_analysis FAIL stage=submit objectKey=${presigned.objectKey}`);
    metrics.successRate.add(false);
    return;
  }
  console.log(`ai_analysis submitted analysisId=${submitted.analysisId} status=${submitted.status}`);

  const polled = boundedPollAnalysisStatus(env.baseUrl, USER_ID, submitted.analysisId, {
    maxAttempts: POLL_MAX_ATTEMPTS,
    intervalSeconds: POLL_INTERVAL_SECONDS,
  });

  const elapsed = Date.now() - start;
  metrics.duration.add(elapsed);
  const ok = polled.finalStatus === 'COMPLETED';
  metrics.successRate.add(ok);
  console.log(`ai_analysis result analysisId=${submitted.analysisId} finalStatus=${polled.finalStatus} attempts=${polled.attempts} elapsedMs=${elapsed}`);
}
