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

# #127: product_analysis_session 스키마 마이그레이션(backend/scripts/migrations/
# 127-cancel-image-analysis.sql)이 대상 DB에 이미 적용돼 있는지 API EC2 위 MySQL 컨테이너에
# 읽기 전용으로 확인한다. ALTER는 절대 실행하지 않는다 - information_schema만 SELECT한다.
# 이 스크립트가 실패하면 verify_schema_migration_applied()가 deploy_and_verify() 호출 전에
# 배포 자체를 중단시킨다(API/Worker 컨테이너를 하나도 바꾸지 않은 채).
SCHEMA_CHECK_SCRIPT="$WORK_DIR/remote-schema-check.sh"

cat > "$SCHEMA_CHECK_SCRIPT" <<'REMOTE_SCHEMA_CHECK'
#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="/opt/autique"
# MySQL은 API EC2 위 docker-compose.aws-api.yml의 mysql 서비스로만 존재한다(Worker EC2에는
# DB가 없다) - 이 스크립트는 API 인스턴스에서만 실행된다(verify_schema_migration_applied 참고).
COMPOSE_FILE="${APP_DIR}/docker-compose.aws-api.yml"

test -f "$COMPOSE_FILE" || {
    echo "SCHEMA_CHECK_FAIL reason=missing_compose_file file=${COMPOSE_FILE}" >&2
    exit 3
}

cd "$APP_DIR"

# api.env의 MYSQL_ROOT_PASSWORD(기존 DB 컨테이너 인증 방식, docs/aws-demo-deployment.md 참고)를
# 그대로 재사용한다 - 새 인증 경로를 만들지 않는다. 값 자체는 어디에도 echo하지 않는다.
if [ -f api.env ]; then
    set -a
    # shellcheck disable=SC1091
    source api.env
    set +a
fi

DB_NAME="${MYSQL_DATABASE:-autique}"

if [ -z "${MYSQL_ROOT_PASSWORD:-}" ]; then
    echo "SCHEMA_CHECK_FAIL reason=missing_MYSQL_ROOT_PASSWORD" >&2
    exit 4
fi

QUERY="SELECT
  SUM(CASE WHEN COLUMN_NAME = 'cancelled_at' THEN 1 ELSE 0 END),
  SUM(CASE WHEN COLUMN_NAME = 'registered_at' THEN 1 ELSE 0 END),
  MAX(CASE WHEN COLUMN_NAME = 'status' THEN COLUMN_TYPE ELSE NULL END)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_analysis_session';"

RESULT="$(docker compose -f "$COMPOSE_FILE" exec -T mysql \
    mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e "$QUERY" "$DB_NAME")" || {
    echo "SCHEMA_CHECK_FAIL reason=db_connection_or_query_failed" >&2
    exit 5
}

if [ -z "$RESULT" ]; then
    echo "SCHEMA_CHECK_FAIL reason=table_not_found_or_no_result" >&2
    exit 6
fi

HAS_CANCELLED_AT="$(printf '%s' "$RESULT" | cut -f1)"
HAS_REGISTERED_AT="$(printf '%s' "$RESULT" | cut -f2)"
STATUS_TYPE="$(printf '%s' "$RESULT" | cut -f3)"

if [ "$HAS_CANCELLED_AT" != "1" ] || [ "$HAS_REGISTERED_AT" != "1" ]; then
    echo "SCHEMA_CHECK_FAIL reason=missing_columns has_cancelled_at=${HAS_CANCELLED_AT} has_registered_at=${HAS_REGISTERED_AT}" >&2
    exit 7
fi

case "$STATUS_TYPE" in
    *"'CANCELLED'"*) ;;
    *)
        echo "SCHEMA_CHECK_FAIL reason=status_enum_missing_CANCELLED" >&2
        exit 8
        ;;
esac

echo "SCHEMA_CHECK_PASS has_cancelled_at=1 has_registered_at=1 status_enum_has_CANCELLED=1"
REMOTE_SCHEMA_CHECK

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

    # #127: Worker를 먼저 배포하고 검증한다. AnalysisStatus는 @Enumerated(EnumType.STRING)이라
    # 구 버전 Worker는 CANCELLED를 모른다 - API가 먼저 취소를 허용해 그 상태를 만들면 구 Worker가
    # 그 행을 읽는 순간(findByIdForUpdate) enum 역직렬화에 실패한다. Worker 배포/검증이 실패하면
    # (아래 run_ssm_script 실패 시 즉시 return 1) API 배포는 시도하지 않는다.
    log "Deploying Worker image=${image_uri}"
    for instance_id in "${WORKER_INSTANCE_IDS[@]}"; do
        run_ssm_script "$instance_id" deploy-worker "$REMOTE_DEPLOY_SCRIPT" worker "$image_uri" "$AWS_REGION" || return 1
    done

    log "Deploying API image=${image_uri}"
    for instance_id in "${API_INSTANCE_IDS[@]}"; do
        run_ssm_script "$instance_id" deploy-api "$REMOTE_DEPLOY_SCRIPT" api "$image_uri" "$AWS_REGION" || return 1
    done

    log "DEPLOY_AND_VERIFY_PASS image=${image_uri}"
    return 0
}

# #127: API/Worker 컨테이너를 하나도 바꾸기 전에, product_analysis_session 마이그레이션이 대상
# DB에 이미 적용돼 있는지 읽기 전용으로 확인한다(SCHEMA_CHECK_SCRIPT 참고 - ALTER 없음). MySQL은
# API EC2 위에만 있으므로 API 인스턴스 하나를 통해 확인한다(이 데모 구성은 API EC2 1대를
# 전제한다 - docs/aws-demo-deployment.md 참고). DB 연결 실패·쿼리 실패·컬럼/ENUM 조건 불충족
# 모두 실패로 취급한다.
verify_schema_migration_applied() {
    local instance_id="${API_INSTANCE_IDS[0]}"
    log "Verifying #127 schema migration (read-only, no ALTER) on instance=${instance_id}"
    if ! run_ssm_script "$instance_id" schema-check "$SCHEMA_CHECK_SCRIPT"; then
        log "ERROR: #127 schema migration check failed - DB does not yet have CANCELLED support."
        log "ERROR: apply backend/scripts/migrations/127-cancel-image-analysis.sql against the target DB first, then retry."
        return 1
    fi
    return 0
}

# #127: 자동 롤백 대상 이미지가 CANCELLED(AnalysisStatus)를 이해하는지 빌드 시점 라벨로
# 판정한다(backend/Dockerfile의 com.autique.compat.analysis-cancellation) - 태그 이름이나
# 빌드 시각으로 추측하지 않는다. DB에 CANCELLED 행이 없다는 조회로도 판정하지 않는다 - 지금
# 시도 중인 새 배포가 그 행을 막 만들었을 수 있어, "현재 비어있음"이 "구버전이 안전하다"를
# 보장하지 않기 때문이다. 라벨이 없거나(구버전 이미지) 다른 값이거나, pull/inspect 자체가
# 실패하면 모두 비호환으로 취급해 자동 롤백 대상에서 제외한다 - API/Worker 컨테이너를 하나도
# 바꾸기 전에 판정하므로, 차단돼도 이미 떠 있던 컨테이너는 그대로 남는다.
ROLLBACK_COMPAT_LABEL="com.autique.compat.analysis-cancellation"
ROLLBACK_COMPAT_VALUE="supported"

check_rollback_image_compatibility() {
    local image_uri="$1"
    local registry="${image_uri%%/*}"
    local label_value
    local docker_auth_dir
    docker_auth_dir="$(mktemp -d)"

    if ! DOCKER_CONFIG="$docker_auth_dir" aws ecr get-login-password --region "$AWS_REGION" \
        | DOCKER_CONFIG="$docker_auth_dir" docker login --username AWS --password-stdin "$registry" >/dev/null 2>&1; then
        log "ROLLBACK_COMPAT_CHECK_FAILED reason=ecr_login_failed image=${image_uri}"
        rm -rf "$docker_auth_dir"
        return 1
    fi

    if ! DOCKER_CONFIG="$docker_auth_dir" docker pull "$image_uri" >/dev/null 2>&1; then
        log "ROLLBACK_COMPAT_CHECK_FAILED reason=image_pull_failed image=${image_uri}"
        rm -rf "$docker_auth_dir"
        return 1
    fi

    label_value="$(DOCKER_CONFIG="$docker_auth_dir" docker inspect \
        --format "{{ index .Config.Labels \"${ROLLBACK_COMPAT_LABEL}\" }}" "$image_uri" 2>/dev/null || true)"
    rm -rf "$docker_auth_dir"

    if [ "$label_value" != "$ROLLBACK_COMPAT_VALUE" ]; then
        log "ROLLBACK_BLOCKED_INCOMPATIBLE image=${image_uri} label=${ROLLBACK_COMPAT_LABEL} value=${label_value:-<missing>}"
        return 1
    fi

    log "ROLLBACK_IMAGE_COMPATIBLE image=${image_uri} label=${ROLLBACK_COMPAT_LABEL}=${label_value}"
    return 0
}

if ! verify_schema_migration_applied; then
    log "DEPLOY_SKIPPED_SCHEMA_CHECK_FAILED"
    exit 4
fi

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

if ! check_rollback_image_compatibility "$ROLLBACK_IMAGE_URI"; then
    log "ERROR: rollback target is not confirmed #127-compatible (CANCELLED-aware) - refusing to"
    log "ERROR: replace API/Worker with it. The DB may already have CANCELLED rows that this"
    log "ERROR: image's AnalysisStatus enum cannot deserialize."
    log "ERROR: recover by fixing forward - deploy a #127-compatible image (built from current"
    log "ERROR: backend/Dockerfile, which carries the ${ROLLBACK_COMPAT_LABEL} label) instead of"
    log "ERROR: relying on automatic rollback to ${ROLLBACK_IMAGE_URI}."
    log "ROLLBACK_BLOCKED previous_sha=${PREVIOUS_SHA}"
    exit 2
fi

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
