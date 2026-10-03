# v134 几何测量 v3 —— 统一口径 + 排除时间线层渗透
param([Parameter(Mandatory=$true)][string]$Tag, [int]$ScreenW = 1366, [int]$ScreenH = 600)
$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134\tk.ps1"
$ime = ImeShownV2
Write-Output "[$Tag] mInputShown=$ime  $((& $Adb -s $Serial shell 'dumpsys window | grep mCurrentFocus' 2>$null) -join '')"
$d = DumpUI $Tag
$ns = NodeList $d.Path
ShotV2 $Tag | Out-Null
$rows = New-Object System.Collections.Generic.List[object]
foreach($n in $ns){
  if($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){
    $x1=[int]$Matches[1];$y1=[int]$Matches[2];$x2=[int]$Matches[3];$y2=[int]$Matches[4]
    $rows.Add([pscustomobject]@{ x1=$x1;y1=$y1;x2=$x2;y2=$y2;w=($x2-$x1);h=($y2-$y1);text=$n.text;desc=$n.desc;cls=$n.class;clickable=$n.clickable })
  }
}
$et = $rows | Where-Object { $_.cls -eq 'android.widget.EditText' } | Select-Object -First 1
if(-not $et){ Write-Output "NO EditText"; exit 1 }
Write-Output "editor: [$($et.x1),$($et.y1)][$($et.x2),$($et.y2)] h=$($et.h)"

# composer children: 口径(y1>=et.y1-2) + 排除 Open terminal + 排除时间线层(全宽行 / 低于编辑器下沿)
$kids = $rows | Where-Object {
  $_.y1 -ge ($et.y1 - 2) -and $_.y2 -le ($et.y2 + 2) -and
  -not ($_.text -eq 'Open terminal' -or $_.desc -eq 'Open terminal') -and
  -not ($_.cls -eq 'android.widget.TextView' -and $_.w -ge 1200)
}
$excl = $rows | Where-Object { $_.y1 -ge ($et.y1-2) -and -not ($kids -contains $_) -and -not ($_.text -eq 'Open terminal' -or $_.desc -eq 'Open terminal') }
Write-Output "EXCLUDED (timeline-layer bleed):"
$excl | ForEach-Object { $l=if($_.text){$_.text}else{$_.desc}; "  [$($_.cls)] '$l' [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] w=$($_.w)" }
$ux1=($kids|Measure-Object x1 -Minimum).Minimum; $uy1=($kids|Measure-Object y1 -Minimum).Minimum
$ux2=($kids|Measure-Object x2 -Maximum).Maximum; $uy2=($kids|Measure-Object y2 -Maximum).Maximum
$unionPx=$uy2-$uy1
Write-Output "composer children:"
$kids | ForEach-Object { $l=if($_.text){$_.text}else{$_.desc}; "  [$($_.cls)] '$l' [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] h=$($_.h) clk=$($_.clickable)" }
Write-Output "UNION(excl Open terminal) = [$ux1,$uy1][$ux2,$uy2] unionPx=$unionPx unionBottom=$uy2"

$hosts = $rows | Where-Object {
  $_.x1 -le 2 -and $_.x2 -ge ($ScreenW-2) -and $_.y1 -le $et.y1 -and $_.y2 -ge ($uy2-2) -and $_.h -le 400 -and $_.h -ge 40
}
$paneHost = $hosts | Sort-Object { $_.h } | Select-Object -First 1
if(-not $paneHost){ Write-Output "NO pane host"; $hosts|ForEach-Object{"  cand [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] h=$($_.h)"}; exit 1 }
$paneH=$paneHost.h; $paneBottom=$paneHost.y2
Write-Output "PANE HOST: [$($paneHost.x1),$($paneHost.y1)][$($paneHost.x2),$($paneHost.y2)] h=$paneH paneBottom=$paneBottom"
$band=48
Write-Output ""
Write-Output "===== GEOMETRY @ $Tag ====="
Write-Output "  hostBox       = [$($paneHost.x1),$($paneHost.y1)][$($paneHost.x2),$($paneHost.y2)] paneH=$paneH"
Write-Output "  union(exclOT) = [$ux1,$uy1][$ux2,$uy2] unionPx=$unionPx unionBottom=$uy2"
Write-Output "  band          = $band"
Write-Output "  paneBottom(i) = $paneBottom"
Write-Output "  paneBottom(ii)= $($paneBottom-48)"
Write-Output "  (i)  diff     = $($uy2-$paneBottom)"
Write-Output "  (ii) diff     = $($uy2-($paneBottom-48))"
Write-Output "  timeline box  = $($paneH-$band)"
Write-Output "  readable      = $(if($paneH -ge 150){$paneH-96}else{$paneH-48})"
Write-Output "  (2) unionPx<=band = $($unionPx -le $band)  (unionPx=$unionPx)"
Write-Output "  mInputShown   = $ime"
$qa = $rows | Where-Object { ($_.text -eq 'Steer' -or $_.text -eq 'Send' -or $_.desc -eq ('Rem'+'ove')) -and $_.clickable -eq 'true' }
if($qa){ Write-Output "  queue actions on screen:"; $qa|ForEach-Object{ $l=if($_.text){$_.text}else{$_.desc}; "    '$l' [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] h=$($_.h) w=$($_.w)" } }
else { Write-Output "  queue actions on screen: NONE (bar CLOSED)" }
Write-Output "======================="
