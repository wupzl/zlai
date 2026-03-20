param(
    [Parameter(Mandatory = $true)]
    [string]$SourceDir,

    [string]$WorkspaceDir = ".\test\rag-eval\workspace"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

try {
    [System.Text.Encoding]::RegisterProvider([System.Text.CodePagesEncodingProvider]::Instance)
} catch {
}

function Resolve-FullPath {
    param([string]$PathValue)
    $resolved = Resolve-Path -LiteralPath $PathValue -ErrorAction SilentlyContinue
    if ($resolved) {
        return $resolved.Path
    }
    return [System.IO.Path]::GetFullPath($PathValue)
}

function Get-RelativePathCompat {
    param(
        [string]$BasePath,
        [string]$TargetPath
    )
    $base = (Resolve-FullPath -PathValue $BasePath).TrimEnd([char[]]@('\', '/'))
    $target = Resolve-FullPath -PathValue $TargetPath
    if ($target.StartsWith($base, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $target.Substring($base.Length).TrimStart([char[]]@('\', '/'))
    }
    return Split-Path -Leaf $target
}

function Get-StrictEncoding {
    param([string]$Name)
    switch ($Name) {
        "utf-8" { return New-Object System.Text.UTF8Encoding($false, $true) }
        "utf-16-le" { return New-Object System.Text.UnicodeEncoding($false, $true, $true) }
        "utf-16-be" { return New-Object System.Text.UnicodeEncoding($true, $true, $true) }
        default { return [System.Text.Encoding]::GetEncoding($Name) }
    }
}

function Test-Decode {
    param([byte[]]$Bytes, [System.Text.Encoding]$Encoding)
    try {
        $text = $Encoding.GetString($Bytes)
        return @{ Success = $true; Text = $text }
    } catch {
        return @{ Success = $false; Text = $null }
    }
}

function Get-BestTextDecode {
    param([byte[]]$Bytes)

    if ($Bytes.Length -ge 3 -and $Bytes[0] -eq 0xEF -and $Bytes[1] -eq 0xBB -and $Bytes[2] -eq 0xBF) {
        return @{ Encoding = 'utf-8-bom'; Text = [System.Text.Encoding]::UTF8.GetString($Bytes, 3, $Bytes.Length - 3) }
    }
    if ($Bytes.Length -ge 2 -and $Bytes[0] -eq 0xFF -and $Bytes[1] -eq 0xFE) {
        return @{ Encoding = 'utf-16-le-bom'; Text = [System.Text.Encoding]::Unicode.GetString($Bytes, 2, $Bytes.Length - 2) }
    }
    if ($Bytes.Length -ge 2 -and $Bytes[0] -eq 0xFE -and $Bytes[1] -eq 0xFF) {
        return @{ Encoding = 'utf-16-be-bom'; Text = [System.Text.Encoding]::BigEndianUnicode.GetString($Bytes, 2, $Bytes.Length - 2) }
    }

    foreach ($candidate in @('utf-8', 'utf-16-le', 'utf-16-be', 'gb18030', 'gbk', 'big5')) {
        $decoded = Test-Decode -Bytes $Bytes -Encoding (Get-StrictEncoding -Name $candidate)
        if (-not $decoded.Success) { continue }
        $nullCharCount = ([regex]::Matches($decoded.Text, [string][char]0x0000)).Count
        if ($nullCharCount -gt [Math]::Max(2, [int]($decoded.Text.Length / 20))) { continue }
        return @{ Encoding = $candidate; Text = $decoded.Text }
    }

    return @{ Encoding = 'system-default'; Text = [System.Text.Encoding]::Default.GetString($Bytes) }
}

function Normalize-Text {
    param([string]$Text)
    $normalized = $Text -replace "`r`n", "`n"
    $normalized = $normalized -replace "`r", "`n"
    return $normalized.TrimEnd() + "`n"
}

function Get-TextQuality {
    param([string]$Text)

    $length = if ($null -eq $Text) { 0 } else { $Text.Length }
    if ($length -eq 0) {
        return [pscustomobject]@{ suspicious = $true; score = 1.0; reasons = @('empty'); replacementCount = 0; mojibakeCount = 0; weirdPunctuationCount = 0; latinRatio = 0.0; cjkRatio = 0.0 }
    }

    $replacementCount = ([regex]::Matches($Text, [string][char]0xFFFD)).Count
    $mojibakeCount = ([regex]::Matches($Text, '[\x00-\x08\x0B\x0C\x0E-\x1F]|[ÃÆÐÑØÞßàáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿ]{2,}')).Count
    $weirdPunctuationCount = ([regex]::Matches($Text, '鈥|銆|锛|锟|�')).Count
    $latinCount = ([regex]::Matches($Text, '[A-Za-z]')).Count
    $hanCount = ([regex]::Matches($Text, '\p{IsCJKUnifiedIdeographs}')).Count

    $latinRatio = [Math]::Round(($latinCount / [double]$length), 4)
    $cjkRatio = [Math]::Round(($hanCount / [double]$length), 4)
    $replacementRatio = $replacementCount / [double]$length
    $mojibakeRatio = $mojibakeCount / [double]$length
    $weirdPunctuationRatio = $weirdPunctuationCount / [double]$length

    $reasons = New-Object System.Collections.Generic.List[string]
    if ($replacementRatio -gt 0.001) { $reasons.Add('replacement-char') | Out-Null }
    if ($mojibakeRatio -gt 0.008) { $reasons.Add('mojibake-pattern') | Out-Null }
    if ($weirdPunctuationRatio -gt 0.01) { $reasons.Add('weird-punctuation') | Out-Null }
    if ($hanCount -gt 20 -and $latinRatio -gt 0.45 -and $cjkRatio -lt 0.1) { $reasons.Add('cjk-decoding-mismatch') | Out-Null }

    $score = [Math]::Min(1.0, ($replacementRatio * 8.0) + ($mojibakeRatio * 12.0) + ($weirdPunctuationRatio * 6.0) + ($(if ($reasons.Count -gt 0) { 0.2 } else { 0.0 })))
    return [pscustomobject]@{
        suspicious = ($reasons.Count -gt 0)
        score = [Math]::Round($score, 4)
        reasons = @($reasons)
        replacementCount = $replacementCount
        mojibakeCount = $mojibakeCount
        weirdPunctuationCount = $weirdPunctuationCount
        latinRatio = $latinRatio
        cjkRatio = $cjkRatio
    }
}

function Is-TextLikeFile {
    param([string]$Extension)
    return @('.md', '.markdown', '.txt', '.java', '.kt', '.xml', '.json', '.yml', '.yaml', '.properties', '.sql', '.py', '.js', '.ts', '.tsx', '.jsx', '.html', '.css', '.csv') -contains $Extension.ToLowerInvariant()
}

$sourceFullPath = Resolve-FullPath -PathValue $SourceDir
if (-not (Test-Path -LiteralPath $sourceFullPath)) { throw "SourceDir not found: $SourceDir" }

$workspaceFullPath = Resolve-FullPath -PathValue $WorkspaceDir
$copiedDir = Join-Path $workspaceFullPath 'copied'
$normalizedDir = Join-Path $workspaceFullPath 'normalized'

New-Item -ItemType Directory -Force -Path $workspaceFullPath | Out-Null
if (Test-Path -LiteralPath $copiedDir) { Remove-Item -LiteralPath $copiedDir -Recurse -Force }
if (Test-Path -LiteralPath $normalizedDir) { Remove-Item -LiteralPath $normalizedDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $copiedDir | Out-Null
New-Item -ItemType Directory -Force -Path $normalizedDir | Out-Null

Write-Host 'Copying source tree to workspace...'
Get-ChildItem -LiteralPath $sourceFullPath -Force | ForEach-Object {
    Copy-Item -Path $_.FullName -Destination $copiedDir -Recurse -Force
}

$manifest = New-Object System.Collections.Generic.List[object]
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

Get-ChildItem -LiteralPath $copiedDir -Recurse -File | ForEach-Object {
    $relativePath = Get-RelativePathCompat -BasePath $copiedDir -TargetPath $_.FullName
    $normalizedPath = Join-Path $normalizedDir $relativePath
    $normalizedParent = Split-Path -Parent $normalizedPath
    if (-not (Test-Path -LiteralPath $normalizedParent)) { New-Item -ItemType Directory -Force -Path $normalizedParent | Out-Null }

    if (-not (Is-TextLikeFile -Extension $_.Extension)) {
        Copy-Item -LiteralPath $_.FullName -Destination $normalizedPath -Force
        $manifest.Add([pscustomobject]@{ relativePath = $relativePath; kind = 'binary'; source = $_.FullName; normalized = $normalizedPath; encoding = $null }) | Out-Null
        return
    }

    $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
    $decoded = Get-BestTextDecode -Bytes $bytes
    $normalizedText = Normalize-Text -Text $decoded.Text
    [System.IO.File]::WriteAllText($normalizedPath, $normalizedText, $utf8NoBom)
    $quality = Get-TextQuality -Text $normalizedText
    $manifest.Add([pscustomobject]@{
        relativePath = $relativePath
        kind = 'text'
        source = $_.FullName
        normalized = $normalizedPath
        encoding = $decoded.Encoding
        chars = $normalizedText.Length
        suspicious = $quality.suspicious
        suspiciousScore = $quality.score
        suspiciousReasons = @($quality.reasons)
        replacementCount = $quality.replacementCount
        mojibakeCount = $quality.mojibakeCount
        weirdPunctuationCount = $quality.weirdPunctuationCount
        latinRatio = $quality.latinRatio
        cjkRatio = $quality.cjkRatio
    }) | Out-Null
}

$manifestPath = Join-Path $workspaceFullPath 'manifest.json'
$summaryPath = Join-Path $workspaceFullPath 'summary.json'
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Path $manifestPath -Encoding UTF8

$textEntries = @($manifest | Where-Object { $_.kind -eq 'text' })
$suspiciousTextEntries = @($textEntries | Where-Object { $_.suspicious })
$summary = [pscustomobject]@{
    sourceDir = $sourceFullPath
    workspaceDir = $workspaceFullPath
    copiedDir = $copiedDir
    normalizedDir = $normalizedDir
    totalFiles = $manifest.Count
    textFiles = $textEntries.Count
    binaryFiles = @($manifest | Where-Object { $_.kind -eq 'binary' }).Count
    suspiciousTextFiles = $suspiciousTextEntries.Count
    cleanTextFiles = @($textEntries | Where-Object { -not $_.suspicious }).Count
    generatedAt = (Get-Date).ToString('s')
}
$summary | ConvertTo-Json -Depth 4 | Set-Content -Path $summaryPath -Encoding UTF8

Write-Host 'Workspace ready.'
Write-Host 'Copied:' $copiedDir
Write-Host 'Normalized:' $normalizedDir
Write-Host 'Manifest:' $manifestPath

