#!/usr/bin/env bash
set -Eeuo pipefail

# GitHub Actions(.github/workflows/deploy.yml)가 호출하는 배포 스크립트. 같은 이미지(app-<sha>)를
# API/Worker EC2 각각에 SSM RunCommand로 배포하고, 각자의 /actuator/health로 실제 준비 상태를
# 확인한다(이미지가 ECR에 올라간 것 자체는 성공 판정 근거가 아니다). 어느 한쪽이라도 실패하면
# 이전 정상 이미지(last-known-good SHA, SSM Parameter Store)로 두 역할 모두 롤백한다.
#
# 이전 인프라 실험(day9 스크립트)에서 가져온 것: 태그로 인스턴스를 찾는 방식, SSM RunCommand로
# 원격 실행 후 폴링하는 방식, last-known-good 파라미터 기반 롤백 로직.
# 가져오지 않은 것: SQS/ALB 전제 코드 전부(ELB 타겟그룹 헬스 대기, day7-e2e SQS 캐너리) - 지금은
# ALB가 없고(Caddy가 직접 EC2에서 TLS 종료) Redis Streams 기반이라 해당 없음.

for required_name in \
    AWS_REGION AWS_ACCOUNT_ID ECR_REPOSITORY IMAGE_SHA \
    PROJECT_TAG ENVIRONMENT_TAG LAST_GOOD_PARAMETER; do
    if [ -z "${!required_name:-}" ]; then
        echo "ERROR: required environment variable is missing: ${required_name}" >&2
        exit 2
    fi
done

if [[ ! "$IMAGE_SHA" =~ ^[0-9a-f]{40}$ ]]; then
    echo "ERROR: IMAGE_SHA must be a full 40-character Git SHA: ${IMAGE_SHA}" >&2
    exit 2
fi

ECR_REGISTRY="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
NEW_IMAGE_URI="${ECR_REGISTRY}/${ECR_REPOSITORY}:app-${IMAGE_SHA}"

log() {
    printf '[deploy] %s\n' "$*"
}

find_running_instances() {
    local role="$1"
    aws ec2 describe-instances \
        --region "$AWS_REGION" \
        --filters \
            "Name=tag:Project,Values=${PROJECT_TAG}" \
            "Name=tag:Environment,Values=${ENVIRONMENT_TAG}" \
            "Name=tag:Role,Values=${role}" \
            "Name=instance-state-name,Values=running" \
        --query 'Reservations[].Instances[].InstanceId' \
        --output text \
        --no-cli-pager \
        | tr '\t' '\n' \
        | sed '/^$/d; /^None$/d' \
        | sort -u
}

mapfile -t API_INSTANCE_IDS < <(find_running_instances api)
mapfile -t WORKER_INSTANCE_IDS < <(find_running_instances worker)

log "running API targets=${#API_INSTANCE_IDS[@]} ids=${API_INSTANCE_IDS[*]:-none}"
log "running Worker targets=${#WORKER_INSTANCE_IDS[@]} ids=${WORKER_INSTANCE_IDS[*]:-none}"

if [ "${#API_INSTANCE_IDS[@]}" -eq 0 ] && [ "${#WORKER_INSTANCE_IDS[@]}" -eq 0 ]; then
    log "DEPLOY_SKIPPED_ALL_TARGETS_STOPPED"
    exit 0
fi

if [ "${#API_INSTANCE_IDS[@]}" -eq 0 ] || [ "${#WORKER_INSTANCE_IDS[@]}" -eq 0 ]; then
    log "ERROR: partial running target set. Refusing to create API/Worker image SHA mismatch."
    exit 3
fi

PREVIOUS_SHA="$(aws ssm get-parameter \
    --region "$AWS_REGION" \
    --name "$LAST_GOOD_PARAMETER" \
    --query 'Parameter.Value' \
    --output text \
    --no-cli-pager 2>/dev/null || true)"

if [[ ! "$PREVIOUS_SHA" =~ ^[0-9a-f]{40}$ ]]; then
    PREVIOUS_SHA=""
fi

log "new SHA=${IMAGE_SHA}"
log "previous last-known-good=${PREVIOUS_SHA:-none}"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

REMOTE_DEPLOY_SCRIPT="$WORK_DIR/remote-deploy.sh"

cat > "$REMOTE_DEPLOY_SCRIPT" <<'REMOTE_DEPLOY'
#!/usr/bin/env bash
set -Eeuo pipefail

ROLE="$1"
IMAGE_URI="$2"
AWS_REGION="$3"
APP_DIR="/opt/autique"

case "$ROLE" in
    api)
        COMPOSE_FILE="${APP_DIR}/docker-compose.aws-api.yml"
        SERVICE="api"
        ;;
    worker)
        COMPOSE_FILE="${APP_DIR}/docker-compose.aws-worker.yml"
        SERVICE="worker"
        ;;
    *)
        echo "Unsupported role: $ROLE" >&2
        exit 2
        ;;
esac

test -f "$COMPOSE_FILE" || {
    echo "Missing compose file: $COMPOSE_FILE" >&2
    exit 3
}

cd "$APP_DIR"

# IMAGE_URI는 compose 파일 해석 시점(${IMAGE_URI} 치환)에만 쓰인다. api 역할은 같은 .env에
# API_PRIVATE_IP/SITE_ADDRESS도 함께 있어야 한다(이미 /opt/autique/.env에 상주, 이 스크립트는
# IMAGE_URI 줄만 갱신한다).
if [ -f .env ]; then
    grep -v '^IMAGE_URI=' .env > .env.tmp || true
else
    : > .env.tmp
fi
printf 'IMAGE_URI=%s\n' "$IMAGE_URI" >> .env.tmp
chmod 644 .env.tmp
mv .env.tmp .env

REGISTRY="${IMAGE_URI%%/*}"
DOCKER_AUTH_DIR="$(mktemp -d)"
export DOCKER_CONFIG="$DOCKER_AUTH_DIR"
trap 'rm -rf "$DOCKER_AUTH_DIR"' EXIT

aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null

COMPOSE=(docker compose -f "$COMPOSE_FILE")

"${COMPOSE[@]}" config --quiet
# -t를 지정하지 않는다 - 지정하면 Compose 파일의 stop_grace_period(API/Worker 모두 210s,
# analysis.stream.shutdown-grace-period-ms 기준)를 무시하고 그 값으로 강제 종료 타이머가
# 돌아간다. 생략하면 Compose가 각 서비스의 stop_grace_period를 그대로 쓴다.
"${COMPOSE[@]}" stop "$SERVICE"
docker pull "$IMAGE_URI"
"${COMPOSE[@]}" up -d --force-recreate "$SERVICE"

ready=0
for attempt in $(seq 1 36); do
    if curl --fail --silent --show-error http://127.0.0.1:8081/actuator/health \
        | grep -q '"status":"UP"'; then
        echo "LOCAL_READINESS_PASS role=${ROLE} attempt=${attempt}"
        ready=1
        break
    fi
    sleep 5
done

if [ "$ready" -ne 1 ]; then
    echo "LOCAL_READINESS_FAIL role=${ROLE}" >&2
    curl --silent http://127.0.0.1:8081/actuator/health || true
    "${COMPOSE[@]}" ps -a || true
    "${COMPOSE[@]}" logs --tail=100 "$SERVICE" || true
    exit 10
fi

container_id="$("${COMPOSE[@]}" ps -q "$SERVICE")"
actual_image="$(docker inspect --format '{{.Config.Image}}' "$container_id")"

if [ "$actual_image" != "$IMAGE_URI" ]; then
    echo "IMAGE_MISMATCH expected=$IMAGE_URI actual=$actual_image" >&2
    exit 12
fi

echo "REMOTE_DEPLOY_PASS role=$ROLE image=$actual_image"
"${COMPOSE[@]}" ps
REMOTE_DEPLOY

run_ssm_script() {
    local instance_id="$1"
    local label="$2"
    local script_file="$3"
    shift 3

    local encoded remote_command parameters command_id status stdout stderr
    encoded="$(base64 -w 0 "$script_file")"

    remote_command="echo '${encoded}' | base64 -d > /tmp/${label}.sh && chmod 700 /tmp/${label}.sh && /bin/bash /tmp/${label}.sh"

    for argument in "$@"; do
        printf -v remote_command '%s %q' "$remote_command" "$argument"
    done

    parameters="$(jq -nc \
        --arg command "$remote_command" \
        '{commands:[$command],executionTimeout:["900"]}')"

    command_id="$(aws ssm send-command \
        --region "$AWS_REGION" \
        --instance-ids "$instance_id" \
        --document-name AWS-RunShellScript \
        --comment "deploy ${label}" \
        --parameters "$parameters" \
        --query 'Command.CommandId' \
        --output text \
        --no-cli-pager)" || return 1

    log "SSM label=${label} instance=${instance_id} command=${command_id}"

    for attempt in $(seq 1 180); do
        status="$(aws ssm get-command-invocation \
            --region "$AWS_REGION" \
            --command-id "$command_id" \
            --instance-id "$instance_id" \
            --query 'Status' \
            --output text \
            --no-cli-pager 2>/dev/null || true)"

        case "$status" in
            Success) break ;;
            Failed|Cancelled|TimedOut|Cancelling) break ;;
            *) sleep 5 ;;
        esac
    done

    stdout="$(aws ssm get-command-invocation \
        --region "$AWS_REGION" \
        --command-id "$command_id" \
        --instance-id "$instance_id" \
        --query 'StandardOutputContent' \
        --output text \
        --no-cli-pager 2>/dev/null || true)"

    stderr="$(aws ssm get-command-invocation \
        --region "$AWS_REGION" \
        --command-id "$command_id" \
        --instance-id "$instance_id" \
        --query 'StandardErrorContent' \
        --output text \
        --no-cli-pager 2>/dev/null || true)"

    printf '%s\n' "$stdout"
    if [ -n "$stderr" ] && [ "$stderr" != "None" ]; then
        printf '%s\n' "$stderr" >&2
    fi

    if [ "$status" != "Success" ]; then
        log "SSM_FAILURE label=${label} instance=${instance_id} status=${status:-unknown}"
        return 1
    fi

    return 0
}

deploy_and_verify() {
    local image_uri="$1"
    local instance_id

    log "Deploying API image=${image_uri}"
    for instance_id in "${API_INSTANCE_IDS[@]}"; do
        run_ssm_script "$instance_id" deploy-api "$REMOTE_DEPLOY_SCRIPT" api "$image_uri" "$AWS_REGION" || return 1
    done

    log "Deploying Worker image=${image_uri}"
    for instance_id in "${WORKER_INSTANCE_IDS[@]}"; do
        run_ssm_script "$instance_id" deploy-worker "$REMOTE_DEPLOY_SCRIPT" worker "$image_uri" "$AWS_REGION" || return 1
    done

    log "DEPLOY_AND_VERIFY_PASS image=${image_uri}"
    return 0
}

if deploy_and_verify "$NEW_IMAGE_URI"; then
    aws ssm put-parameter \
        --region "$AWS_REGION" \
        --name "$LAST_GOOD_PARAMETER" \
        --description "Demo deployment last-known-good application Git SHA" \
        --type String \
        --value "$IMAGE_SHA" \
        --overwrite \
        --no-cli-pager >/dev/null

    log "LAST_KNOWN_GOOD_UPDATED sha=${IMAGE_SHA}"
    log "DEPLOY_SUCCESS"
    log "다음: scripts/aws/smoke-analyze.sh를 한 번 실행해 실제 analyze 요청 1건이 COMPLETED까지 이어지는지 확인하세요(자동 게이트에는 포함되지 않습니다 - OpenAI 호출 비용 때문)."
    exit 0
fi

log "NEW_DEPLOYMENT_FAILED sha=${IMAGE_SHA}"

if [ -z "$PREVIOUS_SHA" ]; then
    log "ROLLBACK_UNAVAILABLE_NO_PREVIOUS_SHA"
    exit 1
fi

ROLLBACK_IMAGE_URI="${ECR_REGISTRY}/${ECR_REPOSITORY}:app-${PREVIOUS_SHA}"

if ! aws ecr describe-images \
    --region "$AWS_REGION" \
    --repository-name "$ECR_REPOSITORY" \
    --image-ids "imageTag=app-${PREVIOUS_SHA}" \
    --no-cli-pager >/dev/null 2>&1; then
    log "ROLLBACK_IMAGE_NOT_FOUND image=${ROLLBACK_IMAGE_URI}"
    exit 1
fi

log "ROLLBACK_START previous_sha=${PREVIOUS_SHA}"

if ! deploy_and_verify "$ROLLBACK_IMAGE_URI"; then
    log "ROLLBACK_FAILED previous_sha=${PREVIOUS_SHA}"
    exit 2
fi

CURRENT_PARAMETER="$(aws ssm get-parameter \
    --region "$AWS_REGION" \
    --name "$LAST_GOOD_PARAMETER" \
    --query 'Parameter.Value' \
    --output text \
    --no-cli-pager)"

if [ "$CURRENT_PARAMETER" != "$PREVIOUS_SHA" ]; then
    log "ROLLBACK_PARAMETER_MISMATCH expected=${PREVIOUS_SHA} actual=${CURRENT_PARAMETER}"
    exit 2
fi

log "ROLLBACK_SUCCESS restored_sha=${PREVIOUS_SHA}"
log "Deployment failure was recovered; failing the workflow for visibility."
exit 1
