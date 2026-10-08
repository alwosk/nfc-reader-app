param([switch]$Disable)
$ErrorActionPreference = 'Stop'
$startup = [Environment]::GetFolderPath('Startup')
$linkPath = Join-Path $startup 'Attendance Receiver.lnk'
$legacy = Join-Path $startup 'Q52 Attendance.lnk'
if ($Disable) {
    if (Test-Path $linkPath) { Remove-Item $linkPath }
    if (Test-Path $legacy) { Remove-Item $legacy }
    exit 0
}
$python = Join-Path $PSScriptRoot '.venv\Scripts\pythonw.exe'
if (!(Test-Path $python)) { throw 'Run install.cmd first.' }
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($linkPath)
$link.TargetPath = $python
$link.Arguments = '"' + (Join-Path $PSScriptRoot 'app.py') + '" --background'
$link.WorkingDirectory = $PSScriptRoot
$link.Save()
if (Test-Path $legacy) { Remove-Item $legacy }
