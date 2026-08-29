$ErrorActionPreference = 'Stop'

$metricsRoot = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'Timeline Visualizer Metrics'
$collector = Join-Path $PSScriptRoot 'daily_metrics.py'
$logDirectory = Join-Path $metricsRoot 'logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$stamp = Get-Date -Format 'yyyy-MM-dd'
$logPath = Join-Path $logDirectory "$stamp-collector.log"

$output = & py -3 $collector --root $metricsRoot collect 2>&1
$exitCode = $LASTEXITCODE
$logText = ($output | Out-String).TrimEnd() + [Environment]::NewLine
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::AppendAllText($logPath, $logText, $utf8)
exit $exitCode
