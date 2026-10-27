<#
  Nightly check for Moire Studio. Windows PowerShell 5.1.
  Builds both flavours and runs the studio unit tests in the working copy, then writes docs/DIGEST.md.
  If something fails it asks a read-only Claude run to name the likely cause (needs the claude CLI; skipped if not found).

  Safety: never commits, pushes, publishes, touches a phone or edits source. It writes only docs/DIGEST.md and tasks/nightly.log.
  Usage: powershell -NoProfile -File tools\agent\nightly.ps1 [-NoClaude]
#>
param([switch]$NoClaude)

$ErrorActionPreference = 'Continue'
$Repo   = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Digest = Join-Path $Repo 'docs\DIGEST.md'
$Log    = Join-Path $Repo 'tasks\nightly.log'
$Gradle = Join-Path $Repo 'gradlew.bat'
Set-Location $Repo
Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue

function Stamp { Get-Date -Format 'yyyy-MM-dd HH:mm' }

$started = Stamp
$free = [math]::Round((Get-PSDrive C).Free / 1GB, 1)
$lines = New-Object System.Collections.Generic.List[string]
if ($free -lt 3) {
    "$started stopped: only $free GB free on C:" | Add-Content $Log
    exit 1
}

# 1. build and test (tasks run one after another so a failure is easy to place)
$steps = @(
    @{ Name = 'Studio unit tests';   Args = @('testStudioDebugUnitTest') },
    @{ Name = 'Studio debug build';  Args = @('assembleStudioDebug') }
)
$failed = @()
$tail = ''
# a sprint run builds in the same folder: leave the builds to it (its own runs test everything after each merge)
$sprintLock = Join-Path $Repo 'tasks\sprint.lock'
$sprintPid = if (Test-Path $sprintLock) { Get-Content $sprintLock | Select-Object -First 1 } else { $null }
if ($sprintPid -and (Get-Process -Id ([int]$sprintPid) -ErrorAction SilentlyContinue)) {
    $steps = @()
    $lines.Add("- Builds skipped: a sprint run (PID $sprintPid) is working in the repo and tests after each merge")
}
foreach ($s in $steps) {
    $out = & $Gradle @($s.Args) '--console=plain' '-q' 2>&1 | Out-String
    $ok = ($LASTEXITCODE -eq 0)
    $lines.Add("- $($s.Name): " + $(if ($ok) { 'passed' } else { 'FAILED' }))
    if (-not $ok) { $failed += $s.Name; $tail += "`n--- $($s.Name) ---`n" + ($out -split "`n" | Select-Object -Last 40) -join "`n" }
}

# 2. test count from the XML reports
$tests = 0; $fails = 0
Get-ChildItem (Join-Path $Repo 'app\build\test-results\testStudioDebugUnitTest') -Recurse -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
    $m = Select-String -Path $_.FullName -Pattern '<testsuite .*?tests="(\d+)".*?failures="(\d+)"' | Select-Object -First 1
    if ($m) { $tests += [int]$m.Matches[0].Groups[1].Value; $fails += [int]$m.Matches[0].Groups[2].Value }
}
$lines.Add("- Tests in the reports: $tests, failures: $fails")

# 3. task cards waiting
$todo = @(Get-ChildItem (Join-Path $Repo 'tasks') -Filter 'T-*.md' | Where-Object { (Get-Content $_.FullName -Raw) -match '(?m)^status:\s*todo' } | ForEach-Object { $_.BaseName })
$lines.Add("- Local-model cards marked todo: " + $(if ($todo.Count) { $todo -join ', ' } else { 'none' }))

# 4. uncommitted size, so the digest shows how much is not in git yet
$dirty = @(git status --porcelain 2>$null).Count
$lines.Add("- Files changed and not committed: $dirty")

# 5. on failure, a read-only diagnosis
$diagnosis = ''
if ($failed.Count -gt 0 -and -not $NoClaude) {
    $exe = Get-ChildItem "$env:USERPROFILE\.vscode\extensions" -Recurse -Filter 'claude.exe' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($exe) {
        $prompt = "These Gradle steps failed in the Moire Studio repo: $($failed -join ', '). Output tail:`n$tail`nName the most likely cause and the file and line to look at. Read files only; change nothing. Five lines at most."
        $diagnosis = & $exe.FullName -p $prompt --permission-mode dontAsk --allowedTools 'Read,Grep,Glob' --max-budget-usd 1 2>&1 | Out-String
    } else { $diagnosis = '(claude CLI not found, so no diagnosis)' }
}

# 6. the day's feedback from testers, pulled from the collector on the Pi (read-only; skipped quietly when the Pi cannot be reached)
$fbDigest = Join-Path $Repo 'docs\FEEDBACK-DIGEST.md'
# the catalog goes first: the digest's "Never used" list and the evidence in step 7 both read it
$cat = Join-Path $Repo 'app\src\main\assets\usecases.json'
& scp -q -o BatchMode=yes -o ConnectTimeout=15 $cat 'deb-server-1@deb-server-1:MoireFeedback/usecases.json' 2>$null
$fb =& ssh -o BatchMode=yes -o ConnectTimeout=15 deb-server-1@deb-server-1 'python3 ~/MoireFeedback/server.py digest --hours 24' 2>$null | Out-String
if ($LASTEXITCODE -eq 0 -and $fb.Trim()) {
    Set-Content -Path $fbDigest -Value $fb -Encoding UTF8
    $lines.Add("- Tester feedback: pulled into docs/FEEDBACK-DIGEST.md")
} else {
    $lines.Add("- Tester feedback: the collector could not be reached")
}

# 7. which checklist tests were carried out in daily use (catalog -> Pi -> evidence JSON; applied to the checklist page by a Claude session)
$ev =& ssh -o BatchMode=yes -o ConnectTimeout=15 deb-server-1@deb-server-1 'python3 ~/MoireFeedback/server.py evidence --days 30' 2>$null | Out-String
if ($LASTEXITCODE -eq 0 -and $ev.Trim().StartsWith('{')) {
    Set-Content -Path (Join-Path $Repo 'docs\AUTO-EVIDENCE.json') -Value $ev -Encoding UTF8
    $j = $ev | ConvertFrom-Json
    $seen = @($j.tests.PSObject.Properties | Where-Object { $_.Value.state -eq 'seen' }).Count
    $touched = @($j.tests.PSObject.Properties | Where-Object { $_.Value.state -eq 'touched' }).Count
    $lines.Add("- Checklist tests done in daily use: $seen seen, $touched area-used (docs/AUTO-EVIDENCE.json)")
}

$body = "# Nightly digest`n`nRun started $started, finished $(Stamp). Free space on C: $free GB.`n`n" + ($lines -join "`n") + "`n"
if ($diagnosis) { $body += "`n## Likely cause of the failure`n`n$diagnosis`n" }
if ($failed.Count -gt 0) { $body += "`n## Output tail`n`n" + '```' + "`n$tail`n" + '```' + "`n" }
$body += "`nThis file is overwritten every night. Nothing was committed, pushed, published or run on a phone.`n"
Set-Content -Path $Digest -Value $body -Encoding UTF8
"$started done, failed: " + $(if ($failed.Count) { $failed -join ', ' } else { 'none' }) | Add-Content $Log
