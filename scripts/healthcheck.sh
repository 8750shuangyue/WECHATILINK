#!/usr/bin/env bash
# ============================================================
# 服务器端健康检查（阶段 7.3）
# 检查：本机应用、管理端口 health、公网 HTTPS、systemd 状态。
# 失败时写 /opt/ilink/healthcheck.log；配置 ALERT_WEBHOOK_URL 后
# 会同时向企业微信/钉钉等 webhook 推送文本消息。
# ============================================================
set -u

APP_URL="${APP_URL:-http://127.0.0.1:8080/login}"
MGMT_URL="${MGMT_URL:-http://127.0.0.1:8081/actuator/health}"
PUBLIC_URL="${PUBLIC_URL:-https://sekaipetplant.com/login}"
SERVICE="${SERVICE:-ilink}"
ALERT_WEBHOOK_URL="${ALERT_WEBHOOK_URL:-}"
LOG="${HEALTHCHECK_LOG:-/opt/ilink/healthcheck.log}"

failures=()

check_http() {
    local name="$1" url="$2"
    if ! curl -fsS --max-time 8 -o /dev/null "$url"; then
        failures+=("$name unreachable: $url")
    fi
}

check_http "app" "$APP_URL"
check_http "public" "$PUBLIC_URL"

if ! systemctl is-active --quiet "$SERVICE"; then
    failures+=("systemd $SERVICE is not active")
fi

health="$(curl -fsS --max-time 8 "$MGMT_URL" 2>/dev/null || true)"
if [ -z "$health" ]; then
    failures+=("management health unreachable: $MGMT_URL")
elif ! printf '%s' "$health" | grep -q '"status":"UP"'; then
    failures+=("management health not UP")
fi

if [ ${#failures[@]} -eq 0 ]; then
    exit 0
fi

ts="$(date '+%Y-%m-%d %H:%M:%S')"
message="[$ts] ilink healthcheck failed: ${failures[*]}"
printf '%s\n' "$message" >> "$LOG"
logger -t ilink-healthcheck "$message" 2>/dev/null || true

if [ -n "$ALERT_WEBHOOK_URL" ]; then
    escaped="$(printf '%s' "$message" | sed 's/\\/\\\\/g; s/"/\\"/g')"
    payload="$(printf '{"msgtype":"text","text":{"content":"%s"}}' "$escaped")"
    curl -fsS --max-time 8 -X POST \
        -H 'Content-Type: application/json' \
        -d "$payload" "$ALERT_WEBHOOK_URL" >/dev/null 2>&1 || true
fi

exit 1
