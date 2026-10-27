<#
  Runs one sprint coordinator session (headless Claude) over docs/SPRINT.md. Windows PowerShell 5.1.
  Started every hour and at logon by the Windows task MoireStudio-Sprint, and at a usage-limit reset by MoireStudio-Sprint-Resume.

  - Only one run at a time (tasks/sprint.lock holds the PID).
  - Runs only on the branch "sprint"; work left uncommitted in the repo is committed first as "WIP", so nothing can be lost.
  - When the Claude usage limit is reached, registers MoireStudio-Sprint-Resume a few minutes after the reset time it reports.
  - Each run's full transcript goes to tasks/sprint-runs/<time>.jsonl; one summary line per run to tasks/sprint-runs/runs.log.

  Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools\agent\sprint.ps1 [-Turns 60] [-DryRun]
#>
param([int]$Turns = 60, [switch]$DryRun)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Repo  = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Runs  = Join-Path $Repo 'tasks\sprint-runs'
$Lock  = Join-Path $Repo 'tasks\sprint.lock'
$Stamp = Get-Date -Format 'yyyy-MM-dd_HHmm'
New-Item -ItemType Directory -Force $Runs | Out-Null
Set-Location $Repo

function Log($msg) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm') $msg" | Add-Content (Join-Path $Runs 'runs.log') }

# one run at a time
if (Test-Path $Lock) {
    $old = (Get-Content $Lock -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($old -and (Get-Process -Id ([int]$old) -ErrorAction SilentlyContinue)) { Log "skipped: run $old still going"; exit 0 }
}
Set-Content $Lock $PID

try {
    $branch = (git branch --show-current).Trim()
    if ($branch -ne 'sprint') { Log "skipped: repo is on '$branch', not 'sprint'"; exit 0 }
    if (@(git status --porcelain).Count -gt 0) {
        git add -A | Out-Null
        git commit -q -m "WIP: uncommitted work found at the start of sprint run $Stamp" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" | Out-Null
        Log "committed uncommitted work as WIP before the run"
    }

    $exe = Get-ChildItem "$env:USERPROFILE\.vscode\extensions" -Recurse -Filter 'claude.exe' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -like '*native-binary*' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $exe) { Log 'failed: claude.exe not found'; exit 1 }

    Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

    $goal = "/goal Every item in docs/SPRINT.md whose status was todo, active or review and whose depends are done now has status done or needs-fix (blocked only when it needs one of D1 to D6 or a person, never because a command was refused), with docs and the checklist page updated for each done item; or $Turns turns have been used in this run. Show the changed queue lines as proof."
    $transcript = Join-Path $Runs "$Stamp.jsonl"
    $cliArgs = @('-p', $goal,
        '--append-system-prompt-file', 'tools/agent/SPRINT-PROMPT.md',
        '--settings', 'tools/agent/sprint-settings.json',
        '--permission-mode', 'dontAsk',
        '--output-format', 'stream-json', '--verbose')
    if ($DryRun) { $cliArgs[1] = 'Reply with exactly SPRINT-DRY-RUN-OK and do nothing else.' }

    Log "start $Stamp (claude $($exe.Directory.Parent.Parent.Name))"
    # written line by line with shared read access, so the transcript can be watched while the run goes on
    $fs = [System.IO.File]::Open($transcript, 'Create', 'Write', 'Read')
    $w = New-Object System.IO.StreamWriter($fs, (New-Object System.Text.UTF8Encoding($false)))
    try {
        $null | & $exe.FullName @cliArgs 2>> (Join-Path $Runs "$Stamp.err") | ForEach-Object { $w.WriteLine($_); $w.Flush() }
    } finally { $w.Close() }

    # the last "result" line says how the run ended
    $result = Get-Content $transcript -Encoding UTF8 | Where-Object { $_ -match '"type":"result"' } | Select-Object -Last 1
    $text = ''; $turnsUsed = '?'; $isError = $false
    if ($result) {
        $r = $result | ConvertFrom-Json
        $text = [string]$r.result; $turnsUsed = $r.num_turns; $isError = [bool]$r.is_error
    }
    Log ("end $Stamp turns=$turnsUsed error=$isError :: " + ($text -replace '\s+', ' ').Substring(0, [Math]::Min(240, $text.Length)))

    # usage limit: resume a few minutes after the reset the CLI reports (the hourly task is the fallback)
    if ($text -match '(?i)(usage limit|session limit|weekly limit|limit reached|hit your .*limit)') {
        $resume = $null
        if ($text -match '\|(\d{10})') { $resume = [DateTimeOffset]::FromUnixTimeSeconds([int64]$Matches[1]).LocalDateTime }
        elseif ($text -match '(?i)reset[s]?\s*(?:at\s*)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)') {
            $h = [int]$Matches[1]; $m = if ($Matches[2]) { [int]$Matches[2] } else { 0 }
            if ($Matches[3] -ieq 'pm' -and $h -lt 12) { $h += 12 }; if ($Matches[3] -ieq 'am' -and $h -eq 12) { $h = 0 }
            $resume = (Get-Date).Date.AddHours($h).AddMinutes($m)
            if ($resume -lt (Get-Date)) { $resume = $resume.AddDays(1) }
        }
        if ($resume) {
            $at = $resume.AddMinutes(3)
            $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$Repo\tools\agent\sprint.ps1`"" -WorkingDirectory $Repo
            Register-ScheduledTask -TaskName 'MoireStudio-Sprint-Resume' -Action $action -Trigger (New-ScheduledTaskTrigger -Once -At $at) `
                -Settings (New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 8)) -Force | Out-Null
            Log "usage limit reached: resume registered for $($at.ToString('yyyy-MM-dd HH:mm'))"
        } else {
            Log 'usage limit reached: reset time not found, the hourly task will retry'
        }
    }
} finally {
    Remove-Item $Lock -ErrorAction SilentlyContinue
}
