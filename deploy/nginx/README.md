# Nginx 限流上线步骤（阶段 4 / S-02）

无应用重启，只有 `reload nginx`。全程可回滚。

## 1. 备份

```bash
STAMP=$(date +%Y%m%d-%H%M%S)
sudo mkdir -p /opt/ilink/backups/$STAMP-pre-rate-limit
sudo cp /etc/nginx/sites-available/sekaipetplant.com \
        /opt/ilink/backups/$STAMP-pre-rate-limit/nginx-site.conf
sudo cp -r /etc/nginx/conf.d/ilink-rate-limit.conf \
        /opt/ilink/backups/$STAMP-pre-rate-limit/ 2>/dev/null || true
```

注意：站点配置实际位于 `/etc/nginx/sites-enabled/`（软链）；复制时请保留原文件路径。

## 2. 安装 zone 配置（dry-run）

```bash
sudo cp deploy/nginx/ilink-rate-limit.conf /etc/nginx/conf.d/ilink-rate-limit.conf
# 打开文件末尾的两行（去掉注释）：
#   limit_req_dry_run on;
#   limit_conn_dry_run on;
sudo nginx -t
```

如果 `nginx -t` 报 `unknown directive "limit_req_dry_run"`（1.18 之前版本），
说明当前 Nginx 不支持 dry-run：跳过 dry-run，直接把阈值当正式配置上线，
并在上线后重点观察 429 数量。

> 实测提醒（nginx 1.18.0）：dry-run 命中的请求只写 debug 日志，默认级别下
> `error.log` 看不到任何记录，因此它只能“演练不误伤”，不能用来观察阈值是否合理。
> 本项目最终采用**直接生效 + 宽松阈值 + access.log 观察 429** 的方式。

## 实际采用的阈值（2026-10-28）

| zone | rate | burst |
|---|---:|---:|
| `ilink_login` | 12r/m | 10 |
| `ilink_register` | 6r/m | 3 |
| `ilink_ai` | 30r/m | 10 |
| `ilink_ai_stream` | 30r/m | 10 |
| `ilink_upload` | 20r/m | 8 |
| `ilink_api` | 300r/m | 60 |

## 3. 合并站点配置

把 `ilink-site.conf.example` 的内容合并进现有站点配置（保留 Certbot 生成的
`listen`/证书块不变）。合并后：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

## 4. 观察 24 小时（dry-run 阶段）

```bash
sudo grep -E 'limiting requests|limiting connections' /var/log/nginx/error.log | tail -50
```

dry-run 不会真的返回 429，只记录日志。确认没有正常用户被误伤后，

## 5. 正式生效

```bash
sudo sed -i 's/^# limit_req_dry_run on;/limit_req_dry_run off;/' /etc/nginx/conf.d/ilink-rate-limit.conf
sudo sed -i 's/^# limit_conn_dry_run on;/limit_conn_dry_run off;/' /etc/nginx/conf.d/ilink-rate-limit.conf
sudo nginx -t && sudo systemctl reload nginx
```

然后运行探针：

```bash
bash scripts/remote/smoke-rate-limit.sh https://sekaipetplant.com
```

预期：前 10 次左右 200/401，随后出现 429，且响应体是 JSON
`{"code":429,"message":"请求过于频繁，请稍后再试","data":null}`。

## 6. 回滚

```bash
sudo cp /opt/ilink/backups/<STAMP>-pre-rate-limit/nginx-site.conf /etc/nginx/sites-available/sekaipetplant.com
sudo rm -f /etc/nginx/conf.d/ilink-rate-limit.conf
sudo nginx -t && sudo systemctl reload nginx
```

应用侧无需任何操作。

## 说明

- 限流键是 `$binary_remote_addr`（客户端 IP）。Nginx 会把 `X-Real-IP` 覆盖为真实来源，
  应用侧审计与登录失败计数均优先读取该头。
- SSE 单独 zone（`ilink_ai_stream`，连接上限 4/单 IP），不会继承普通 AI 限流。
- 静态资源不在限流范围内（`location /` 未加 limit_req）。
