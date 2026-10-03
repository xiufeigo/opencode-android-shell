. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3_map.ps1"
$ErrorActionPreference = 'Stop'
InitDir
$null = FocusInfo

ScrollForm 4 "up"   # form to top
$null = Cap "M_E1_top"
ShowM "E1_top"

$null = TypeInto "host" "ssh -p 2222 sshtest@127.0.0.1" "E1"
$null = TypeInto "name" "E2ECHK5554d3c-01" "E1"
$null = TypeInto "pass" "keypass123" "E1"
$null = Cap "E1_ready"
ShowM "E1_ready"

$add = FindN "Add server"
if ($add) {
    $b = Bnd $add
    Write-Host ("  TAP Add server @{0},{1} en={2}" -f $b['cx'],$b['cy'],$add.GetAttribute("enabled"))
    TapXY $b['cx'] $b['cy']
} else { Write-Host "  Add server NOT FOUND" }
Start-Sleep -Milliseconds 2000
$null = Cap "E1_post1"
Say "FORM_OPEN_AFTER=$(FormOpen)"
ShowM "E1_post1"
Say "DONE_E1"
