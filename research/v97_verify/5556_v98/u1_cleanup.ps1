$ErrorActionPreference='Continue'
. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1_lib.ps1

function Ctr([string]$p,[string]$k){ $n=FindTxt $p $k; if($n){ return @([int](($n.X1+$n.X2)/2),[int](($n.Y1+$n.Y2)/2)) }; return $null }

# 回 Home 会话列表（点 header 的 Home，不按 BACK）
$h = Ctr (DumpIt "84_home") 'Home'
if($h){ Ado "input tap $($h[0]) $($h[1])" | Out-Null; Start-Sleep -Seconds 2 }
$f = DumpIt "85_homelist"
Write-Output "=== sessions in list ==="
GetNodes $f | Where-Object { $_.Txt -and $_.Txt.Contains('Planatio') } | ForEach-Object { Write-Output ("SESS [$($_.X1),$($_.Y1)][$($_.X2),$($_.Y2)] '$($_.Txt)'") }

# 找该行右侧的 More options
$row = GetNodes $f | Where-Object { $_.Txt -and $_.Txt.Contains('Planatio') } | Select-Object -First 1
if($row){
  $mo = GetNodes $f | Where-Object { $_.Txt -eq 'More options' -and $_.Y1 -le $row.Y1 -and $_.Y1 -ge ($row.Y1-10) -and $_.X1 -gt $row.X2 } | Select-Object -First 1
  if($mo){ Write-Output "MORE [$($mo.X1),$($mo.Y1)][$($mo.X2),$($mo.Y2)]"; Ado "input tap $([int](($mo.X1+$mo.X2)/2)) $([int](($mo.Y1+$mo.Y2)/2))" | Out-Null }
  else { Write-Output "NO_MORE_OPT" }
}
Start-Sleep -Seconds 2
$f2 = DumpIt "86_menu"
Write-Output "=== menu ==="
GetNodes $f2 | Where-Object { $_.Txt -and $_.Click -eq 'true' -and $_.Y1 -gt 200 } | ForEach-Object { Write-Output ("MENU [$($_.X1),$($_.Y1)][$($_.X2),$($_.Y2)] '$($_.Txt)'") }
$d = FindTxt $f2 'Delete'
if($d){ Write-Output "TAP_DELETE [$($d.X1),$($d.Y1)][$($d.X2),$($d.Y2)]"; Ado "input tap $([int](($d.X1+$d.X2)/2)) $([int](($d.Y1+$d.Y2)/2))" | Out-Null }
Start-Sleep -Seconds 2
$f3 = DumpIt "87_confirm"
Write-Output "=== confirm layer ==="
GetNodes $f3 | Where-Object { $_.Txt -ne '' -and $_.Y1 -gt 200 } | ForEach-Object { Write-Output ("C [$($_.X1),$($_.Y1)][$($_.X2),$($_.Y2)] click=$($_.Click) '$($_.Txt)'") }
ShotIt "87_confirm" | Out-Null
