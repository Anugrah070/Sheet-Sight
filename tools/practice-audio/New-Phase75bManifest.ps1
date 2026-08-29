param(
    [ValidateRange(3, 5)]
    [int]$TakesPerCase = 3,

    [string]$OutputPath = (Join-Path $PSScriptRoot 'local-recordings\manifest.tsv')
)

$templatePath = Join-Path $PSScriptRoot 'dataset-template.tsv'
$templateLines = Get-Content -Encoding utf8 -LiteralPath $templatePath
if ($templateLines.Count -lt 2) {
    throw "Capture template has no data rows: $templatePath"
}

$header = $templateLines[0].Split("`t")
$idIndex = [Array]::IndexOf($header, 'id')
$wavIndex = [Array]::IndexOf($header, 'wav')
$placementIndex = [Array]::IndexOf($header, 'phone_placement')
if ($idIndex -lt 0 -or $wavIndex -lt 0 -or $placementIndex -lt 0) {
    throw 'Capture template is missing id, wav, or phone_placement.'
}

$placements = @('NORMAL', 'NEAR_KEYS', 'OPEN_LID', 'MUSIC_STAND', 'ROOM_DISTANCE')
$output = [System.Collections.Generic.List[string]]::new()
$output.Add($templateLines[0])

foreach ($line in $templateLines[1..($templateLines.Count - 1)]) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $cells = [System.Collections.Generic.List[string]]::new()
    foreach ($cell in $line.Split("`t")) { $cells.Add($cell) }
    while ($cells.Count -lt $header.Count) { $cells.Add('') }

    $baseId = $cells[$idIndex] -replace '-01$', ''
    for ($take = 1; $take -le $TakesPerCase; $take++) {
        $copy = $cells.ToArray()
        $takeSuffix = $take.ToString('00')
        $copy[$idIndex] = "$baseId-$takeSuffix"
        $copy[$wavIndex] = "local-recordings/$baseId-$takeSuffix.wav"
        $copy[$placementIndex] = $placements[$take - 1]
        $output.Add(($copy -join "`t"))
    }
}

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
$recordingRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'local-recordings'))
if (-not $resolvedOutput.StartsWith($recordingRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Output must remain under $recordingRoot"
}

$outputDirectory = Split-Path -Parent $resolvedOutput
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
[System.IO.File]::WriteAllLines($resolvedOutput, $output, [System.Text.UTF8Encoding]::new($false))
Write-Output "Created $resolvedOutput with $($output.Count - 1) unverified capture rows."
Write-Output 'Every row must be manually reviewed and completed before benchmark replay.'
