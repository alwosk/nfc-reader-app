$ErrorActionPreference = 'Stop'
$launcher = Join-Path $PSScriptRoot 'start.cmd'
$startup = [Environment]::GetFolderPath('Startup')
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut((Join-Path $startup 'Q52 Attendance.lnk'))
$link.TargetPath = $launcher
$link.WorkingDirectory = $PSScriptRoot
$link.Save()
Write-Host 'Q52 receiver will start after Windows sign-in. Keep this folder in its current location.'
