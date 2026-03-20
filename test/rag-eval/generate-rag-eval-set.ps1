param(
    [string]$NormalizedDir = ".\test\rag-eval\workspace\normalized",
    [string]$OutputDir = ".\test\rag-eval\output",
    [int]$MaxDocuments = 120,
    [int]$MaxSectionSamplesPerDocument = 2
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-FullPath {
    param([string]$PathValue)
    $resolved = Resolve-Path -LiteralPath $PathValue -ErrorAction SilentlyContinue
    if ($resolved) { return $resolved.Path }
    return [System.IO.Path]::GetFullPath($PathValue)
}

function Get-RelativePathCompat {
    param([string]$BasePath, [string]$TargetPath)
    $base = (Resolve-FullPath -PathValue $BasePath).TrimEnd([char[]]@('\', '/'))
    $target = Resolve-FullPath -PathValue $TargetPath
    if ($target.StartsWith($base, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $target.Substring($base.Length).TrimStart([char[]]@('\', '/'))
    }
    return Split-Path -Leaf $target
}

function Normalize-Whitespace {
    param([string]$Text)
    $normalized = $Text -replace "`r`n", "`n"
    $normalized = $normalized -replace "`r", "`n"
    $normalized = $normalized -replace "[ \t]+", " "
    $normalized = $normalized -replace "\n{3,}", "`n`n"
    return $normalized.Trim()
}

function Get-TextQuality {
    param([string]$Text)
    $length = if ($null -eq $Text) { 0 } else { $Text.Length }
    if ($length -eq 0) {
        return [pscustomobject]@{ suspicious = $true; score = 1.0; reasons = @('empty') }
    }

    $replacementCount = ([regex]::Matches($Text, [string][char]0xFFFD)).Count
    $mojibakeCount = ([regex]::Matches($Text, '[\x00-\x08\x0B\x0C\x0E-\x1F]|[ÃÆÐÑØÞßàáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿ]{2,}')).Count
    $weirdPunctuationCount = ([regex]::Matches($Text, '鈥|銆|锛|锟|�')).Count
    $latinCount = ([regex]::Matches($Text, '[A-Za-z]')).Count
    $hanCount = ([regex]::Matches($Text, '\p{IsCJKUnifiedIdeographs}')).Count

    $latinRatio = $latinCount / [double]$length
    $cjkRatio = $hanCount / [double]$length
    $replacementRatio = $replacementCount / [double]$length
    $mojibakeRatio = $mojibakeCount / [double]$length
    $weirdPunctuationRatio = $weirdPunctuationCount / [double]$length

    $reasons = New-Object System.Collections.Generic.List[string]
    if ($replacementRatio -gt 0.001) { $reasons.Add('replacement-char') | Out-Null }
    if ($mojibakeRatio -gt 0.008) { $reasons.Add('mojibake-pattern') | Out-Null }
    if ($weirdPunctuationRatio -gt 0.01) { $reasons.Add('weird-punctuation') | Out-Null }
    if ($hanCount -gt 20 -and $latinRatio -gt 0.45 -and $cjkRatio -lt 0.1) { $reasons.Add('cjk-decoding-mismatch') | Out-Null }

    $score = [Math]::Min(1.0, ($replacementRatio * 8.0) + ($mojibakeRatio * 12.0) + ($weirdPunctuationRatio * 6.0) + ($(if ($reasons.Count -gt 0) { 0.2 } else { 0.0 })))
    return [pscustomobject]@{ suspicious = ($reasons.Count -gt 0); score = [Math]::Round($score, 4); reasons = @($reasons) }
}

function Clip-Text {
    param([string]$Text, [int]$MaxLength = 320)
    $clean = Normalize-Whitespace -Text $Text
    if ($clean.Length -le $MaxLength) { return $clean }
    return $clean.Substring(0, $MaxLength) + '...'
}

function Get-FirstParagraph {
    param([string]$Text)
    foreach ($part in ([regex]::Split((Normalize-Whitespace -Text $Text), "\n\s*\n"))) {
        $value = $part.Trim()
        if ($value.Length -ge 40) { return $value }
    }
    return ''
}

function Parse-MarkdownSections {
    param([string]$Content)
    $sections = New-Object System.Collections.Generic.List[object]
    $currentTitle = ''
    $currentLines = New-Object System.Collections.Generic.List[string]
    $lines = (Normalize-Whitespace -Text $Content) -split "`n"

    foreach ($line in $lines) {
        if ($line -match '^(#{1,6})\s+(.+?)\s*$') {
            if ($currentLines.Count -gt 0 -or -not [string]::IsNullOrWhiteSpace($currentTitle)) {
                $sections.Add([pscustomobject]@{ title = $currentTitle; content = ($currentLines -join "`n").Trim() }) | Out-Null
            }
            $currentTitle = $matches[2].Trim()
            $currentLines = New-Object System.Collections.Generic.List[string]
            continue
        }
        $currentLines.Add($line) | Out-Null
    }

    if ($currentLines.Count -gt 0 -or -not [string]::IsNullOrWhiteSpace($currentTitle)) {
        $sections.Add([pscustomobject]@{ title = $currentTitle; content = ($currentLines -join "`n").Trim() }) | Out-Null
    }
    return $sections
}

function Is-CleanSampleText {
    param([string]$Text, [int]$MinLength = 1)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $false }
    $normalized = Normalize-Whitespace -Text $Text
    if ($normalized.Length -lt $MinLength) { return $false }
    $garblePattern = '聽|€||銆|锛|鈥|绗|涓€|鍚|鏂|杩|鐨|缁|缂|璇|浠|鍒|鎬|闂|鍥|鍦'
    $garbleCount = ([regex]::Matches($normalized, $garblePattern)).Count
    if ($garbleCount -ge 3 -and ($garbleCount / [double][Math]::Max(1, $normalized.Length)) -gt 0.01) { return $false }
    return -not (Get-TextQuality -Text $normalized).suspicious
}

$normalizedFullPath = Resolve-FullPath -PathValue $NormalizedDir
if (-not (Test-Path -LiteralPath $normalizedFullPath)) { throw "NormalizedDir not found: $NormalizedDir" }

$outputFullPath = Resolve-FullPath -PathValue $OutputDir
New-Item -ItemType Directory -Force -Path $outputFullPath | Out-Null
$jsonlPath = Join-Path $outputFullPath 'rag-eval.jsonl'
$summaryPath = Join-Path $outputFullPath 'rag-eval-summary.json'
if (Test-Path -LiteralPath $jsonlPath) { Remove-Item -LiteralPath $jsonlPath -Force }

$manifestPath = Join-Path (Split-Path -Parent $normalizedFullPath) 'manifest.json'
$suspiciousMap = @{}
if (Test-Path -LiteralPath $manifestPath) {
    $manifestEntries = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($entry in $manifestEntries) {
        if ($entry.kind -eq 'text') {
            $suspiciousMap[$entry.relativePath] = [bool]$entry.suspicious
        }
    }
}

$samples = New-Object System.Collections.Generic.List[object]
$documents = Get-ChildItem -LiteralPath $normalizedFullPath -Recurse -File -Filter *.md | Sort-Object FullName
$docCounter = 0
$skippedSuspiciousFiles = 0
$skippedDirtySamples = 0

foreach ($file in $documents) {
    if ($docCounter -ge $MaxDocuments) { break }

    $relativePath = Get-RelativePathCompat -BasePath $normalizedFullPath -TargetPath $file.FullName
    if ($suspiciousMap.ContainsKey($relativePath) -and $suspiciousMap[$relativePath]) {
        $skippedSuspiciousFiles++
        continue
    }

    try {
        $content = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)
    } catch {
        $skippedSuspiciousFiles++
        continue
    }
    $cleanContent = Normalize-Whitespace -Text $content
    if ($cleanContent.Length -lt 120) { continue }
    $fileGarbledCount = ([regex]::Matches($cleanContent.Substring(0, [Math]::Min(1200, $cleanContent.Length)), '聽|€||銆|锛|鈥|绗|涓€|鍚|鏂|杩|鐨|缁|缂|璇|浠|鍒|鎬|闂|鍥|鍦')).Count
    if ((Get-TextQuality -Text $cleanContent).suspicious -or $fileGarbledCount -ge 12) {
        $skippedSuspiciousFiles++
        continue
    }

    $title = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)
    if ($cleanContent -match '(?m)^#\s+(.+?)\s*$') { $title = $matches[1].Trim() }
    if (-not (Is-CleanSampleText -Text $title -MinLength 2)) {
        $skippedDirtySamples++
        continue
    }

    $docCounter++
    $firstParagraph = Get-FirstParagraph -Text $cleanContent
    if (Is-CleanSampleText -Text $firstParagraph -MinLength 40) {
        $docQuestion = ('请总结《{0}》这份文件的核心内容。' -f $title)
        $fileQuestion = ('根据文件名《{0}》，总结这整个文件。' -f $title)
        $docAnswer = Clip-Text -Text $firstParagraph -MaxLength 360

        if ((Is-CleanSampleText -Text $docQuestion -MinLength 8) -and (Is-CleanSampleText -Text $docAnswer -MinLength 40)) {
            $samples.Add([pscustomobject]@{ id = "doc-summary-$docCounter"; type = 'document_summary'; relativePath = $relativePath; title = $title; question = $docQuestion; expectedAnswer = $docAnswer; expectedSections = @() }) | Out-Null
        } else {
            $skippedDirtySamples++
        }

        if ((Is-CleanSampleText -Text $fileQuestion -MinLength 8) -and (Is-CleanSampleText -Text $docAnswer -MinLength 40)) {
            $samples.Add([pscustomobject]@{ id = "file-name-summary-$docCounter"; type = 'file_name_summary'; relativePath = $relativePath; title = $title; question = $fileQuestion; expectedAnswer = $docAnswer; expectedSections = @() }) | Out-Null
        } else {
            $skippedDirtySamples++
        }
    }

    $sectionCount = 0
    foreach ($section in (Parse-MarkdownSections -Content $cleanContent)) {
        if ($sectionCount -ge $MaxSectionSamplesPerDocument) { break }
        $sectionTitle = $section.title
        $sectionContent = Normalize-Whitespace -Text $section.content
        if (-not (Is-CleanSampleText -Text $sectionTitle -MinLength 2)) { continue }
        if (-not (Is-CleanSampleText -Text $sectionContent -MinLength 80)) { continue }

        $question = ('《{0}》中“{1}”这一节主要讲了什么？' -f $title, $sectionTitle)
        $answer = Clip-Text -Text $sectionContent -MaxLength 320
        if ((Is-CleanSampleText -Text $question -MinLength 8) -and (Is-CleanSampleText -Text $answer -MinLength 40)) {
            $sectionCount++
            $samples.Add([pscustomobject]@{ id = "section-summary-$docCounter-$sectionCount"; type = 'section_summary'; relativePath = $relativePath; title = $title; question = $question; expectedAnswer = $answer; expectedSections = @($sectionTitle) }) | Out-Null
        } else {
            $skippedDirtySamples++
        }
    }
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$stream = New-Object System.IO.StreamWriter($jsonlPath, $false, $utf8NoBom)
try {
    foreach ($sample in $samples) {
        $stream.WriteLine(($sample | ConvertTo-Json -Depth 5 -Compress))
    }
} finally {
    $stream.Dispose()
}

$typeStats = @{}
foreach ($sample in $samples) {
    if (-not $typeStats.ContainsKey($sample.type)) { $typeStats[$sample.type] = 0 }
    $typeStats[$sample.type]++
}

$summary = [pscustomobject]@{
    normalizedDir = $normalizedFullPath
    outputDir = $outputFullPath
    documentCount = $docCounter
    sampleCount = $samples.Count
    sampleTypes = $typeStats
    skippedSuspiciousFiles = $skippedSuspiciousFiles
    skippedDirtySamples = $skippedDirtySamples
    generatedAt = (Get-Date).ToString('s')
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -Path $summaryPath -Encoding UTF8

Write-Host 'Generated:' $jsonlPath
Write-Host 'Summary:' $summaryPath




