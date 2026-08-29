[CmdletBinding()]
param(
    [string]$TaskName = 'Timeline Visualizer Daily Metrics'
)

$ErrorActionPreference = 'Stop'
$installDirectory = Join-Path $env:LOCALAPPDATA 'Timeline Visualizer Metrics\bin'
New-Item -ItemType Directory -Path $installDirectory -Force | Out-Null

Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'daily_metrics.py') -Destination $installDirectory -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'run_daily_metrics.ps1') -Destination $installDirectory -Force

$runner = Join-Path $installDirectory 'run_daily_metrics.ps1'
$action = New-ScheduledTaskAction `
    -Execute 'powershell.exe' `
    -Argument "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$runner`""
$trigger = New-ScheduledTaskTrigger -Daily -At '09:00'
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 15)
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Principal $principal `
    -Description 'Collect aggregate Timeline Visualizer web and release metrics at 09:00 KST.' `
    -Force | Out-Null

Get-ScheduledTask -TaskName $TaskName | Select-Object TaskName, State
