# 原始口径重算（geom.ps1 规则：y1>=et.y1-2, y2<=et.y2+60, 排除 Open terminal）
param([Parameter(Mandatory=$true)][string]$File, [Parameter(Mandatory=$true)][string]$Tag, [int]$ScreenW=1366)
$ErrorActionPreference='Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134\tk.ps1"
$rows = New-Object System.Collections.Generic.List[object]
foreach($n in (NodeList $File)){
  if($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){
    $rows.Add([pscustomobject]@{ x1=[int]$Matches[1];y1=[int]$Matches[2];x2=[int]$Matches[3];y2=[int]$Matches[4];
      w=([int]$Matches[3]-[int]$Matches[1]);h=([int]$Matches[4]-[int]$Matches[2]);text=$n.text;desc=$n.desc;cls=$n.class })
  }
}
$et = $rows | Where-Object { $_.cls -eq 'android.widget.EditText' } | Select-Object -First 1
$kids = $rows | Where-Object { $_.y1 -ge ($et.y1-2) -and $_.y2 -le ($et.y2+60) -and -not ($_.text -eq 'Open terminal' -or $_.desc -eq 'Open terminal') }
$ux1=($kids|Measure-Object x1 -Minimum).Minimum; $uy1=($kids|Measure-Object y1 -Minimum).Minimum
$ux2=($kids|Measure-Object x2 -Maximum).Maximum; $uy2=($kids|Measure-Object y2 -Maximum).Maximum
$unionPx=$uy2-$uy1
$hosts = $rows | Where-Object { $_.x1 -le 2 -and $_.x2 -ge ($ScreenW-2) -and $_.y1 -le $et.y1 -and $_.y2 -ge ($uy2-2) -and $_.h -le 400 -and $_.h -ge 40 }
$ph = $hosts | Sort-Object { $_.h } | Select-Object -First 1
Write-Output "== ORIGINAL-CALIBER @ $Tag =="
Write-Output "  editor      : [$($et.x1),$($et.y1)][$($et.x2),$($et.y2)] h=$($et.h)"
Write-Output "  kids count  : $($kids.Count)"
$kids | ForEach-Object { $l=if($_.text){$_.text}else{$_.desc}; "     [$($_.cls)] '$l' [$($_.x1),$_.y1][$($_.x2),$($_.y2)] h=$($_.h)" }
Write-Output "  UNION       = [$ux1,$uy1][$ux2,$uy2]  unionPx=$unionPx  unionBottom=$uy2"
Write-Output "  PANE HOST   = [$($ph.x1),$($ph.y1)][$($ph.x2),$($ph.y2)] h=$($ph.h)  paneBottom=$($ph.y2)"
Write-Output "  ①(i)  diff  = $($uy2 - $ph.y2)"
Write-Output "  ①(ii) diff  = $($uy2 - ($ph.y2-48))"
Write-Output "  timeline box= $($ph.h-48)   readable=$(if($ph.h -ge 150){$ph.h-96}else{$ph.h-48})"
Write-Output "  ② unionPx<=48 = $($unionPx -le 48)"
