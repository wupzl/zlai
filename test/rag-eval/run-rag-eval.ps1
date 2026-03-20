param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$EvalFile = '.\test\rag-eval\output-java-study-resource\rag-eval.jsonl',
    [string]$OutputDir = '.\test\rag-eval\results',
    [string]$BearerToken = '',
    [int]$TopK = 8,
    [int]$MaxSamples = 0,
    [int]$TimeoutSeconds = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

function Resolve-FullPath {
    param([string]$PathValue)
    $resolved = Resolve-Path -LiteralPath $PathValue -ErrorAction SilentlyContinue
    if ($resolved) { return $resolved.Path }
    return [System.IO.Path]::GetFullPath($PathValue)
}

function Normalize-Text {
    param([string]$Text)
    if ($null -eq $Text) { return '' }
    $normalized = $Text -replace "`r`n", "`n"
    $normalized = $normalized -replace "`r", "`n"
    $normalized = $normalized -replace '\s+', ' '
    return $normalized.Trim().ToLowerInvariant()
}

function Get-CjkBigrams {
    param([string]$Text)
    $chars = @([regex]::Matches($Text, '\p{IsCJKUnifiedIdeographs}') | ForEach-Object { $_.Value })
    $tokens = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt ($chars.Count - 1); $i++) {
        $tokens.Add($chars[$i] + $chars[$i + 1]) | Out-Null
    }
    return $tokens
}

function Get-LatinTokens {
    param([string]$Text)
    return [regex]::Matches($Text.ToLowerInvariant(), '[a-z0-9_\-]{2,}') | ForEach-Object { $_.Value }
}

function Get-KeywordSet {
    param([string]$Text)
    $normalized = Normalize-Text -Text $Text
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($token in (Get-LatinTokens -Text $normalized)) { [void]$set.Add($token) }
    foreach ($token in (Get-CjkBigrams -Text $normalized)) { [void]$set.Add($token) }
    return $set
}

function Get-CoverageScore {
    param(
        [string]$Expected,
        [string]$Actual
    )
    $expectedSet = Get-KeywordSet -Text $Expected
    if ($expectedSet.Count -eq 0) { return 0.0 }
    $actualSet = Get-KeywordSet -Text $Actual
    $hit = 0
    foreach ($token in $expectedSet) {
        if ($actualSet.Contains($token)) { $hit++ }
    }
    return [Math]::Round(($hit / [double]$expectedSet.Count), 4)
}

function Test-ContainsAllSections {
    param(
        [object[]]$ExpectedSections,
        [string]$Context
    )
    $sections = @($ExpectedSections)
    if ($null -eq $ExpectedSections -or $sections.Count -eq 0) { return $true }
    $normalizedContext = Normalize-Text -Text $Context
    foreach ($section in $sections) {
        $sectionText = Normalize-Text -Text ([string]$section)
        if ([string]::IsNullOrWhiteSpace($sectionText)) { continue }
        if (-not $normalizedContext.Contains($sectionText)) { return $false }
    }
    return $true
}

function Invoke-RagQuery {
    param(
        [string]$Url,
        [hashtable]$Headers,
        [string]$Question,
        [int]$TopKValue,
        [int]$Timeout
    )
    $body = @{ query = $Question; topK = $TopKValue } | ConvertTo-Json
    return Invoke-RestMethod -Uri ($Url.TrimEnd('/') + '/api/rag/query') -Method Post -Headers $Headers -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec $Timeout
}

$evalFullPath = Resolve-FullPath -PathValue $EvalFile
if (-not (Test-Path -LiteralPath $evalFullPath)) { throw "Eval file not found: $EvalFile" }

$outputFullPath = Resolve-FullPath -PathValue $OutputDir
New-Item -ItemType Directory -Force -Path $outputFullPath | Out-Null
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$resultJsonl = Join-Path $outputFullPath ("rag-eval-result-$timestamp.jsonl")
$summaryJson = Join-Path $outputFullPath ("rag-eval-summary-$timestamp.json")

$headers = @{}
if (-not [string]::IsNullOrWhiteSpace($BearerToken)) {
    $headers['Authorization'] = "Bearer $BearerToken"
}

$lines = [System.IO.File]::ReadAllLines($evalFullPath, [System.Text.Encoding]::UTF8)
$selectedLines = if ($MaxSamples -gt 0) { $lines | Select-Object -First $MaxSamples } else { $lines }
$results = New-Object System.Collections.Generic.List[object]
$typeStats = @{}
$passed = 0
$failed = 0

foreach ($line in $selectedLines) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $sample = $line | ConvertFrom-Json
    $context = ''
    $matches = @()
    $requestError = $null
    try {
        $response = Invoke-RagQuery -Url $BaseUrl -Headers $headers -Question $sample.question -TopKValue $TopK -Timeout $TimeoutSeconds
        $context = [string]$response.data.context
        $matches = @($response.data.matches)
    } catch {
        $requestError = $_.Exception.Message
    }

    $matchText = (($matches | ForEach-Object { $_.content }) -join "`n")
    $contextCoverage = if ($requestError) { 0.0 } else { Get-CoverageScore -Expected ([string]$sample.expectedAnswer) -Actual $context }
    $matchCoverage = if ($requestError) { 0.0 } else { Get-CoverageScore -Expected ([string]$sample.expectedAnswer) -Actual $matchText }
    $sectionHit = if ($requestError) { $false } else { Test-ContainsAllSections -ExpectedSections $sample.expectedSections -Context ($context + "`n" + $matchText) }
    $score = [Math]::Round(($contextCoverage * 0.7) + ($matchCoverage * 0.3) + ($(if ($sectionHit) { 0.1 } else { 0.0 })), 4)
    $pass = (-not $requestError) -and ($contextCoverage -ge 0.35 -or $matchCoverage -ge 0.45) -and $sectionHit

    if (-not $typeStats.ContainsKey($sample.type)) {
        $typeStats[$sample.type] = [ordered]@{ total = 0; passed = 0; avgScore = 0.0; avgContextCoverage = 0.0; avgMatchCoverage = 0.0 }
    }
    $typeStats[$sample.type].total++
    $typeStats[$sample.type].avgScore += $score
    $typeStats[$sample.type].avgContextCoverage += $contextCoverage
    $typeStats[$sample.type].avgMatchCoverage += $matchCoverage
    if ($pass) { $typeStats[$sample.type].passed++ }

    if ($pass) { $passed++ } else { $failed++ }

    $results.Add([pscustomobject]@{
        id = $sample.id
        type = $sample.type
        relativePath = $sample.relativePath
        question = $sample.question
        expectedSections = @($sample.expectedSections)
        contextCoverage = $contextCoverage
        matchCoverage = $matchCoverage
        sectionHit = $sectionHit
        score = $score
        passed = $pass
        error = $requestError
        contextPreview = if ($context.Length -gt 300) { $context.Substring(0, 300) + '...' } else { $context }
    }) | Out-Null
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$writer = New-Object System.IO.StreamWriter($resultJsonl, $false, $utf8NoBom)
try {
    foreach ($item in $results) {
        $writer.WriteLine(($item | ConvertTo-Json -Depth 6 -Compress))
    }
} finally {
    $writer.Dispose()
}

foreach ($key in @($typeStats.Keys)) {
    $entry = $typeStats[$key]
    if ($entry.total -gt 0) {
        $entry.avgScore = [Math]::Round(($entry.avgScore / [double]$entry.total), 4)
        $entry.avgContextCoverage = [Math]::Round(($entry.avgContextCoverage / [double]$entry.total), 4)
        $entry.avgMatchCoverage = [Math]::Round(($entry.avgMatchCoverage / [double]$entry.total), 4)
        $entry.passRate = [Math]::Round(($entry.passed / [double]$entry.total), 4)
    }
}

$summary = [pscustomobject]@{
    baseUrl = $BaseUrl
    evalFile = $evalFullPath
    resultFile = $resultJsonl
    sampleCount = $results.Count
    passed = $passed
    failed = $failed
    passRate = if ($results.Count -gt 0) { [Math]::Round(($passed / [double]$results.Count), 4) } else { 0.0 }
    topK = $TopK
    byType = $typeStats
    generatedAt = (Get-Date).ToString('s')
}
$summaryWriter = New-Object System.IO.StreamWriter($summaryJson, $false, $utf8NoBom)
try {
    $summaryWriter.Write(($summary | ConvertTo-Json -Depth 6))
} finally {
    $summaryWriter.Dispose()
}

Write-Host 'Result:' $resultJsonl
Write-Host 'Summary:' $summaryJson

