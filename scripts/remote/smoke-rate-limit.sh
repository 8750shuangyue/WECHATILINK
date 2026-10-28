#!/usr/bin/env bash
# 限流探针：向登录接口发送多次错误凭据，确认出现 429 且响应体为 JSON。
# 不会创建账号；被锁定的只是 ___rl_probe 这个不存在的用户名。
# 用法：bash scripts/remote/smoke-rate-limit.sh [base-url]

set -u

BASE="${1:-https://sekaipetplant.com}"
OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT

echo "Probing ${BASE}/api/auth/login"
code_429=0
for i in $(seq 1 15); do
  code=$(curl -sS -o "$OUT" -w '%{http_code}' --max-time 15 \
    -X POST "${BASE}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"userName\":\"___rl_probe\",\"password\":\"x\"}" || echo "000")
  printf '%s ' "$code"
  if [ "$code" = "429" ]; then
    code_429=$((code_429 + 1))
  fi
  sleep 0.2
done
echo

if [ "$code_429" -eq 0 ]; then
  echo "FAIL: no 429 observed (dry-run may still be enabled, or limits are too loose)"
  exit 1
fi

echo "Last response body:"
cat "$OUT"
echo

if grep -q '"code":429' "$OUT"; then
  echo "PASS: 429 JSON body received (${code_429} of 15 requests limited)"
  exit 0
fi

echo "WARN: got 429 but body is not the expected JSON (check error_page config)"
exit 1
