param([Parameter(Mandatory=$true)][string]$PhoneIP, [Parameter(Mandatory=$true)][string]$PcIP)
$ErrorActionPreference = 'Stop'
foreach ($value in @($PhoneIP, $PcIP)) {
  if ($value -notmatch '^(0|[1-9][0-9]{0,2})(\.(0|[1-9][0-9]{0,2})){3}$') { throw 'Use dotted IPv4 addresses.' }
  $ip = [System.Net.IPAddress]::Parse($value)
  $bytes = $ip.GetAddressBytes()
  $private = $bytes.Length -eq 4 -and (
    $bytes[0] -eq 10 -or
    ($bytes[0] -eq 172 -and $bytes[1] -ge 16 -and $bytes[1] -le 31) -or
    ($bytes[0] -eq 192 -and $bytes[1] -eq 168))
  if (-not $private) { throw 'Use private LAN IPv4 addresses only.' }
}
# Limit this rule to the two devices and a trusted Windows private network.
New-NetFirewallRule -DisplayName 'Attendance via LAN' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8765 -LocalAddress $PcIP -RemoteAddress $PhoneIP -Profile Private
