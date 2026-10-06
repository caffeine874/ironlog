$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Coach-Tailscale.ps1')
function Assert-Rejected([scriptblock]$Check) {
    $rejected = $false
    try { & $Check | Out-Null } catch { $rejected = $true }
    if (!$rejected) { throw 'Unsafe synthetic configuration was accepted.' }
}
$status = '{"BackendState":"Running","Self":{"DNSName":"computer.tail123.ts.net.","UserID":123},"User":{"123":{"LoginName":"owner@example.test"}}}' | ConvertFrom-Json
$identity = Get-CoachTailscaleIdentity $status
if ($identity.DnsName -ne 'computer.tail123.ts.net' -or $identity.LoginName -ne 'owner@example.test') { throw 'Identity extraction failed.' }
Assert-Rejected { Get-CoachTailscaleIdentity ('{"BackendState":"Stopped"}' | ConvertFrom-Json) }
Assert-Rejected { Get-CoachTailscaleIdentity ('{"BackendState":"Running","Self":{"DNSName":"attacker.example","UserID":123}}' | ConvertFrom-Json) }
Assert-Rejected { Get-CoachTailscaleIdentity ('{"BackendState":"Running","Self":{"DNSName":"computer.tail123.ts.net","UserID":0}}' | ConvertFrom-Json) }
if (Test-CoachServeMigration ('{}' | ConvertFrom-Json) $identity.DnsName) { throw 'Empty Serve config is not a TCP migration.' }
if (!(Test-CoachServeMigration ('{"TCP":{"8765":{"TCPForward":"127.0.0.1:8765"},"8766":{"TCPForward":"127.0.0.1:8766"}}}' | ConvertFrom-Json) $identity.DnsName)) { throw 'Coach TCP migration not detected.' }
$https = '{"TCP":{"8765":{"HTTPS":true}},"Web":{"computer.tail123.ts.net:8765":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:8765"}}}}}' | ConvertFrom-Json
if (Test-CoachServeMigration $https $identity.DnsName) { throw 'Existing HTTPS must not be removed as TCP.' }
Assert-Rejected { Test-CoachServeMigration ('{"TCP":{"8765":{"TCPForward":"127.0.0.1:9999"}}}' | ConvertFrom-Json) $identity.DnsName }
Assert-Rejected { Test-CoachServeMigration ('{"TCP":{"8765":{"HTTP":true}}}' | ConvertFrom-Json) $identity.DnsName }
Assert-Rejected { Test-CoachServeMigration ('{"AllowFunnel":{"computer.tail123.ts.net:8765":true}}' | ConvertFrom-Json) $identity.DnsName }
Assert-Rejected { Test-CoachServeMigration ('{"TCP":{"8765":{"HTTPS":true}},"Web":{"computer.tail123.ts.net:8765":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:9999"}}}}}' | ConvertFrom-Json) $identity.DnsName }
Write-Output 'Coach Tailscale synthetic validation: 11 checks passed. No Tailscale commands executed.'
