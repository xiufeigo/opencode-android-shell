$ErrorActionPreference='Continue'
. "$PSScriptRoot\c8lib.ps1"
Log "--- loading b8_funcs ---"
try { . "$PSScriptRoot\b8_funcs.ps1"; Log "LOADED OK" } catch { Log ("LOAD ERROR: " + $_.Exception.Message) }
foreach ($f in 'GetFormET','FindTxt','CardUp','PanelRows','TunnelLen','EnsurePanel','EnumRows','DeleteRow','RowStates') {
  if (Get-Command $f -ErrorAction SilentlyContinue) { Log "  have $f" } else { Log "  MISSING $f" }
}
Log "--- baseline tunnel len ---"
[void](TunnelLen 'baseline')
Log "focus=$(Focus)"
