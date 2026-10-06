# Pure validation helpers. Loading this file does not change Tailscale or read credentials.
function Get-CoachTailscaleIdentity {
    param($Status)
    if (!$Status -or $Status.BackendState -ne 'Running') { throw 'Connect Tailscale before running coach setup.' }
    $dnsName = ([string]$Status.Self.DNSName).TrimEnd('.').ToLowerInvariant()
    if ($dnsName -notmatch '^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.[a-z0-9-]+\.ts\.net$') {
        throw 'A valid Tailscale DNS name is required for HTTPS. Enable MagicDNS and HTTPS certificates.'
    }
    $ownerProperty = if ($Status.User -and $Status.Self.UserID) { $Status.User.PSObject.Properties[[string]$Status.Self.UserID] } else { $null }
    $loginName = if ($ownerProperty) { [string]$ownerProperty.Value.LoginName } else { '' }
    if ($loginName -notmatch '^[\x21-\x7e]{1,254}$' -or $loginName.Contains(',')) {
        throw 'Sign into Tailscale with your personal account. A tagged or unidentified device cannot configure this private relay.'
    }
    return [pscustomobject]@{ DnsName = $dnsName; LoginName = $loginName }
}

function Test-CoachServeMigration {
    param($ServeStatus, [string]$DnsName)
    # Only the relay's fixed port can be migrated. Never reset unrelated services.
    $port = if ($ServeStatus.TCP) { $ServeStatus.TCP.PSObject.Properties['8765'].Value } else { $null }
    $webEntries = @($ServeStatus.Web.PSObject.Properties | Where-Object { $_.Name -match ':8765$' })
    $funnelEntries = @($ServeStatus.AllowFunnel.PSObject.Properties | Where-Object { $_.Name -match ':8765$' -and $_.Value })
    if ($funnelEntries.Count -gt 0) { throw 'Port 8765 is configured for public Funnel. Disable that exposure before coach setup.' }
    if ($webEntries.Count -gt 0) {
        if ($webEntries.Count -ne 1 -or $webEntries[0].Name -ne "${DnsName}:8765") { throw 'Another service already uses HTTPS port 8765.' }
        $handlers = @($webEntries[0].Value.Handlers.PSObject.Properties)
        if ($handlers.Count -ne 1 -or $handlers[0].Name -ne '/' -or
            $handlers[0].Value.Proxy -notin @('http://127.0.0.1:8765', 'http://localhost:8765')) {
            throw 'Another service already uses HTTPS port 8765. Its configuration was preserved.'
        }
    }
    if (!$port) { return $false }
    if ($port.TCPForward) {
        if ($port.TCPForward -notin @('127.0.0.1:8765', 'localhost:8765') -or $webEntries.Count -gt 0) {
            throw 'Another service already uses TCP port 8765. Its configuration was preserved.'
        }
        return $true
    }
    if (!$port.HTTPS -or $webEntries.Count -ne 1) { throw 'Port 8765 has an unsupported existing configuration.' }
    return $false
}
