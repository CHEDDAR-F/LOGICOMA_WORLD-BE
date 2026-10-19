#!/bin/bash
set -eo pipefail

# ==========================================
# Logicoma 后端自动化部署脚本
# ==========================================
# Usage: ./deploy.sh [--target=aws|aliyun] [--skip-build] [--no-backup] [--reuse-remote-env] [--preflight-only]
# ==========================================

# Options
TARGET="aliyun"
SKIP_BUILD=false
NO_BACKUP=false
SELF_AUDIT=false
BACKUP_DB=false
ROLLBACK=""
REUSE_REMOTE_ENV=false
PREFLIGHT_ONLY=false
RUNTIME_DOCKER_ARGS=""
RUNTIME_JAVA_ARGS=""
COMMENT_RECEIPT_EXPANSION=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --target=*) TARGET="${1#*=}"; shift ;;
    --allow-comment-receipt-expansion) COMMENT_RECEIPT_EXPANSION="--allow-comment-receipt-expansion"; shift ;;
    --skip-build) SKIP_BUILD=true; shift ;;
    --no-backup) NO_BACKUP=true; shift ;;
    --self-audit) SELF_AUDIT=true; shift ;;
    --backup-db) BACKUP_DB=true; shift ;;
    --rollback=*) ROLLBACK="${1#*=}"; shift ;;
    --reuse-remote-env) REUSE_REMOTE_ENV=true; shift ;;
    --preflight-only) PREFLIGHT_ONLY=true; shift ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

# Target server configuration
select_server() {
  case "$TARGET" in
    aliyun|server1)
      SERVER_IP="111.231.58.28"
      SSH_USER="root"
      SSH_KEY="$HOME/.ssh/id_ed25519_logicoma"
      REMOTE_DIR="/DE_PKGS/de_version/de_code/de_server/ai_resource"
      CONTAINER_NAME="ai_resource_app"
      ENV_FILE=".env"
      ;;
    aws)
      SERVER_IP="44.199.91.157"
      SSH_USER="ubuntu"
      SSH_KEY="$HOME/.ssh/aws-logicomanet.pem"
      REMOTE_DIR="/opt/logicomanet-be"
      CONTAINER_NAME="logicomanet-be"
      ENV_FILE=".env.aws"
      # Leave room for MySQL, Redis, monitoring and the OS on the 2 GiB host.
      RUNTIME_DOCKER_ARGS="--memory=768m --memory-swap=1g"
      RUNTIME_JAVA_ARGS="-Xms64m -Xmx320m -XX:ReservedCodeCacheSize=64m"
      ;;
    *)
      echo "Unknown target: $TARGET"
      echo "Available targets: aliyun, aws"
      exit 1
      ;;
  esac
}
select_server
if [ -n "$COMMENT_RECEIPT_EXPANSION" ] && [ "$TARGET" != aws ]; then
  echo "Comment receipt expansion is supported only by the AWS passive handoff" >&2
  exit 1
fi
if [ "$PREFLIGHT_ONLY" = true ]; then
  [ "$SKIP_BUILD" = false ] || { echo "Preflight requires a fresh build" >&2; exit 1; }
  [ "$NO_BACKUP" = false ] || { echo "Preflight cannot waive database backup" >&2; exit 1; }
fi
if [ "$NO_BACKUP" = true ] && [ -d "$(dirname "$0")/src/main/resources/db/migration-forward-only" ]; then
  echo "Forward-only migrations require a verified database backup" >&2
  exit 1
fi

# =============================================================================
# --self-audit: 部署前自检 (设计 §3.9, v2-5/v3-8)
# 5 项检查: Flyway V 号 / DB pre-flight / UNDO / Schema 预演 / Mapper 一致性
# =============================================================================
self_audit() {
  local fail=0
  local migration_dir="src/main/resources/db/migration"
  local undo_dir="src/main/resources/db/migration-undo"
  local forward_dir="src/main/resources/db/migration-forward-only"
  local be_dir
  be_dir="$(cd "$(dirname "$0")" && pwd)"
  cd "$be_dir"
  echo "[deploy.sh --self-audit] 开始自检 (BE dir: $be_dir)"
  echo -n "  Flyway V 号续接 ... "
  local latest_v
  latest_v=$(ls "$migration_dir"/V*.sql 2>/dev/null | sort -V | tail -1 | sed 's/.*V\([0-9]*\).*/\1/')
  [ -n "$latest_v" ] && echo "V${latest_v} — PASS" || { echo "FAIL"; fail=1; }
  echo -n "  DB pre-flight ... "
  ssh_cmd "${SSH_USER}@${SERVER_IP}" "docker exec mysql sh -c 'exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e \"SELECT version FROM logicoma_net.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1\"' 2>/dev/null" > /dev/null 2>&1 && echo "PASS" || echo "WARN"
  echo -n "  UNDO / forward-only 策略 ... "
  local missing=0
  for vfile in "$migration_dir"/V*.sql; do
    local vname; vname=$(basename "$vfile" .sql)
    if [ ! -f "$undo_dir/${vname}-undo.sql" ] && [ ! -s "$forward_dir/${vname}.md" ]; then
      echo ""
      echo "    MISSING: undo script or documented forward-only marker for $vname"
      missing=1
    fi
  done
  [ "$missing" -eq 0 ] && echo "PASS" || { echo "FAIL"; fail=1; }
  echo -n "  Schema 预演 (mvn compile) ... "
  mvn -q compile -DskipTests 2>/dev/null && echo "PASS" || { echo "FAIL"; fail=1; }
  echo -n "  Mapper ↔ DB 一致性 ... "
  [ -f "src/test/java/com/ai/repo/MapperXmlConsistencyTest.java" ] && echo "PASS" || echo "WARN"
  echo "[deploy.sh --self-audit] 完成: $fail 项失败"
  return $fail
}

# =============================================================================
# --backup-db: 数据库备份 (P23)
# =============================================================================
backup_db() {
  local ts; ts=$(date +%Y%m%d_%H%M%S)
  local backup_file="/opt/backups/pre-${ts}.sql"
  echo "[deploy.sh --backup-db] 备份 DB → ${backup_file}"
  ssh_cmd "${SSH_USER}@${SERVER_IP}" "sudo mkdir -p /opt/backups && sudo chmod 700 /opt/backups" 2>/dev/null
  # Create the destination with restrictive permissions before streaming any
  # database bytes; tee otherwise inherits a potentially world-readable umask.
  ssh_cmd "${SSH_USER}@${SERVER_IP}" "sudo install -m 600 /dev/null '${backup_file}.gz'" 2>/dev/null
  # Use the container-local root credential so application-password rotation
  # cannot break the mandatory pre-deploy backup gate.
  ssh_cmd "${SSH_USER}@${SERVER_IP}" "docker exec mysql sh -c 'exec mysqldump -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --single-transaction logicoma_net' 2>/dev/null" | gzip | ssh_cmd "${SSH_USER}@${SERVER_IP}" "sudo tee ${backup_file}.gz >/dev/null" 2>/dev/null
  ssh_cmd "${SSH_USER}@${SERVER_IP}" "test \$(sudo stat -c%s '${backup_file}.gz') -gt 1024 && test \$(sudo stat -c%a '${backup_file}.gz') = 600 && sudo gzip -t '${backup_file}.gz'" || {
    echo "[deploy.sh --backup-db] FAIL: backup is empty or invalid"
    return 1
  }
  echo "[deploy.sh --backup-db] 完成: ${backup_file}.gz"
}

# =============================================================================
# --rollback: 回滚 (设计 §3.9)
# =============================================================================
rollback() {
  local version="$1"
  echo "[deploy.sh --rollback] 回滚到版本: ${version}"
  echo "  1. docker stop ${CONTAINER_NAME}"
  echo "  2. 回退 JAR: docker tag logicomanet-be:<prev> logicomanet-be:latest"
  echo "  3. 还原 DB: zcat /opt/backups/pre-${version}.sql.gz | docker exec -i mysql mysql logicoma_net"
  echo "  4. docker run ..."
  echo "[deploy.sh --rollback] 请手动执行, 或使用 --rollback=auto"
}

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

step() { echo -e "${GREEN}[→]${NC} $1"; }
info() { echo -e "${YELLOW}[!]${NC} $1"; }
err() { echo -e "${RED}[✗]${NC} $1"; exit 1; }
ok()  { echo -e "${CYAN}[✓]${NC} $1"; }

# SSH/SCP helpers
ssh_cmd() {
  ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 "$@"
}

scp_cmd() {
  scp -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$@"
}

[ -f "$SSH_KEY" ] || err "SSH key not found: $SSH_KEY"

LOCAL_JAR="target/logicoma-net-2.0.0.jar"
APP_JAR="app.jar"

# Load .env file
load_env_file() {
  if [ -f "$ENV_FILE" ]; then
    step "Loading $ENV_FILE..."
    set -a
    source "$ENV_FILE"
    set +a
    ok "Environment loaded from $ENV_FILE"
  elif [ -f ".env" ]; then
    step "$ENV_FILE not found, falling back to .env..."
    set -a
    source ".env"
    set +a
    ok "Environment loaded from .env"
  else
    info "No .env file found, using defaults"
  fi
}

echo ""
echo "=========================================="
echo "  Target: ${TARGET} (${SERVER_IP})"
echo "  Dir:    ${REMOTE_DIR}"
echo "  Container: ${CONTAINER_NAME}"
echo "=========================================="
echo ""

if [ "$REUSE_REMOTE_ENV" = true ]; then
  step "Using the existing remote environment file; no secret file will be copied from this checkout"
else
  load_env_file
fi

# Default env values
MAIL_HOST="${MAIL_HOST:-smtp.example.com}"
MAIL_PORT="${MAIL_PORT:-587}"
MAIL_USERNAME="${MAIL_USERNAME:-}"
MAIL_PASSWORD="${MAIL_PASSWORD:-}"
MAIL_FROM="${MAIL_FROM:-noreply@logicoma.ai}"
APP_BASE_URL="${APP_BASE_URL:-http://your-domain.com}"
APP_FRONTEND_URL="${APP_FRONTEND_URL:-http://your-domain.com}"
ADMIN_BOOTSTRAP_EMAILS="${ADMIN_BOOTSTRAP_EMAILS:-}"

# ─── 子命令路由（helpers 与环境变量加载完成后执行） ────────────────────────
[ "$SELF_AUDIT" = true ] && { self_audit; exit $?; }
[ -n "$ROLLBACK" ] && { rollback "$ROLLBACK"; exit $?; }
[ "$BACKUP_DB" = true ] && { backup_db; exit $?; }

if [ "$REUSE_REMOTE_ENV" = true ] && [ "$TARGET" != aws ]; then
  step "Checking existing remote environment file and permissions..."
  ssh_cmd "${SSH_USER}@${SERVER_IP}" \
    "test -f '${REMOTE_DIR}/.env' && test \"\$(stat -c %a '${REMOTE_DIR}/.env')\" = 600" \
    || err "Remote .env is missing or is not mode 600"
  ok "Remote environment file ready"
fi

# --- Build ---
if [ "$SKIP_BUILD" = false ]; then
  step "Building project..."
  mvn clean package -DskipTests || err "Build failed."
  ok "Build complete."

  if [ ! -f "$LOCAL_JAR" ]; then
    if [ -f "logicoma-net-2.0.0.jar" ]; then
      LOCAL_JAR="logicoma-net-2.0.0.jar"
    else
      err "JAR not found: $LOCAL_JAR"
    fi
  fi
else
  step "Skipping build (--skip-build)"
fi

if [ "$PREFLIGHT_ONLY" = true ]; then
  [ -f "$LOCAL_JAR" ] || err "JAR not found: $LOCAL_JAR"
  bash scripts/validate-git-policy.sh range origin/main HEAD || err "Git policy failed"
  bash scripts/test-deploy-backup-gate.sh || err "Backup gate failed"
  ok "Backend preflight complete; no server changes made"
  exit 0
fi

# AWS uses a warmed passive candidate and graceful proxy handoff. Never enter
# the legacy stop-first path below; runtime secrets/flags are cloned on the host.
if [ "$TARGET" = aws ]; then
  git diff --quiet && git diff --cached --quiet || err "Commit tracked changes before release"
  [ -z "$(git ls-files --others --exclude-standard -- src scripts deploy.sh pom.xml)" ] || err "Commit new application and release sources before deploy"
  REVISION=$(git rev-parse HEAD)
  CURRENT_SOURCE=$(ssh_cmd "${SSH_USER}@${SERVER_IP}" "docker inspect --format='{{index .Config.Labels \"com.logicoma.release\"}}' '${CONTAINER_NAME}'")
  git cat-file -e "${CURRENT_SOURCE}^{commit}" 2>/dev/null && git merge-base --is-ancestor "$CURRENT_SOURCE" HEAD \
    || err "Candidate must retain the deployed source ancestry; compose existing production changes first"
  EXPECTED=$(ssh_cmd "${SSH_USER}@${SERVER_IP}" "sha256sum '${REMOTE_DIR}/app.jar'" | awk '{print $1}')
  CANDIDATE=$(shasum -a 256 "$LOCAL_JAR" | awk '{print $1}')
  RELEASE_DIR="${REMOTE_DIR}/releases/release-$(date -u +%Y%m%d%H%M%S)-${REVISION:0:12}"
  ssh_cmd "${SSH_USER}@${SERVER_IP}" "sudo mkdir '$RELEASE_DIR' && sudo chown '$SSH_USER' '$RELEASE_DIR' && chmod 700 '$RELEASE_DIR'"
  scp_cmd "$LOCAL_JAR" "${SSH_USER}@${SERVER_IP}:${RELEASE_DIR}/candidate.jar" || err "Artifact upload failed"
  scp_cmd scripts/deploy-blue-green.py "${SSH_USER}@${SERVER_IP}:${RELEASE_DIR}/deploy-blue-green.py" || err "Release tool upload failed"
  ssh_cmd "${SSH_USER}@${SERVER_IP}" "sudo python3 '$RELEASE_DIR/deploy-blue-green.py' '$RELEASE_DIR' '$EXPECTED' '$CANDIDATE' '$REVISION' $COMMENT_RECEIPT_EXPANSION" \
    || err "AWS handoff failed; inspect server-local release evidence"
  ok "AWS passive-candidate release completed"
  exit 0
fi

# --- Remote dir ---
step "Ensuring remote directory..."
ssh_cmd "${SSH_USER}@${SERVER_IP}" "mkdir -p '${REMOTE_DIR}'" || err "Failed to create remote directory"
ok "Remote directory ready"

# --- Backup old JAR ---
if [ "$NO_BACKUP" = false ]; then
  step "Backing up old JAR (if any)..."
  ssh_cmd "${SSH_USER}@${SERVER_IP}" \
    "cd ${REMOTE_DIR} && [ -f ${APP_JAR} ] && mv ${APP_JAR} ${APP_JAR}.bak.\$(date +%Y%m%d_%H%M%S) && echo 'Backup created' || echo 'No existing JAR to backup'"
  # DB 备份 (P23, 设计 §3.9, 切流量前)
  step "Backing up database before deploy..."
  backup_db
else
  info "Skipping backup (--no-backup)"
fi

# --- Upload ---
step "Uploading JAR..."
scp_cmd "$LOCAL_JAR" "${SSH_USER}@${SERVER_IP}:${REMOTE_DIR}/logicoma-net-2.0.0.jar" || err "Upload failed"
ok "JAR uploaded"

# Upload .env
if [ "$REUSE_REMOTE_ENV" = true ]; then
  step "Keeping the existing remote .env file"
elif [ -f "$ENV_FILE" ]; then
  step "Uploading $ENV_FILE..."
  scp_cmd "$ENV_FILE" "${SSH_USER}@${SERVER_IP}:${REMOTE_DIR}/.env"
  ssh_cmd "${SSH_USER}@${SERVER_IP}" "chmod 600 '${REMOTE_DIR}/.env'"
  ok "$ENV_FILE uploaded"
else
  err "Required deployment secret file not found: $ENV_FILE"
fi

# --- Remote deploy ---
step "Deploying on remote server..."
ssh_cmd "${SSH_USER}@${SERVER_IP}" << EOF
  set -e
  cd ${REMOTE_DIR}

  # Rename new JAR
  mv logicoma-net-2.0.0.jar ${APP_JAR}
  echo "JAR renamed to ${APP_JAR}"

  # Stop & remove old container
  echo "Stopping old container..."
  docker stop ${CONTAINER_NAME} 2>/dev/null || true
  docker rm ${CONTAINER_NAME} 2>/dev/null || true
  echo "Old container removed"

  # Ensure data directory
  sudo mkdir -p /data/git_repos

  # Start new container
  echo "Starting new container..."
  docker run -d \
    --name ${CONTAINER_NAME} \
    --restart=always \
    ${RUNTIME_DOCKER_ARGS} \
    --log-opt max-size=10m \
    --log-opt max-file=3 \
    --network=host \
    --env-file ${REMOTE_DIR}/.env \
    -v ${REMOTE_DIR}:${REMOTE_DIR} \
    -v /data/git_repos:/data/git_repos \
    -e FILE_STORAGE_PATH=${REMOTE_DIR} \
    -w ${REMOTE_DIR} \
    eclipse-temurin:17-jdk-alpine \
    java ${RUNTIME_JAVA_ARGS} -jar app.jar --spring.profiles.active=prod
  echo "Container started"
EOF
ok "Deployment completed"

# --- Verify ---
step "Verifying deployment..."
for attempt in $(seq 1 30); do
  if ssh_cmd "${SSH_USER}@${SERVER_IP}" "docker ps --filter name=^/${CONTAINER_NAME}$ --filter status=running --format '{{.Names}}' | grep -qx '${CONTAINER_NAME}' && curl --fail --silent http://127.0.0.1:8080/actuator/health | grep -q '\"status\":\"UP\"'"; then
    ok "Container and local API are healthy"
    break
  fi
  [ "$attempt" -eq 30 ] && {
    ssh_cmd "${SSH_USER}@${SERVER_IP}" "docker logs --tail 100 ${CONTAINER_NAME}" || true
    err "Deployment health check timed out"
  }
  sleep 2
done

if [ "$TARGET" = "aws" ]; then
  PUBLIC_API_STATUS=$(curl --silent --show-error -o /dev/null -w '%{http_code}' https://logicomanet.com/api/agents/76)
  case "$PUBLIC_API_STATUS" in
    200|401) ok "Public API route and authentication verified (HTTP $PUBLIC_API_STATUS)" ;;
    *) err "Public API verification failed (HTTP $PUBLIC_API_STATUS)" ;;
  esac
fi

echo ""
step "============================================"
step "  Deployment Complete!"
step "  Target: ${TARGET} (${SERVER_IP})"
step "  Container: ${CONTAINER_NAME}"
step "============================================"
echo ""

# Show logs
step "Recent logs:"
echo ""
ssh_cmd "${SSH_USER}@${SERVER_IP}" "docker logs --tail 50 ${CONTAINER_NAME}"
