. "$PWD\E2ECHK5558_run.ps1" -Dev emulator-5558
$n = Grab
Write-Host "label present: $($n | Select-String -Pattern 'Host or SSH command' -Quiet)"
$r = FormScrollRect
Write-Host "rect: $($r -join ',')"
$f = Fields $n
Write-Host "Fields count: $($f.Count)"
$f | ForEach-Object { Write-Host "  FLD [$_]" }
Write-Host "FieldNear host below -> [$(FieldNear $n 'Host or SSH command' 'below')]"
