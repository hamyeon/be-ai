#!/usr/bin/env bash
set -Eeuo pipefail

# 배포 자동 게이트(scripts/aws/deploy.sh)는 API/Worker 프로세스가 "떠 있는지"만 확인한다.
# 이 스크립트는 그 다음 단계 - 실제 사진 1장으로 analyze -> Worker 소비 -> Vision 호출 ->
# COMPLETED까지 실제로 이어지는지를 사람이 눈으로 확인한다. 매 배포 자동 실행하지 않는 이유는
# OpenAI Vision 호출이 유료라서다(scripts/aws/deploy.sh 출력 참고).
#
# analyze가 인증 필수로 바뀌었으므로(JwtSecurityConfig, 2026-09) 실제 로그인 토큰이 필요하다.
# 카카오 로그인 자체는 프론트가 처리하므로, 이 스크립트가 로그인까지 대신할 수는 없다 - 프론트나
# 브라우저 개발자도구로 먼저 로그인해 이 서버가 발급한 Access Token(JWT, 카카오 토큰이 아니다)을
# 받아온 뒤 ACCESS_TOKEN으로 넘겨준다.
#
# 사용법:
#   BASE_URL=https://<api-ip>.sslip.io ACCESS_TOKEN=<발급받은 JWT> \
#     bash scripts/aws/smoke-analyze.sh path/to/test-shoe.jpg

BASE_URL="${BASE_URL:?BASE_URL 환경변수가 필요합니다 (예: https://<api-ip>.sslip.io)}"
ACCESS_TOKEN="${ACCESS_TOKEN:?ACCESS_TOKEN 환경변수가 필요합니다 (이 서버가 발급한 JWT Access Token)}"
IMAGE_PATH="${1:?사용법: BASE_URL=... ACCESS_TOKEN=... bash scripts/aws/smoke-analyze.sh <이미지 경로>}"

test -f "$IMAGE_PATH" || {
    echo "ERROR: 이미지 파일을 찾을 수 없습니다: ${IMAGE_PATH}" >&2
    exit 2
}

log() {
    printf '[smoke-analyze] %s\n' "$*"
}

log "이미지 업로드 및 분석 요청 제출 중..."
submit_response="$(curl --fail --silent --show-error \
    -X POST "${BASE_URL}/api/products/analyze" \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -F "images=@${IMAGE_PATH}")"

echo "$submit_response"

task_id="$(echo "$submit_response" | jq -r '.data.analysisId')"
if [ -z "$task_id" ] || [ "$task_id" = "null" ]; then
    echo "ERROR: 응답에서 analysisId를 읽지 못했습니다." >&2
    exit 3
fi

log "taskId=${task_id} - 상태 폴링 시작(최대 3분, Vision 처리 상한 analysis.vision.overall-timeout-ms 기본 180s + 여유)"

status="QUEUED"
for attempt in $(seq 1 36); do
    status_response="$(curl --fail --silent --show-error \
        "${BASE_URL}/api/products/analyze/${task_id}" \
        -H "Authorization: Bearer ${ACCESS_TOKEN}")"
    status="$(echo "$status_response" | jq -r '.data.status')"
    log "attempt=${attempt} status=${status}"

    case "$status" in
        AWAITING_USER_CONFIRMATION)
            log "SMOKE_ANALYZE_PASS - Vision 분석이 완료됐습니다."
            echo "$status_response" | jq '.data | {brand, modelName, conditionGrade, needsUserConfirmation}'
            exit 0
            ;;
        VISION_FAILED|IMAGE_UPLOAD_FAILED|QUEUE_FAILED)
            log "SMOKE_ANALYZE_FAIL - 실패 상태로 종료됐습니다."
            echo "$status_response" | jq '.data | {status, failureStage, failureMessage}'
            exit 1
            ;;
    esac

    sleep 5
done

log "SMOKE_ANALYZE_TIMEOUT - 제한 시간 안에 완료되지 않았습니다. 마지막 상태=${status}"
exit 4
