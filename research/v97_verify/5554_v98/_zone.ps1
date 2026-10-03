param([string]$File,[int]$MinY=0,[int]$MaxY=99999)
$ErrorActionPreference='Stop'
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98"
$raw = Get-Content "$dir\$File" -Raw
$nodeRx = [regex]'<node\b[^>]*>'
$boundRx = [regex]'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
$textRx  = [regex]'text="([^"]*)"'
$descRx  = [regex]'content-desc="([^"]*)"'
$clickRx = [regex]'clickable="([^"]*)"'
foreach($m in $nodeRx.Matches($raw)){
  $bm = $boundRx.Match($m.Value); if(-not $bm.Success){continue}
  $y1 = [int]$bm.Groups[2].Value
  if($y1 -lt $MinY -or $y1 -gt $MaxY){continue}
  $t = $textRx.Match($m.Value);  $t = $(if($t.Success){$t.Groups[1].Value}else{""})
  $d = $descRx.Match($m.Value);  $d = $(if($d.Success){$d.Groups[1].Value}else{""})
  $c = $clickRx.Match($m.Value); $c = $(if($c.Success){$c.Groups[1].Value}else{""})
  $b = "[" + $bm.Groups[1].Value + "," + $bm.Groups[2].Value + "][" + $bm.Groups[3].Value + "," + $bm.Groups[4].Value + "]"
  if($t -eq "" -and $d -eq ""){ continue }
  $cx = [math]::Round(([int]$bm.Groups[1].Value + [int]$bm.Groups[3].Value)/2)
  $cy = [math]::Round(([int]$bm.Groups[2].Value + [int]$bm.Groups[4].Value)/2)
  "{0,-24} text='{1}' desc='{2}' click={3} ctr=({4},{5})" -f $b,$t,$d,$c,$cx,$cy
}
