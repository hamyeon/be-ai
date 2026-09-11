// Day5 4단계 k6 공용 모듈. 세 시나리오(auction-browse.js, auction-bid.js, ai-analysis-e2e.js)가
// 이 파일 하나만 import해서 env 파싱/인증 헤더/응답 검증/presign+S3 PUT/비동기 제출/bounded
// polling/시간 측정/로그 마스킹을 공유한다 - 각 스크립트에 중복 구현하지 않는다.
//
// 인증: 이 저장소의 local 프로필은 JWT가 아니라 X-User-Id 헤더 + MockAuthInterceptor를 쓴다
// (loadtest/k6/README.md "인증 경계" 절과 동일한 제약 - dev/prod/AWS에는 그대로 못 쓴다).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// ---- 공통 실행 설정(BASE_URL/VUs/duration) ----
// VUs/duration은 세 스크립트의 options에서 직접 쓰지만, 파싱과 기본값은 여기 한 곳에 둔다.
export function readCommonEnv(defaults) {
  const d = defaults || {};
  return {
    baseUrl: __ENV.BASE_URL || d.baseUrl || 'http://localhost:8080',
    vus: parseInt(__ENV.VUs || __ENV.VUS || d.vus || '1', 10),
    duration: __ENV.duration || __ENV.DURATION || d.duration || '30s',
  };
}

// ---- 인증 헤더 ----
export function authHeaders(userId, extra) {
  const headers = { 'Content-Type': 'application/json' };
  if (userId !== undefined && userId !== null) {
    headers['X-User-Id'] = String(userId);
  }
  return Object.assign(headers, extra || {});
}

// ---- 공통 응답 검증 ----
// 이 저장소의 계약(ApiResponse<T>{success,data,error})을 그대로 전제한다 - 가상의 wrapper를
// 만들지 않는다.
export function checkApiResponse(res, expectedStatus, label) {
  const ok = check(res, {
    [`${label}: status===${expectedStatus}`]: (r) => r.status === expectedStatus,
    [`${label}: body is valid JSON`]: (r) => {
      try {
        JSON.parse(r.body);
        return true;
      } catch (e) {
        return false;
      }
    },
  });
  return ok;
}

export function parseApiData(res) {
  try {
    return JSON.parse(res.body).data;
  } catch (e) {
    return null;
  }
}

// ---- 로그 마스킹 ----
// presigned URL의 쿼리스트링(X-Amz-Signature 등)과 Authorization 헤더 값은 절대 그대로
// 로그에 남기지 않는다 - 서명/자격증명 자체가 사용 권한이기 때문이다(PresignedUploadService
// 주석과 동일한 원칙).
export function maskUrl(url) {
  if (!url) return url;
  const qIdx = url.indexOf('?');
  return qIdx === -1 ? url : url.slice(0, qIdx) + '?<masked>';
}

export function maskAuthHeader(value) {
  if (!value) return value;
  return value.slice(0, 12) + '...<masked>';
}

// ---- Presigned URL 발급 + 실제 S3 PUT (AI 분석 시나리오 전용, experiment 스택 대상) ----
export function requestPresignedUrl(baseUrl) {
  const res = http.post(`${baseUrl}/api/uploads/presigned-url`, null, {
    tags: { name: 'presign' },
  });
  checkApiResponse(res, 200, 'presign');
  const data = parseApiData(res);
  return data ? { objectKey: data.objectKey, uploadUrl: data.uploadUrl } : null;
}

export function uploadToS3(uploadUrl, bytes) {
  const res = http.put(uploadUrl, bytes, { tags: { name: 'presigned_put' } });
  const ok = check(res, { 'presigned PUT: status===200': (r) => r.status === 200 });
  return ok;
}

// ---- 비동기 분석 제출 ----
export function submitAsyncAnalysis(baseUrl, userId, objectKey, idempotencyKey) {
  const res = http.post(
    `${baseUrl}/api/analyses`,
    JSON.stringify({ objectKey: objectKey }),
    { headers: authHeaders(userId, { 'Idempotency-Key': idempotencyKey }), tags: { name: 'submit_async_analysis' } }
  );
  checkApiResponse(res, 202, 'submit_async') || checkApiResponse(res, 200, 'submit_async');
  const data = parseApiData(res);
  return data ? { analysisId: data.analysisId, status: data.status } : null;
}

// ---- bounded status polling ----
// 실패 terminal 상태(FAILED/PUBLISH_FAILED)를 만나면 더 기다리지 않고 즉시 중단한다 -
// 끝나지 않을 폴링으로 VU를 낭비하지 않기 위함.
const TERMINAL_FAILURE_STATUSES = new Set(['FAILED', 'PUBLISH_FAILED']);

export function boundedPollAnalysisStatus(baseUrl, userId, analysisId, opts) {
  const maxAttempts = (opts && opts.maxAttempts) || 30;
  const intervalSeconds = (opts && opts.intervalSeconds) || 2;
  for (let i = 0; i < maxAttempts; i++) {
    const res = http.get(`${baseUrl}/api/analyses/${analysisId}`, {
      headers: authHeaders(userId),
      tags: { name: 'poll_analysis_status' },
    });
    const data = parseApiData(res);
    const status = data ? data.status : null;
    if (status === 'COMPLETED') {
      return { finalStatus: status, attempts: i + 1 };
    }
    if (status && TERMINAL_FAILURE_STATUSES.has(status)) {
      return { finalStatus: status, attempts: i + 1, failed: true };
    }
    sleep(intervalSeconds);
  }
  return { finalStatus: 'TIMEOUT', attempts: maxAttempts, failed: true };
}

// ---- E2E 시간 측정용 공용 Trend/Rate ----
// 세 스크립트가 이름을 구분해서 쓸 수 있도록 팩토리로 제공한다(k6 metric은 스크립트당
// 한 번만 등록해야 하므로, 각 시나리오 파일의 최상위 스코프에서 한 번만 호출해서 써야 한다).
export function newE2eMetrics(prefix) {
  return {
    duration: new Trend(`${prefix}_e2e_duration`, true),
    successRate: new Rate(`${prefix}_success_rate`),
  };
}
