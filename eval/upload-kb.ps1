# upload-kb.ps1 - 上传知识库文档到运行中的应用 (http://localhost:8080)
# 用法: powershell -ExecutionPolicy Bypass -File upload-kb.ps1
$ErrorActionPreference = "Stop"
$Base = "http://localhost:8080"
$User = "eval_runner"
$Pass = "wyf580192"
$DocsDir = "C:\Users\wyf58\soft\WECHATILINK-proj\eval\kb-docs"

# 1. 登录获取 session
$loginBody = "{""userName"":""$User"",""password"":""$Pass""}"
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
try {
    $r = Invoke-WebRequest -Uri "$Base/api/auth/login" -Method POST -Body $loginBody -ContentType "application/json" -WebSession $session -UseBasicParsing -TimeoutSec 30
    Write-Host "[OK] login status: $($r.StatusCode) body: $($r.Content)"
} catch {
    Write-Host "[XX] login failed: $($_.Exception.Message)"
    Write-Host "[..] 请确认应用已在 8080 端口运行: mvn spring-boot:run"
    exit 1
}

# 2. 逐个上传文档
$files = Get-ChildItem $DocsDir -Filter *.md | Sort-Object Name
foreach ($f in $files) {
    try {
        $resp = Invoke-WebRequest -Uri "$Base/api/kb/upload" -Method POST -Form @{ file = Get-Item $f.FullName } -WebSession $session -UseBasicParsing -TimeoutSec 120
        Write-Host "[OK] $($f.Name) -> $($resp.Content)"
    } catch {
        Write-Host "[XX] $($f.Name) upload failed: $($_.Exception.Message)"
    }
}

# 3. 列出实际 sourceId
Write-Host ""
Write-Host "==== 知识库文档列表（用于回填 expectedSourceIds）===="
try {
    $list = Invoke-WebRequest -Uri "$Base/api/kb/list" -Method GET -WebSession $session -UseBasicParsing -TimeoutSec 30
    $data = $list.Content | ConvertFrom-Json
    foreach ($d in $data.data) {
        Write-Host ("sourceId=" + $d.sourceId + " | count=" + $d.count + " | sample=" + $d.sample.Substring(0, [Math]::Min(60, $d.sample.Length)))
    }
} catch {
    Write-Host "[XX] list failed: $($_.Exception.Message)"
}