<#
  Runs task cards (tasks/*.md) with executor "local" through the local LM Studio model.
  See docs/AGENT-WORKFLOW.md. Windows PowerShell 5.1 compatible.

  Usage:
    powershell -File tools\agent\run-local.ps1                 # all runnable local cards
    powershell -File tools\agent\run-local.ps1 -Id T-001       # one card
    powershell -File tools\agent\run-local.ps1 -DryRun         # show the prompt, call nothing
    powershell -File tools\agent\run-local.ps1 -NoGradle       # write the file, skip the test run

  Safety: works only in a separate git worktree (../moire-studio-agent) on branch agent/<id>,
  writes only the card's files_out, never touches the main working copy except the card's
  status line and tasks/results/. No pushes, no commits to main.
#>
param(
    [string]$Id = '',            # one card, or several separated by commas (run in that order)
    [string]$WorkDir = '',       # another worktree, so two runners can work at once
    [int]$MaxCards = 99,
    [switch]$DryRun,
    [switch]$NoGradle
)

$ErrorActionPreference = 'Stop'
$Repo      = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$TasksDir  = Join-Path $Repo 'tasks'
$ResultDir = Join-Path $TasksDir 'results'
$Work      = if ($WorkDir) { $WorkDir } else { Join-Path (Split-Path $Repo -Parent) 'moire-studio-agent' }
$Api       = 'http://localhost:1234'
$ModelId   = 'coder'
$LmsExe    = Join-Path $env:USERPROFILE '.lmstudio\bin\lms.exe'
$LmStudio  = Join-Path $env:LOCALAPPDATA 'Programs\LM Studio\LM Studio.exe'
$MinFreeGB = 3
$GradleTimeoutSec = 1800

# VS Code sets this and it makes Electron apps (LM Studio) exit at once.
Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue

$SystemPrompt = @'
You are a coding assistant operating in one of two modes. Detect the mode from the START of each user message:

MODE_AGENT - Message begins with <<<AGENT>>>.
  Caller: an orchestrating program. Respond with:
  - NO preamble, NO explanations.
  - The complete file in ONE fenced code block with a language tag.
  - After the block, a single line prefixed `# SUMMARY:`.
  - For error feedback: output the corrected COMPLETE file in one fenced block, then `# CHANGES:` with a short bullet list.

MODE_WEBUI - otherwise. Friendly tone, explain reasoning.
Never mix modes within a single response.
'@

function Log([string]$m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

# Runs a native program, returns @{ Code; Out }. Avoids PowerShell 5.1 stderr-as-error problems.
function Exec([string]$File, [string[]]$Arguments, [string]$Cwd, [int]$TimeoutSec = 120) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $File
    $psi.Arguments = ($Arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' '
    $psi.WorkingDirectory = $Cwd
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $psi
    $sb = New-Object System.Text.StringBuilder
    $h = { if ($EventArgs.Data -ne $null) { [void]$Event.MessageData.AppendLine($EventArgs.Data) } }
    $e1 = Register-ObjectEvent -InputObject $p -EventName OutputDataReceived -Action $h -MessageData $sb
    $e2 = Register-ObjectEvent -InputObject $p -EventName ErrorDataReceived  -Action $h -MessageData $sb
    [void]$p.Start()
    $p.BeginOutputReadLine(); $p.BeginErrorReadLine()
    $done = $p.WaitForExit($TimeoutSec * 1000)
    if (-not $done) { try { & taskkill /PID $p.Id /T /F | Out-Null } catch {} }
    else { $p.WaitForExit() }
    Start-Sleep -Milliseconds 200
    Unregister-Event -SourceIdentifier $e1.Name; Unregister-Event -SourceIdentifier $e2.Name
    $code = if ($done) { $p.ExitCode } else { -999 }
    return @{ Code = $code; Out = $sb.ToString(); TimedOut = (-not $done) }
}

function Git([string]$Cwd, [string[]]$GitArgs) { Exec 'git' $GitArgs $Cwd 120 }

# ---- cards ----------------------------------------------------------------------------------
function Read-Card([string]$Path) {
    $raw = [IO.File]::ReadAllText($Path)
    $m = [regex]::Match($raw, '(?s)^---\r?\n(.*?)\r?\n---\r?\n(.*)$')
    if (-not $m.Success) { throw "Card $Path has no frontmatter" }
    $card = @{ Path = $Path; Body = $m.Groups[2].Value }
    foreach ($line in ($m.Groups[1].Value -split "\r?\n")) {
        if ($line -match '^\s*([A-Za-z_]+)\s*:\s*(.*?)\s*$') {
            $k = $Matches[1]; $v = ($Matches[2] -replace '\s+#.*$', '').Trim()
            if ($v -match '^\[(.*)\]$') {
                $items = @(); foreach ($x in ($Matches[1] -split ',')) { if ($x.Trim()) { $items += $x.Trim() } }
                $card[$k] = $items
            } else { $card[$k] = $v }
        }
    }
    foreach ($req in 'id', 'executor', 'status', 'files_out', 'test_filter') {
        if (-not $card.ContainsKey($req)) { throw "Card $Path is missing '$req'" }
    }
    return $card
}

function Set-CardField([string]$Path, [string]$Key, [string]$Value) {
    $raw = [IO.File]::ReadAllText($Path)
    $new = [regex]::Replace($raw, "(?m)^($Key\s*:\s*)[^#\r\n]*", { param($m) $m.Groups[1].Value + $Value + ' ' }, 1)
    [IO.File]::WriteAllText($Path, $new, (New-Object Text.UTF8Encoding($false)))
}

function All-Cards { Get-ChildItem $TasksDir -Filter 'T-*.md' | Sort-Object Name | ForEach-Object { Read-Card $_.FullName } }

function Pick-Cards {
    $cards = @(All-Cards)
    $doneIds = @($cards | Where-Object { $_.status -eq 'done' } | ForEach-Object { $_.id })
    $list = @($cards | Where-Object { $_.executor -eq 'local' -and $_.status -eq 'todo' } | Where-Object {
        $deps = @(); if ($_.ContainsKey('depends')) { $deps = @($_.depends) }
        -not ($deps | Where-Object { $_ -and ($doneIds -notcontains $_) })
    } | Sort-Object { [int]$_.wave }, { $_.id })
    if ($Id) { $list = @($Id -split ',' | ForEach-Object { $want = $_.Trim(); $cards | Where-Object { $_.id -eq $want } }) }
    return $list
}

# ---- LM Studio ------------------------------------------------------------------------------
function Test-Server { try { $r = Invoke-RestMethod "$Api/v1/models" -TimeoutSec 4; return (@($r.data | Where-Object { $_.id -eq $ModelId }).Count -gt 0) } catch { return $false } }

function Ensure-Server {
    if (Test-Server) { return }
    Log 'Model server not answering, starting LM Studio...'
    if (-not (Get-Process -Name 'LM Studio' -ErrorAction SilentlyContinue)) { Start-Process $LmStudio; Start-Sleep 30 }
    [void](Exec $LmsExe @('server', 'start') $Repo 120)
    [void](Exec $LmsExe @('load', 'qwen/qwen3-coder-30b', '--gpu', '0.3', '-c', '16384', '--identifier', $ModelId, '-y') $Repo 400)
    if (-not (Test-Server)) { throw 'Could not get the local model running.' }
}

function Ask-Model($Messages) {
    $body = @{ model = $ModelId; temperature = 0.2; max_tokens = 3500; messages = $Messages } | ConvertTo-Json -Depth 6
    $bytes = [Text.Encoding]::UTF8.GetBytes($body)
    $r = Invoke-RestMethod "$Api/v1/chat/completions" -Method Post -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 900
    return $r.choices[0].message.content
}

function Get-CodeBlock([string]$Text) {
    $m = [regex]::Match($Text, '(?s)```[A-Za-z]*\r?\n(.*?)```')
    if ($m.Success) { return $m.Groups[1].Value.TrimEnd() + "`n" }
    return $null
}

# ---- worktree -------------------------------------------------------------------------------
function Ensure-Worktree {
    if (-not (Test-Path $Work)) {
        Log "Creating worktree $Work"
        $r = Git $Repo @('worktree', 'add', '--detach', $Work, 'HEAD'); if ($r.Code -ne 0) { throw "worktree add failed: $($r.Out)" }
    }
    $lp = Join-Path $Repo 'local.properties'
    if (Test-Path $lp) { Copy-Item $lp (Join-Path $Work 'local.properties') -Force }
}

function Safe-Path([string]$Rel) {
    if ([IO.Path]::IsPathRooted($Rel) -or $Rel -match '\.\.' -or $Rel -notmatch '^app/src/(main|test)/java/') { throw "Refusing path '$Rel'" }
    return $Rel
}

# ---- one card -------------------------------------------------------------------------------
function Run-Card($card) {
    $cid = $card.id
    # Another runner may have taken it since the list was made.
    $now = (Read-Card $card.Path).status
    if ($now -notin @('todo', 'failed')) { Log "$cid is $now, skipping"; return 'skipped' }
    Log "=== $cid ==="
    $outs = @($card.files_out | ForEach-Object { Safe-Path $_ })
    if ($outs.Count -ne 1) { throw "${cid}: runner v1 supports exactly one file in files_out" }
    $implRel = $outs[0]
    $maxRetries = 2; if ($card.ContainsKey('max_retries')) { $maxRetries = [int]$card.max_retries }

    # context: provided tests, extra files, existing implementation
    $ctx = New-Object System.Text.StringBuilder
    $testDest = $null; $testSrc = $null
    if ($card.ContainsKey('test_src')) {
        $testSrc = Join-Path $Repo $card.test_src
        $testDest = Safe-Path $card.test_dest
        [void]$ctx.AppendLine("The unit test that your code must pass (do not change it; match its API exactly):`n``````kotlin`n$([IO.File]::ReadAllText($testSrc))`n``````")
    }
    if ($card.ContainsKey('files_in')) {
        foreach ($f in @($card.files_in)) {
            $p = Join-Path $Repo $f
            if (Test-Path $p) { [void]$ctx.AppendLine("Context file $f`:`n``````kotlin`n$([IO.File]::ReadAllText($p))`n``````") }
        }
    }
    $userMsg = "<<<AGENT>>> Write the complete Kotlin file ``$implRel``. Include the package line.`n`n$($card.Body)`n`n$($ctx.ToString())"

    $messages = @(@{ role = 'system'; content = $SystemPrompt }, @{ role = 'user'; content = $userMsg })
    if ($DryRun) { Write-Host $userMsg; return 'dry-run' }

    Ensure-Worktree
    $r = Git $Work @('checkout', '-f', '--detach', 'HEAD');
    $r = Git $Work @('checkout', '-B', "agent/$cid", 'HEAD'); if ($r.Code -ne 0) { throw "checkout failed: $($r.Out)" }
    $r = Git $Work @('clean', '-fdq', '--', 'app/src')

    Set-CardField $card.Path 'status' 'running'
    $log = New-Object System.Text.StringBuilder
    [void]$log.AppendLine("# Result $cid`n`nStarted $(Get-Date -Format s)`n`n## Prompt`n``````n$userMsg`n```````n")
    $verdict = 'failed'; $attempt = 0
    try {
        while ($attempt -le $maxRetries) {
            $attempt++
            Log "${cid} attempt ${attempt}: asking the model..."
            $t0 = Get-Date
            $reply = Ask-Model $messages
            Log ("$cid reply in {0:N0}s, {1} chars" -f ((Get-Date) - $t0).TotalSeconds, $reply.Length)
            [void]$log.AppendLine("## Attempt $attempt reply`n``````n$reply`n```````n")
            $code = Get-CodeBlock $reply
            if (-not $code) {
                $messages += @{ role = 'assistant'; content = $reply }
                $messages += @{ role = 'user'; content = '<<<AGENT>>> REMINDER: output ONLY the complete file in one fenced code block plus the # SUMMARY line.' }
                continue
            }
            $utf8 = New-Object Text.UTF8Encoding($false)
            $implAbs = Join-Path $Work $implRel
            New-Item -ItemType Directory -Force (Split-Path $implAbs) | Out-Null
            [IO.File]::WriteAllText($implAbs, $code, $utf8)
            if ($testSrc) {
                $td = Join-Path $Work $testDest
                New-Item -ItemType Directory -Force (Split-Path $td) | Out-Null
                Copy-Item $testSrc $td -Force
            }
            if ($NoGradle) { $verdict = 'needs-review'; [void]$log.AppendLine('(gradle skipped)'); break }

            Log "$cid running tests (Gradle)..."
            $g = Exec (Join-Path $Work 'gradlew.bat') @('testDebugUnitTest', '--tests', $card.test_filter, '--console=plain', '-q') $Work $GradleTimeoutSec
            $tail = (($g.Out -split "\r?\n") | Where-Object { $_ -match '^e: |FAILED|AssertionError|Exception|expected|error:|What went wrong' } | Select-Object -First 40) -join "`n"
            [void]$log.AppendLine("## Attempt $attempt gradle exit $($g.Code)`n``````n$tail`n```````n")
            if ($g.Code -eq 0) { $verdict = 'needs-review'; break }
            if ($g.TimedOut) { $verdict = 'failed'; [void]$log.AppendLine('Gradle timed out.'); break }
            if (-not $tail) { $tail = (($g.Out -split "\r?\n") | Select-Object -Last 40) -join "`n" }
            # show the model the exact source lines the compiler complained about
            $srcLines = $code -split "
?
"; $shown = @{}; $ctxLines = New-Object System.Text.StringBuilder
            foreach ($mm in [regex]::Matches($g.Out, '[A-Za-z0-9_]+\.kt:(\d+):\d+')) {
                $ln = [int]$mm.Groups[1].Value
                if ($ln -ge 1 -and $ln -le $srcLines.Count -and -not $shown.ContainsKey($ln)) { $shown[$ln] = 1; [void]$ctxLines.AppendLine("line ${ln}: $($srcLines[$ln - 1])") }
            }
            $messages += @{ role = 'assistant'; content = $reply }
            $messages += @{ role = 'user'; content = "<<<AGENT>>> The build or test failed. Fix the file. Output the corrected COMPLETE file in one fenced block. Check that every parenthesis is balanced and every function you call is imported.`n`nCompiler/test output:`n``````n$tail`n``````n`nThe lines of your file the compiler pointed at:`n``````n$($ctxLines.ToString())``````" }
        }
        if ($verdict -eq 'needs-review') {
            $add = @($implRel); if ($testDest) { $add += $testDest }
            [void](Git $Work (@('add', '--') + $add))
            $c = Git $Work @('commit', '-m', "agent(local): $cid (Qwen3-Coder 30B-A3B), needs review")
            [void]$log.AppendLine("Committed on branch agent/$cid`n$($c.Out)")
        }
    } catch {
        [void]$log.AppendLine("ERROR: $($_.Exception.Message)")
        $verdict = 'failed'
    }
    New-Item -ItemType Directory -Force $ResultDir | Out-Null
    [void]$log.AppendLine("`n## Verdict`n$verdict after $attempt attempt(s). $(Get-Date -Format s)")
    [IO.File]::WriteAllText((Join-Path $ResultDir "$cid.md"), $log.ToString(), (New-Object Text.UTF8Encoding($false)))
    Set-CardField $card.Path 'status' $verdict
    Log "$cid -> $verdict"
    return $verdict
}

# ---- main -----------------------------------------------------------------------------------
$free = (Get-PSDrive ($Repo.Substring(0, 1))).Free / 1GB
if ($free -lt $MinFreeGB) { throw ("Only {0:N1} GB free, stopping." -f $free) }
$cards = @(Pick-Cards)
if ($cards.Count -eq 0) { Log 'No runnable local cards.'; return }
if (-not $DryRun) { Ensure-Server }
$n = 0; $fails = 0
foreach ($c in $cards) {
    if ($n -ge $MaxCards -or $fails -ge 2) { break }
    $v = Run-Card $c
    if ($v -eq 'skipped') { continue }
    $n++
    if ($v -eq 'failed') { $fails++ } else { $fails = 0 }
}
Log "Finished $n card(s)."
