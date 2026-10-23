# Security Policy

## 凭据处理规则

- 仓库中只允许出现环境变量占位符（如 `${DASHSCOPE_API_KEY:}`）和 `.example` 示例值。
- 真实密钥只放在服务器的 `application-local.properties`、systemd 环境变量或密钥管理服务中。
- 不要在 Issue、PR、聊天记录、日志或截图中粘贴真实密钥。
- 一旦怀疑密钥泄露，先在服务商控制台撤销/轮换，再处理仓库历史；删除提交不能使已泄露的密钥失效。

## 本地提交前扫描

启用仓库自带的 pre-commit 钩子：

```bash
git config core.hooksPath .githooks
```

钩子会对暂存文件运行：

```bash
python scripts/secret_scan.py --staged
```

手动检查当前工作树或历史：

```bash
python scripts/secret_scan.py --tracked
python scripts/secret_scan.py --history
```

`--history` 会报告历史提交中的遗留凭据，适合在历史清理前后做对照；CI 默认只扫描当前工作树。

## CI

`.github/workflows/secret-scan.yml` 在 push 和 PR 时运行当前工作树扫描，并复用
`scripts/check-sensitive-config.ps1` 检查 `application.properties` 是否仍为占位符。

## 依赖漏洞

- Dependabot 每周一为主工程、`TEST/` 和 GitHub Actions 创建升级 PR。
- CI 的 `Dependency Scan` 运行 `scripts/osv_scan.py`，对新增 HIGH/CRITICAL 失败。
- `.osv-baseline.json` 是已接受基线，每月复核；新增 CRITICAL 应在 48 小时内分诊，HIGH 在一周内分诊。
- 补丁升级与密钥轮换、功能改动分开提交；大版本升级单独立项。

## 漏洞报告

请通过 GitHub Security Advisories 私下报告，不要公开提交 Issue。报告时不要附带真实凭据。
