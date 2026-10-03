$ErrorActionPreference='Continue'
. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1_lib.ps1
function Ctr([string]$p,[string]$k){ $n=FindTxt $p $k; if($n){ return @([int](($n.X1+$n.X2)/2),[int](($n.Y1+$n.Y2)/2)) }; return $null }

$s = Ctr (DumpIt "88_row") 'Planatio s.'
if($s){ Ado "input tap $($s[0]) $($s[1])" | Out-Null }
Start-Sleep -Seconds 3
$mo = Ctr (DumpIt "89_sess") 'More options'
if($mo){ Write-Output "TAP_MORE [$($mo[0]),$($mo[1])]"; Ado "input tap $($mo[0]) $($mo[1])" | Out-Null }
Start-Sleep -Seconds 2
$f = DumpIt "90_menu"
Write-Output "=== menu items ==="
GetNodes $f | Where-Object { $_.Txt -and $_.Click -eq 'true' -and $_.Y1 -gt 150 } | ForEach-Object { Write-Output ("M [$($_.X1),$($_.Y1)][$($_.X2),$($_.Y2)] '$($_.Txt)'") }
$d = FindTxt $f 'Delete'
if($d){ Write-Output "TAP_DEL [$($d.X1),$($d.Y1)]"; Ado "input tap $([int](($d.X1+$d.X2)/2)) $([int](($d.Y1+$d.Y2)/2))" | Out-Null }
else { Write-Output "NO_DELETE_MENU" }
Start-Sleep -Seconds 2
$f2 = DumpIt "91_confirm"
Write-Output "=== confirm layer ==="
GetNodes $f2 | Where-Object { $_.Txt -ne '' -and $_.Y1 -gt 250 } | ForEach-Object { Write-Output ("K [$($_.X1),$($_.Y1)][$($_.X2),$($_.Y2)] click=$($_.Click) '$($_.Txt)'") }
ShotIt "91_confirm" | Out-Null
