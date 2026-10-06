param([switch]$NoStart)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Coach-Tailscale.ps1')
$projectRoot = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $projectRoot 'coach-relay\.runtime'
$toolsRoot = Join-Path $projectRoot '.coach-tools'
New-Item -ItemType Directory -Force -Path $runtime,$toolsRoot | Out-Null
$node = (Get-Command node.exe -ErrorAction Stop).Source
$codex = Get-ChildItem (Join-Path $toolsRoot 'node_modules\@openai') -Filter codex.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if (!$codex) {
    & npm.cmd install --prefix $toolsRoot --no-audit --no-fund '@openai/codex@0.155.1'
    if ($LASTEXITCODE -ne 0) { throw 'Codex installation failed.' }
    $codex = Get-ChildItem (Join-Path $toolsRoot 'node_modules\@openai') -Filter codex.exe -Recurse | Select-Object -First 1
}
$coachHome = Join-Path $runtime 'codex-home'
New-Item -ItemType Directory -Force -Path $coachHome | Out-Null
$coachAuth = Join-Path $coachHome 'auth.json'
if (!(Test-Path $coachAuth)) {
    # A dedicated login owns its own rotating refresh token; never copy the
    # desktop's auth.json into an independent refresher.
    $previousCodexHome = $env:CODEX_HOME
    try {
        $env:CODEX_HOME = $coachHome
        & $codex.FullName login
        if ($LASTEXITCODE -ne 0) { throw 'Please complete ChatGPT login and run setup again.' }
    } finally { $env:CODEX_HOME = $previousCodexHome }
}
$authInfo = Get-Content -Raw -LiteralPath $coachAuth | ConvertFrom-Json
if ($authInfo.auth_mode -ne 'chatgpt') { throw 'AI coach requires ChatGPT login, never an API key.' }
function New-CoachSecret {
    $buffer = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($buffer) } finally { $generator.Dispose() }
    return [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+','-').Replace('/','_')
}
$configPath = Join-Path $runtime 'relay-config.json'
$previous = if (Test-Path $configPath) { Get-Content -Raw -LiteralPath $configPath | ConvertFrom-Json } else { $null }
$config = [ordered]@{
    host = '127.0.0.1'; port = 8765
    token = $(if ($previous.token) { $previous.token } else { New-CoachSecret })
    codexHome = $coachHome; codexCommand = $codex.FullName
    apkPath = $(if ($previous.apkPath) { $previous.apkPath } else { Join-Path $projectRoot 'dist\Training-1.12.apk' })
    downloadToken = $(if ($previous.downloadToken) { $previous.downloadToken } else { New-CoachSecret })
    pairingToken = $(if ($previous.pairingToken) { $previous.pairingToken } else { New-CoachSecret })
    publicUrl = 'http://127.0.0.1:8765'
}
$tailscale = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
if (!(Test-Path $tailscale)) { throw 'Install Tailscale with scripts\Install-Tailscale.ps1, then sign in. Existing relay settings were preserved.' }
$statusText = & $tailscale status --json 2>$null
if ($LASTEXITCODE -ne 0) { throw 'Could not read Tailscale status. Existing relay settings were preserved.' }
$identity = Get-CoachTailscaleIdentity ($statusText | ConvertFrom-Json)
$serveText = & $tailscale serve status --json 2>$null
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect Tailscale Serve. Existing relay settings were preserved.' }
$removeLegacyTcp = Test-CoachServeMigration ($serveText | ConvertFrom-Json) $identity.DnsName
if ($removeLegacyTcp) {
    & $tailscale serve --tcp=8765 off
    if ($LASTEXITCODE -ne 0) { throw 'Could not disable the old coach TCP listener.' }
}
& $tailscale serve --bg --https=8765 http://127.0.0.1:8765
if ($LASTEXITCODE -ne 0) { throw 'Private HTTPS Serve could not start. Enable HTTPS using the displayed Tailscale link, then run setup again. HTTP forwarding was not enabled.' }
$config.publicUrl = 'https://' + $identity.DnsName + ':8765'
$config.allowedTailscaleLogin = $identity.LoginName
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json), $utf8)
[IO.File]::WriteAllText((Join-Path $runtime 'node-path.txt'), $node, $utf8)
# Keep tokens and ChatGPT credentials local to this Windows account.
$account = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $runtime /inheritance:r /grant:r "${account}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' 'BUILTIN\Administrators:(OI)(CI)F' /Q | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect coach credentials.' }
$taskName = 'Training AI Coach'
$runner = Join-Path $PSScriptRoot 'Run-Coach.ps1'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $runner + '"') -WorkingDirectory $projectRoot
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $account
$principal = New-ScheduledTaskPrincipal -UserId $account -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
try {
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
    if (!$NoStart) {
        Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        # Windows may leave the child process alive when stopping its task.
        # Retire only this project's verified relay so new code/config is loaded.
        $serverPath = Join-Path $projectRoot 'coach-relay\server.mjs'
        Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" | Where-Object {
            $_.ExecutablePath -eq $node -and $_.CommandLine -and
            ($_.CommandLine.TrimEnd().EndsWith($serverPath) -or $_.CommandLine.TrimEnd().EndsWith('"' + $serverPath + '"'))
        } | ForEach-Object { Stop-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }
        Start-ScheduledTask -TaskName $taskName
    }
    Write-Host 'AI coach is configured to start at Windows sign-in.'
} catch {
    Write-Warning 'Automatic startup could not be registered. Use Start-Coach.cmd.'
    if (!$NoStart) { Start-Process powershell.exe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$runner+'"')) -WindowStyle Hidden | Out-Null }
}
$setupLink = if ($config.pairingToken) { $config.publicUrl + '/setup/' + $config.pairingToken } else { 'Tailscale login required; run Setup-Coach.ps1 again after connecting.' }
$downloadLink = $config.publicUrl + '/download/' + $config.downloadToken + '/Training-latest.apk'
$guide = "AI coach connection`r`n`r`nOpen this private link on the phone while Tailscale is connected:`r`n$setupLink`r`n`r`nAPK update:`r`n$downloadLink`r`n`r`nKeep these links private. Do not uninstall the existing app.`r`n"
[IO.File]::WriteAllText((Join-Path $runtime 'phone-setup.txt'), $guide, $utf8)
Write-Host ('Setup details saved: ' + (Join-Path $runtime 'phone-setup.txt'))
