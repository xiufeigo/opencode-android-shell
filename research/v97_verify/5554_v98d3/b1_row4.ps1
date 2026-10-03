$ErrorActionPreference='Continue'
. "$PSScriptRoot\c8lib.ps1"
. "$PSScriptRoot\b8_funcs.ps1"
. "$PSScriptRoot\dv.ps1"

Log "=== B: D3-3 4th row (E2ECHK5554d3e-4) ==="
[void](TunnelLen 'B_before')
Log "--- rows before delete ---"
$before = EnumRows 'B_before'
Log "ROWS_BEFORE_ENUM=[$($before -join '|')]"
$r = DelVerified 'E2ECHK5554d3e-4'
Log "B RESULT ok=$($r.ok) gone=$($r.gone) confirm=$($r.confirmB) y=$($r.confirmY)"
Log "  rowsBefore=[$($r.rowsBefore)]"
Log "  rowsAfter=[$($r.rowsAfter)]"
[void](TunnelLen 'B_after')
$after = EnumRows 'B_after'
Log "ROWS_AFTER_ENUM=[$($after -join '|')]"
Shot 'B_after_delete4'
Log "=== B DONE ==="

