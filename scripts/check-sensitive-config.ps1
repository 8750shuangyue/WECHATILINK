$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $repoRoot 'src/main/resources/application.properties'
$localConfig = Join-Path $repoRoot 'application-local.properties'

$sensitiveKeys = @(
    'dashscope.api-key',
    'dashscope.embedding.api-key',
    'spring.ai.openai.api-key',
    'spring.datasource.password',
    'weather.api.api-key',
    'amap.api-key',
    'xunfei.tts.api-key',
    'xunfei.tts.api-secret',
    'baidu.search.api-key',
    'webpush.vapid.public-key',
    'webpush.vapid.private-key'
)

$failures = @()
$lineNumber = 0

foreach ($line in [System.IO.File]::ReadAllLines($configPath)) {
    $lineNumber++
    if ($line -match '^\s*([^#=]+)=(.*)$') {
        $key = $matches[1].Trim()
        $value = $matches[2].Trim()

        if ($sensitiveKeys -contains $key -and -not $value.StartsWith('${')) {
            $failures += "Literal value found: $key (line $lineNumber)"
        }
    }
}

& git -C $repoRoot check-ignore -q $localConfig
if ($LASTEXITCODE -ne 0) {
    $failures += 'application-local.properties is not ignored by Git.'
}

if ($failures.Count -gt 0) {
    Write-Host 'Sensitive config check failed:' -ForegroundColor Red
    $failures | ForEach-Object { Write-Host " - $_" -ForegroundColor Red }
    exit 1
}

Write-Host 'Sensitive config check passed.' -ForegroundColor Green
