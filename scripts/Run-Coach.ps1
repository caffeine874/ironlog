$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $projectRoot 'coach-relay\.runtime'
$mutex = New-Object Threading.Mutex($false, 'Local\TrainingAiCoachRelay')
$ownsMutex = $false
try {
    try { $ownsMutex = $mutex.WaitOne(0) }
    catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (!$ownsMutex) { exit 0 }
    $node = (Get-Content -Raw -LiteralPath (Join-Path $runtime 'node-path.txt')).Trim()
    Set-Location $projectRoot
    $env:COACH_CONFIG = Join-Path $runtime 'relay-config.json'
    # No API key provider is available to the relay's child process.
    Remove-Item Env:OPENAI_API_KEY,Env:CODEX_API_KEY,Env:AZURE_OPENAI_API_KEY -ErrorAction SilentlyContinue
    while ($true) {
        # Start-Process avoids Windows PowerShell's NativeCommandError treating a
        # harmless native stderr line as a terminating error and leaving Node orphaned.
        $serverPath = Join-Path $projectRoot 'coach-relay\server.mjs'
        $existing = Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" | Where-Object {
            $_.ExecutablePath -eq $node -and $_.CommandLine -and
            $_.CommandLine.Contains($serverPath)
        } | Select-Object -First 1
        if ($existing) {
            Wait-Process -Id $existing.ProcessId -ErrorAction SilentlyContinue
        } else {
            $child = Start-Process -FilePath $node -ArgumentList ('"' + $serverPath + '"') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -Wait
            ('Last relay exit: ' + $child.ExitCode + ' at ' + (Get-Date -Format o)) | Set-Content -LiteralPath (Join-Path $runtime 'supervisor-status.txt')
        }
        Start-Sleep -Seconds 5
    }
} finally {
    if ($ownsMutex) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
