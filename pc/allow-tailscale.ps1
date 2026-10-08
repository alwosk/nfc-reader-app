param([Parameter(Mandatory=$true)][string]$PhoneIP, [Parameter(Mandatory=$true)][string]$PcIP)
$ErrorActionPreference = 'Stop'
foreach ($value in @($PhoneIP, $PcIP)) {
  $ip = [System.Net.IPAddress]::Parse($value)
  $bytes = $ip.GetAddressBytes()
  if ($bytes.Length -ne 4 -or $bytes[0] -ne 100 -or $bytes[1] -lt 64 -or $bytes[1] -gt 127) { throw 'Use Tailscale IPv4 addresses only.' }
}
New-NetFirewallRule -DisplayName 'Attendance via Tailscale' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8765 -LocalAddress $PcIP -RemoteAddress $PhoneIP -Profile Any
