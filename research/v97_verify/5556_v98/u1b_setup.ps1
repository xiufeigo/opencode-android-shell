. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib.ps1

function Ctr([string]$p,[string]$k){ $n=FindTxt $p $k; if($n){ return @([int](($n.X1+$n.X2)/2),[int](($n.Y1+$n.Y2)/2)) }; return $null }
function TapOn([string]$p,[string]$k){ $c=Ctr $p $k; if($c){ Ado "input tap $($c[0]) $($c[1])" | Out-Null; Start-Sleep -Milliseconds 800; return $true }; Write-Output "TAPFAIL:$k"; return $false }

Write-Output "=== STEP0 launch ==="
Ado "am start -n com.xiufeigo.opencodeshell/.OcActivity" | Out-Null
Start-Sleep -Seconds 4
$f = DumpIt "01_launch"
Write-Output "FOCUS=$(FocusNow)"

# HOME
if(-not (TapOn $f 'Home')){ }
Start-Sleep -Seconds 2
$f = DumpIt "02_home"
Write-Output "--- HOME nodes ---"
foreach($n in (GetNodes $f)){ if($n.Txt -and $n.Txt.Length -lt 40 -and $n.W -gt 0){ Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] '$($n.Txt)'") } }
ShotIt "02_home" | Out-Null
