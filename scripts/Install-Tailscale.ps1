$ErrorActionPreference = 'Stop'
if (Test-Path (Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe')) { Write-Host 'Tailscale is already installed.'; exit 0 }
$projectRoot = Split-Path $PSScriptRoot -Parent
$downloads = Join-Path $projectRoot '.coach-tools\downloads'
New-Item -ItemType Directory -Force -Path $downloads | Out-Null
$installer = Join-Path $downloads 'tailscale-setup.msi'
Invoke-WebRequest 'https://dl.tailscale.com/stable/tailscale-setup-1.102.4-amd64.msi' -OutFile $installer
$signature = Get-AuthenticodeSignature -LiteralPath $installer
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Tailscale Inc\.') { throw 'Invalid Tailscale installer signature.' }
$process = Start-Process msiexec.exe -Verb RunAs -ArgumentList @('/i',('"'+$installer+'"'),'/qn','/norestart','TS_NOLAUNCH=1','TS_UNATTENDEDMODE=always') -WindowStyle Hidden -PassThru -Wait
if ($process.ExitCode -notin @(0,3010)) { throw ('Tailscale installation failed: ' + $process.ExitCode) }
Write-Host 'Tailscale installed. Sign in once, then run Setup-Coach.ps1.'
