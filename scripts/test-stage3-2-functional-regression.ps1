[CmdletBinding()]
param(
    [string]$BaseUrl = 'https://sekaipetplant.com',
    [string]$Username = $env:ILINK_SMOKE_USERNAME,
    [string]$Password = $env:ILINK_SMOKE_PASSWORD,
    [switch]$RunWriteSmoke,
    [switch]$RequireAuthenticated,
    [switch]$RequireRagObservation,
    [int]$TimeoutSeconds = 180,
    [string]$OutputRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot '.runtime-reports'
}
if ($TimeoutSeconds -lt 30) {
    throw 'TimeoutSeconds must be at least 30.'
}

$BaseUrl = $BaseUrl.TrimEnd('/')
$baseUri = [Uri]$BaseUrl
$origin = $baseUri.GetLeftPart([System.UriPartial]::Authority)
$results = [System.Collections.Generic.List[object]]::new()
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$writeSmokeSessionReady = $false

function Add-Result {
    param(
        [string]$Name,
        [ValidateSet('PASS', 'FAIL', 'SKIP')]
        [string]$Status,
        [string]$Detail
    )
    $results.Add([pscustomobject]@{
            name = $Name
            status = $Status
            detail = $Detail
        })
    $color = switch ($Status) {
        'PASS' { 'Green' }
        'FAIL' { 'Red' }
        default { 'Yellow' }
    }
    Write-Host ("{0}  {1} - {2}" -f $Status, $Name, $Detail) -ForegroundColor $color
}

function Get-PropertyValue {
    param(
        [object]$InputObject,
        [string]$Name,
        $Default = $null
    )
    if ($null -eq $InputObject) {
        return $Default
    }
    $property = $InputObject.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $Default
    }
    return $property.Value
}

function Invoke-Http {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [hashtable]$Headers = $null
    )

    $requestHeaders = [ordered]@{
        Origin = $origin
        'Accept-Language' = 'zh-CN'
    }
    if ($null -ne $Headers) {
        foreach ($key in $Headers.Keys) {
            $requestHeaders[$key] = $Headers[$key]
        }
    }

    $params = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        WebSession = $session
        Headers = $requestHeaders
        TimeoutSec = $TimeoutSeconds
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json; charset=UTF-8'
        $params.Body = $Body | ConvertTo-Json -Depth 10 -Compress
    }
    return Invoke-WebRequest @params
}

function Get-JsonBody {
    param($Response)
    if ($null -eq $Response -or [string]::IsNullOrWhiteSpace($Response.Content)) {
        return $null
    }
    try {
        return $Response.Content | ConvertFrom-Json
    } catch {
        return $null
    }
}

function ConvertFrom-SsePayload {
    param([string]$Payload)
    if ([string]::IsNullOrWhiteSpace($Payload) -or $Payload.Trim() -eq '[DONE]') {
        return ''
    }
    try {
        $json = $Payload | ConvertFrom-Json
        if ($json -is [string]) {
            return $json
        }
        foreach ($field in @('content', 'text', 'message', 'data')) {
            $fieldValue = Get-PropertyValue -InputObject $json -Name $field
            if ($null -ne $fieldValue -and $fieldValue -isnot [System.Management.Automation.PSCustomObject]) {
                return [string]$fieldValue
            }
        }
    } catch {
        return $Payload
    }
    return $Payload
}

function Get-SseEvents {
    param([string]$Content)
    $events = [System.Collections.Generic.List[object]]::new()
    $eventName = 'message'
    $dataLines = [System.Collections.Generic.List[string]]::new()

    foreach ($line in ($Content -split "`r?`n")) {
        if ($line -eq '') {
            if ($dataLines.Count -gt 0) {
                $events.Add([pscustomobject]@{
                        name = $eventName
                        data = ($dataLines -join "`n")
                    })
            }
            $eventName = 'message'
            $dataLines.Clear()
            continue
        }
        if ($line.StartsWith('event:')) {
            $eventName = $line.Substring(6).Trim()
            continue
        }
        if ($line.StartsWith('data:')) {
            $payload = $line.Substring(5)
            if ($payload.StartsWith(' ')) {
                $payload = $payload.Substring(1)
            }
            $dataLines.Add($payload)
        }
    }
    if ($dataLines.Count -gt 0) {
        $events.Add([pscustomobject]@{
                name = $eventName
                data = ($dataLines -join "`n")
            })
    }
    return $events
}

function Invoke-SseChat {
    param([string]$Message)
    $response = Invoke-Http -Method Post -Path '/api/ai/chat/stream' `
        -Body @{ message = $Message } -Headers @{ Accept = 'text/event-stream' }
    $traceId = $null
    $text = [System.Text.StringBuilder]::new()
    if ($response.StatusCode -eq 200) {
        foreach ($event in Get-SseEvents -Content $response.Content) {
            if ($event.name -eq 'rag-trace') {
                try {
                    $traceJson = $event.data | ConvertFrom-Json
                    $eventTraceId = Get-PropertyValue -InputObject $traceJson -Name 'traceId'
                    if (-not [string]::IsNullOrWhiteSpace([string]$eventTraceId)) {
                        $traceId = [string]$eventTraceId
                    }
                } catch {
                    $traceId = $null
                }
                continue
            }
            $decoded = ConvertFrom-SsePayload -Payload $event.data
            if (-not [string]::IsNullOrWhiteSpace($decoded)) {
                [void]$text.Append($decoded)
            }
        }
    }
    return [pscustomobject]@{
        status = [int]$response.StatusCode
        trace_id = $traceId
        text = $text.ToString()
    }
}

function Get-RagObservation {
    param([string]$TraceId)
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $response = Invoke-Http -Method Get `
            -Path "/api/internal/rag/retrievals/$([Uri]::EscapeDataString($TraceId))"
        if ($response.StatusCode -eq 200 -or $response.StatusCode -eq 401 -or
            $response.StatusCode -eq 403 -or $response.StatusCode -eq 404) {
            return $response
        }
        if ($attempt -lt 3) {
            Start-Sleep -Seconds 1
        }
    }
    return $response
}

$startedAt = Get-Date
try {
    $rootResponse = Invoke-Http -Method Get -Path '/'
    $rootLooksLikeHtml = $rootResponse.Content -match '(?is)<!doctype|<html'
    if ($rootResponse.StatusCode -eq 200 -and $rootLooksLikeHtml) {
        Add-Result 'public_page' 'PASS' 'status=200; content=html'
    } else {
        Add-Result 'public_page' 'FAIL' "status=$($rootResponse.StatusCode); html=$rootLooksLikeHtml"
    }

    $anonymousResponse = Invoke-Http -Method Get -Path '/api/community/posts?page=1&size=5'
    $anonymousJson = Get-JsonBody -Response $anonymousResponse
    $anonymousCode = [int](Get-PropertyValue -InputObject $anonymousJson -Name 'code' -Default 0)
    if ($anonymousResponse.StatusCode -eq 401 -and $anonymousCode -eq 401) {
        Add-Result 'anonymous_protected_api' 'PASS' 'status=401; code=401'
    } else {
        Add-Result 'anonymous_protected_api' 'FAIL' `
            "status=$($anonymousResponse.StatusCode); code=$anonymousCode"
    }

    $hasCredentials = -not [string]::IsNullOrWhiteSpace($Username) -and
        -not [string]::IsNullOrWhiteSpace($Password)
    $authenticated = $false
    if (-not $hasCredentials) {
        $detail = 'ILINK_SMOKE_USERNAME / ILINK_SMOKE_PASSWORD not configured'
        if ($RequireAuthenticated -or $RunWriteSmoke) {
            Add-Result 'authenticated_checks' 'FAIL' $detail
        } else {
            Add-Result 'authenticated_checks' 'SKIP' $detail
        }
    } else {
        $loginResponse = Invoke-Http -Method Post -Path '/api/auth/login' `
            -Body @{ userName = $Username; password = $Password }
        $loginJson = Get-JsonBody -Response $loginResponse
        $loginCode = [int](Get-PropertyValue -InputObject $loginJson -Name 'code' -Default 0)
        if ($loginResponse.StatusCode -eq 200 -and $loginCode -eq 200) {
            $authenticated = $true
            Add-Result 'login' 'PASS' 'status=200; code=200'
        } else {
            Add-Result 'login' 'FAIL' `
                "status=$($loginResponse.StatusCode); code=$loginCode"
        }

        if ($authenticated) {
            $meResponse = Invoke-Http -Method Get -Path '/api/auth/me'
            $meJson = Get-JsonBody -Response $meResponse
            $meCode = [int](Get-PropertyValue -InputObject $meJson -Name 'code' -Default 0)
            if ($meResponse.StatusCode -eq 200 -and $meCode -eq 200) {
                Add-Result 'session_identity' 'PASS' 'status=200; code=200'
            } else {
                Add-Result 'session_identity' 'FAIL' `
                    "status=$($meResponse.StatusCode); code=$meCode"
            }

            $postsResponse = Invoke-Http -Method Get -Path '/api/community/posts?page=1&size=5'
            $postsJson = Get-JsonBody -Response $postsResponse
            $postsCode = [int](Get-PropertyValue -InputObject $postsJson -Name 'code' -Default 0)
            $postsData = Get-PropertyValue -InputObject $postsJson -Name 'data'
            $postItems = @(Get-PropertyValue -InputObject $postsData -Name 'content' -Default @())
            if ($postsResponse.StatusCode -eq 200 -and $postsCode -eq 200 -and
                $postItems.Count -gt 0) {
                Add-Result 'community_list' 'PASS' "status=200; posts=$($postItems.Count)"
                $postId = [int]$postItems[0].id
                $postResponse = Invoke-Http -Method Get -Path "/api/community/posts/$postId"
                $postJson = Get-JsonBody -Response $postResponse
                $postCode = [int](Get-PropertyValue -InputObject $postJson -Name 'code' -Default 0)
                $postData = Get-PropertyValue -InputObject $postJson -Name 'data'
                $postValue = Get-PropertyValue -InputObject $postData -Name 'post'
                if ($postResponse.StatusCode -eq 200 -and $postCode -eq 200 -and
                    $null -ne $postValue) {
                    Add-Result 'community_detail' 'PASS' "status=200; postId=$postId"
                } else {
                    Add-Result 'community_detail' 'FAIL' `
                        "status=$($postResponse.StatusCode); code=$postCode; postId=$postId"
                }
            } else {
                Add-Result 'community_list' 'FAIL' `
                    "status=$($postsResponse.StatusCode); code=$postsCode; posts=$($postItems.Count)"
            }
        }
    }

    if ($RunWriteSmoke -and $authenticated) {
        $writeSmokeSessionReady = $true
        $marker = 'ILINK-REG-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
        $first = Invoke-SseChat -Message "自动回归测试，请只回复“已记录”。测试标记：$marker"
        if ($first.status -eq 200) {
            Add-Result 'stream_memory_write' 'PASS' "status=200; trace=$(-not [string]::IsNullOrWhiteSpace($first.trace_id))"
        } else {
            Add-Result 'stream_memory_write' 'FAIL' "status=$($first.status)"
        }

        if (-not [string]::IsNullOrWhiteSpace($first.trace_id)) {
            $observation = Get-RagObservation -TraceId $first.trace_id
            $observationJson = Get-JsonBody -Response $observation
            $observationCode = [int](Get-PropertyValue -InputObject $observationJson -Name 'code' -Default 0)
            $observationData = Get-PropertyValue -InputObject $observationJson -Name 'data'
            $observedTraceId = [string](Get-PropertyValue -InputObject $observationData -Name 'traceId')
            $observationResults = @(Get-PropertyValue -InputObject $observationData -Name 'results' -Default @())
            if ($observation.StatusCode -eq 200 -and $observationCode -eq 200 -and
                $observedTraceId -eq $first.trace_id) {
                Add-Result 'rag_observation_lookup' 'PASS' `
                    "status=200; results=$($observationResults.Count)"
            } elseif ($observation.StatusCode -eq 403) {
                $detail = 'observation disabled or user not allowlisted'
                if ($RequireRagObservation) {
                    Add-Result 'rag_observation_lookup' 'FAIL' $detail
                } else {
                    Add-Result 'rag_observation_lookup' 'SKIP' $detail
                }
            } else {
                Add-Result 'rag_observation_lookup' 'FAIL' `
                    "status=$($observation.StatusCode); code=$observationCode"
            }
        } else {
            Add-Result 'rag_observation_lookup' 'FAIL' 'rag-trace event did not include traceId'
        }

        $second = Invoke-SseChat -Message '请只回答上面要求你记住的完整测试标记。'
        if ($second.status -eq 200 -and $second.text.Contains($marker)) {
            Add-Result 'multi_turn_memory' 'PASS' 'marker recalled'
        } else {
            Add-Result 'multi_turn_memory' 'FAIL' `
                "status=$($second.status); markerFound=$($second.text.Contains($marker))"
        }

        $clearResponse = Invoke-Http -Method Post -Path '/api/ai/chat/clear'
        $clearJson = Get-JsonBody -Response $clearResponse
        $clearSuccess = [bool](Get-PropertyValue -InputObject $clearJson -Name 'success' -Default $false)
        $clearCount = Get-PropertyValue -InputObject $clearJson -Name 'cleared' -Default 0
        if ($clearResponse.StatusCode -eq 200 -and $clearSuccess) {
            Add-Result 'clear_conversation' 'PASS' "status=200; cleared=$clearCount"
        } else {
            Add-Result 'clear_conversation' 'FAIL' `
                "status=$($clearResponse.StatusCode); success=$clearSuccess"
        }

        $third = Invoke-SseChat -Message '请只回答你刚才记住的测试标记；如果不知道，只回答“不知道”。'
        if ($third.status -eq 200 -and -not $third.text.Contains($marker)) {
            Add-Result 'clear_effect' 'PASS' 'old marker was not recalled'
        } else {
            Add-Result 'clear_effect' 'FAIL' `
                "status=$($third.status); markerStillFound=$($third.text.Contains($marker))"
        }
    } elseif ($RunWriteSmoke -and -not $authenticated) {
        Add-Result 'write_smoke' 'FAIL' 'write smoke requires valid smoke credentials'
    } else {
        Add-Result 'write_smoke' 'SKIP' 'disabled; use -RunWriteSmoke for memory and clear checks'
    }

    if ($authenticated) {
        if ($writeSmokeSessionReady) {
            try {
                [void](Invoke-Http -Method Post -Path '/api/ai/chat/clear')
            } catch {
                Write-Warning 'Pre-logout smoke-session cleanup request failed.'
            } finally {
                $writeSmokeSessionReady = $false
            }
        }

        $logoutResponse = Invoke-Http -Method Post -Path '/api/auth/logout'
        $logoutJson = Get-JsonBody -Response $logoutResponse
        $logoutCode = [int](Get-PropertyValue -InputObject $logoutJson -Name 'code' -Default 0)
        if ($logoutResponse.StatusCode -eq 200 -and $logoutCode -eq 200) {
            Add-Result 'logout' 'PASS' 'status=200; code=200'
        } else {
            Add-Result 'logout' 'FAIL' `
                "status=$($logoutResponse.StatusCode); code=$logoutCode"
        }

        $postLogoutResponse = Invoke-Http -Method Get -Path '/api/auth/me'
        $postLogoutJson = Get-JsonBody -Response $postLogoutResponse
        $postLogoutCode = [int](Get-PropertyValue -InputObject $postLogoutJson -Name 'code' -Default 0)
        $postLogoutMessage = [string](Get-PropertyValue -InputObject $postLogoutJson -Name 'message' -Default '')
        $sessionInvalid = (
            $postLogoutResponse.StatusCode -eq 401 -and $postLogoutCode -eq 401
        ) -or (
            $postLogoutResponse.StatusCode -eq 200 -and
            $postLogoutCode -ne 200 -and
            $postLogoutMessage -eq '未登录'
        )
        if ($sessionInvalid) {
            Add-Result 'post_logout_session_invalid' 'PASS' `
                "status=$($postLogoutResponse.StatusCode); code=$postLogoutCode; message=$postLogoutMessage"
        } else {
            Add-Result 'post_logout_session_invalid' 'FAIL' `
                "status=$($postLogoutResponse.StatusCode); code=$postLogoutCode; message=$postLogoutMessage"
        }
    } else {
        Add-Result 'logout' 'SKIP' 'login did not complete'
        Add-Result 'post_logout_session_invalid' 'SKIP' 'login did not complete'
    }
} catch {
    Add-Result 'unhandled_exception' 'FAIL' $_.Exception.Message
} finally {
    if ($writeSmokeSessionReady) {
        try {
            [void](Invoke-Http -Method Post -Path '/api/ai/chat/clear')
        } catch {
            Write-Warning 'Final smoke-session cleanup request failed.'
        }
    }
}

New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$finishedAt = Get-Date
$failures = @($results | Where-Object { $_.status -eq 'FAIL' })
$reportStamp = $finishedAt.ToString('yyyyMMdd-HHmmss')
$jsonPath = Join-Path $OutputRoot "stage3-2-functional-regression-$reportStamp.json"
$markdownPath = Join-Path $OutputRoot "stage3-2-functional-regression-$reportStamp.md"
$report = [ordered]@{
    started_at = $startedAt.ToString('o')
    finished_at = $finishedAt.ToString('o')
    base_url = $BaseUrl
    write_smoke = [bool]$RunWriteSmoke
    credentials_configured = -not [string]::IsNullOrWhiteSpace($Username) -and
        -not [string]::IsNullOrWhiteSpace($Password)
    results = $results
    failures = $failures
    passed = @($results | Where-Object { $_.status -eq 'PASS' }).Count
    failed = $failures.Count
    skipped = @($results | Where-Object { $_.status -eq 'SKIP' }).Count
}
$report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

$markdown = [System.Collections.Generic.List[string]]::new()
$markdown.Add('# 阶段 3.2 功能回归报告')
$markdown.Add('')
$markdown.Add("- 执行时间：``$($finishedAt.ToString('yyyy-MM-dd HH:mm:ss zzz'))``")
$markdown.Add("- 测试地址：``$BaseUrl``")
$markdown.Add("- 写入冒烟：``$([bool]$RunWriteSmoke)``")
$markdown.Add("- 结果：``$($report.passed)`` 通过，``$($report.failed)`` 失败，``$($report.skipped)`` 跳过")
$markdown.Add('')
$markdown.Add('| 检查项 | 结果 | 说明 |')
$markdown.Add('| --- | --- | --- |')
foreach ($result in $results) {
    $markdown.Add("| $($result.name) | $($result.status) | $($result.detail) |")
}
$markdown.Add('')
$markdown.Add("- JSON：``$jsonPath``")
$markdown | Set-Content -LiteralPath $markdownPath -Encoding UTF8

Write-Host "Report: $jsonPath"
Write-Host "Summary: $markdownPath"
if ($failures.Count -gt 0) {
    Write-Host "Functional regression failed with $($failures.Count) issue(s)." -ForegroundColor Red
    exit 1
}
Write-Host 'Functional regression passed.' -ForegroundColor Green
