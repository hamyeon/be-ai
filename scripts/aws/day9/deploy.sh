#!/usr/bin/env bash
set -Eeuo pipefail

for required_name in \
    AWS_REGION AWS_ACCOUNT_ID ECR_REPOSITORY IMAGE_SHA \
    TARGET_GROUP_NAME LAST_GOOD_PARAMETER; do
    if [ -z "${!required_name:-}" ]; then
        echo "ERROR: required environment variable is missing: ${required_name}" >&2
        exit 2
    fi
done

if [[ ! "$IMAGE_SHA" =~ ^[0-9a-f]{40}$ ]]; then
    echo "ERROR: IMAGE_SHA must be a full 40-character Git SHA: ${IMAGE_SHA}" >&2
    exit 2
fi

PROJECT_TAG="auction-infra"
ENVIRONMENT_TAG="experiment"
ECR_REGISTRY="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
NEW_IMAGE_URI="${ECR_REGISTRY}/${ECR_REPOSITORY}:app-${IMAGE_SHA}"

log() {
    printf '[day9] %s\n' "$*"
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
    log "ERROR: partial running target set. Refusing to create API/Worker SHA mismatch."
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
REMOTE_CANARY_SCRIPT="$WORK_DIR/remote-canary.sh"

cat > "$REMOTE_DEPLOY_SCRIPT" <<'REMOTE_DEPLOY'
#!/usr/bin/env bash
set -Eeuo pipefail

ROLE="$1"
IMAGE_URI="$2"
AWS_REGION="$3"
APP_DIR="/opt/autique"
AWSLOGS_FILE="${APP_DIR}/docker-compose.awslogs.yml"

case "$ROLE" in
    api)
        BASE_FILE="${APP_DIR}/docker-compose.aws-api.yml"
        SERVICE="api"
        ;;
    worker)
        BASE_FILE="${APP_DIR}/docker-compose.aws-worker.yml"
        SERVICE="worker"
        ;;
    *)
        echo "Unsupported role: $ROLE" >&2
        exit 2
        ;;
esac

test -f "$BASE_FILE" || {
    echo "Missing compose file: $BASE_FILE" >&2
    exit 3
}

test -f "$AWSLOGS_FILE" || {
    echo "Missing Day8 awslogs override: $AWSLOGS_FILE" >&2
    exit 3
}

cd "$APP_DIR"

printf 'IMAGE_URI=%s\n' "$IMAGE_URI" > .env.tmp
chmod 644 .env.tmp
mv .env.tmp .env

REGISTRY="${IMAGE_URI%%/*}"
DOCKER_AUTH_DIR="$(mktemp -d)"
export DOCKER_CONFIG="$DOCKER_AUTH_DIR"
trap 'rm -rf "$DOCKER_AUTH_DIR"' EXIT

aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null

COMPOSE=(docker compose -f "$BASE_FILE" -f "$AWSLOGS_FILE")

"${COMPOSE[@]}" config --quiet
"${COMPOSE[@]}" stop -t 90 "$SERVICE"
docker pull "$IMAGE_URI"
"${COMPOSE[@]}" up -d --force-recreate "$SERVICE"

if [ "$ROLE" = "api" ]; then
    ready=0
    for attempt in $(seq 1 36); do
        if curl --fail --silent --show-error \
            http://127.0.0.1:8081/actuator/health/readiness \
            | grep -q '"status":"UP"'; then
            echo "API_LOCAL_READINESS_PASS attempt=$attempt"
            ready=1
            break
        fi
        sleep 5
    done

    if [ "$ready" -ne 1 ]; then
        echo "API_LOCAL_READINESS_FAIL" >&2
        "${COMPOSE[@]}" ps -a || true
        exit 10
    fi
else
    running=0
    for attempt in $(seq 1 36); do
        state="$("${COMPOSE[@]}" ps --format '{{.State}}' "$SERVICE" 2>/dev/null || true)"
        if [ "$state" = "running" ]; then
            echo "WORKER_CONTAINER_RUNNING_PASS attempt=$attempt"
            running=1
            break
        fi
        sleep 5
    done

    if [ "$running" -ne 1 ]; then
        echo "WORKER_CONTAINER_RUNNING_FAIL" >&2
        "${COMPOSE[@]}" ps -a || true
        exit 11
    fi
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

cat > "$REMOTE_CANARY_SCRIPT" <<'REMOTE_CANARY'
#!/usr/bin/env bash
set -Eeuo pipefail

CANARY="/opt/autique/day7-e2e.sh"

test -x "$CANARY" || {
    echo "Missing executable canary: $CANARY" >&2
    exit 20
}

"$CANARY"
REMOTE_CANARY

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
        --comment "day9 ${label}" \
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
            Success)
                break
                ;;
            Failed|Cancelled|TimedOut|Cancelling)
                break
                ;;
            *)
                sleep 5
                ;;
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

wait_for_target_health() {
    local target_group_arn instance_id state reason all_healthy

    target_group_arn="$(aws elbv2 describe-target-groups \
        --region "$AWS_REGION" \
        --names "$TARGET_GROUP_NAME" \
        --query 'TargetGroups[0].TargetGroupArn' \
        --output text \
        --no-cli-pager)" || return 1

    for attempt in $(seq 1 60); do
        all_healthy=1

        for instance_id in "${API_INSTANCE_IDS[@]}"; do
            state="$(aws elbv2 describe-target-health \
                --region "$AWS_REGION" \
                --target-group-arn "$target_group_arn" \
                --targets "Id=${instance_id},Port=8080" \
                --query 'TargetHealthDescriptions[0].TargetHealth.State' \
                --output text \
                --no-cli-pager 2>/dev/null || true)"

            reason="$(aws elbv2 describe-target-health \
                --region "$AWS_REGION" \
                --target-group-arn "$target_group_arn" \
                --targets "Id=${instance_id},Port=8080" \
                --query 'TargetHealthDescriptions[0].TargetHealth.Reason' \
                --output text \
                --no-cli-pager 2>/dev/null || true)"

            log "target=${instance_id} state=${state:-unknown} reason=${reason:-None} attempt=${attempt}"

            if [ "$state" != "healthy" ]; then
                all_healthy=0
            fi
        done

        if [ "$all_healthy" -eq 1 ]; then
            log "ELB_TARGET_HEALTH_PASS"
            return 0
        fi

        sleep 5
    done

    log "ELB_TARGET_HEALTH_FAIL"
    return 1
}

deploy_and_verify() {
    local image_uri="$1"
    local instance_id

    log "Deploying API image=${image_uri}"
    for instance_id in "${API_INSTANCE_IDS[@]}"; do
        run_ssm_script \
            "$instance_id" \
            day9-deploy-api \
            "$REMOTE_DEPLOY_SCRIPT" \
            api "$image_uri" "$AWS_REGION" || return 1
    done

    wait_for_target_health || return 1

    log "Deploying Worker image=${image_uri}"
    for instance_id in "${WORKER_INSTANCE_IDS[@]}"; do
        run_ssm_script \
            "$instance_id" \
            day9-deploy-worker \
            "$REMOTE_DEPLOY_SCRIPT" \
            worker "$image_uri" "$AWS_REGION" || return 1
    done

    log "Running async worker canary through API instance=${API_INSTANCE_IDS[0]}"
    run_ssm_script \
        "${API_INSTANCE_IDS[0]}" \
        day9-worker-canary \
        "$REMOTE_CANARY_SCRIPT" || return 1

    log "DEPLOY_AND_VERIFY_PASS image=${image_uri}"
    return 0
}

if deploy_and_verify "$NEW_IMAGE_URI"; then
    aws ssm put-parameter \
        --region "$AWS_REGION" \
        --name "$LAST_GOOD_PARAMETER" \
        --description "Day9 last-known-good application Git SHA" \
        --type String \
        --value "$IMAGE_SHA" \
        --overwrite \
        --no-cli-pager >/dev/null

    log "LAST_KNOWN_GOOD_UPDATED sha=${IMAGE_SHA}"
    log "DAY9_DEPLOY_SUCCESS"
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
log "Intentional or real deployment failure was recovered; failing workflow for visibility."
exit 1
