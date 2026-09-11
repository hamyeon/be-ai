#!/bin/bash
# LocalStack 컨테이너 안에서(init hook: /etc/localstack/init/ready.d) 자동 실행된다 -
# LocalStack이 "ready" 상태가 된 뒤 한 번 돌고, 그게 끝나야 LocalStack 자체의 헬스체크가
# healthy로 바뀐다(고정 sleep 대신 이 순서 보장을 그대로 쓴다 - docker-compose.experiment.yml의
# api/worker는 localstack의 service_healthy를 depends_on 조건으로 기다린다).
#
# 반복 실행에도 안전(idempotent)하다: s3 mb는 head-bucket으로 존재를 먼저 확인하고,
# sqs create-queue는 이미 같은 속성으로 존재하는 큐에 대해 그대로 같은 URL을 반환한다
# (AWS SQS API 자체의 멱등 동작 - 별도 방어 코드가 필요 없다).
set -euo pipefail

BUCKET="vintic-mvp-bucket-123" # cloud.aws.s3.bucket(application.yml)과 반드시 같은 값 - 바꾸지 않았다.
MAIN_QUEUE_NAME="analysis-job-queue"
DLQ_NAME="analysis-job-dlq"
# analysis.worker.max-receive-count 기본값(SqsAnalysisJobHandler)과 반드시 같은 값을 쓴다.
MAX_RECEIVE_COUNT=3
VISIBILITY_TIMEOUT=90 # contracts.md Timeout 관계의 "SQS Visibility Timeout 90초 이상"

echo "[localstack-init] bucket=${BUCKET} 생성 확인 중..."
if ! awslocal s3api head-bucket --bucket "${BUCKET}" 2>/dev/null; then
  awslocal s3 mb "s3://${BUCKET}"
fi

echo "[localstack-init] DLQ(${DLQ_NAME}) 생성 중..."
DLQ_URL=$(awslocal sqs create-queue --queue-name "${DLQ_NAME}" --query QueueUrl --output text)
DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url "${DLQ_URL}" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

# RedrivePolicy 속성값 자체는 SQS 스펙상 "JSON을 담은 문자열"이라 바깥 --attributes JSON 안에서
# 따옴표를 한 번 더 이스케이프해야 한다 - jq 등 새 도구 없이 bash 파라미터 치환만으로 처리한다.
REDRIVE_POLICY_INNER=$(printf '{"deadLetterTargetArn":"%s","maxReceiveCount":%d}' "${DLQ_ARN}" "${MAX_RECEIVE_COUNT}")
REDRIVE_POLICY_ESCAPED=${REDRIVE_POLICY_INNER//\"/\\\"}
ATTRIBUTES=$(printf '{"VisibilityTimeout":"%s","RedrivePolicy":"%s"}' "${VISIBILITY_TIMEOUT}" "${REDRIVE_POLICY_ESCAPED}")

echo "[localstack-init] Main queue(${MAIN_QUEUE_NAME}) 생성 중 (maxReceiveCount=${MAX_RECEIVE_COUNT}, redrive -> ${DLQ_NAME})..."
MAIN_QUEUE_URL=$(awslocal sqs create-queue --queue-name "${MAIN_QUEUE_NAME}" \
  --attributes "${ATTRIBUTES}" \
  --query QueueUrl --output text)

echo "[localstack-init] 완료: bucket=${BUCKET} mainQueue=${MAIN_QUEUE_URL} dlq=${DLQ_URL} maxReceiveCount=${MAX_RECEIVE_COUNT}"

# localstack 서비스 자체의 /_localstack/health는 S3/SQS 엔진이 떴는지만 보고, 이 스크립트가
# 끝났는지는 모른다 - Compose healthcheck가 이 마커 파일 존재 여부로 "init까지 끝난 뒤에만
# healthy"를 판정하게 한다(고정 sleep 대신 이 방식으로 api/worker 기동 순서를 제어한다).
touch /tmp/localstack-init-complete
