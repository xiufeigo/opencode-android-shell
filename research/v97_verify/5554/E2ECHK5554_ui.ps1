# E2ECHK5554_ui.ps1 - standalone uiautomator dump helper (+ optional node table).
# Usage:
#   .\E2ECHK5554_ui.ps1 -Name prep_02_home          # dump only -> ui_E2ECHK5554_prep_02_home.xml
#   .\E2ECHK5554_ui.ps1 -Name prep_02_home -Nodes   # dump + print node table
param(
    [Parameter(Mandatory = $true)][string]$Name,
    [switch]$Nodes,
    [string]$Dev = 'emulator-5554'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5554_run.ps1" -Dev $Dev
Ui "E2ECHK5554_$Name"
if ($Nodes) {
    python (Join-Path $global:DIR 'E2ECHK5554_nodes.py') (Join-Path $global:DIR "ui_E2ECHK5554_$Name.xml")
}
