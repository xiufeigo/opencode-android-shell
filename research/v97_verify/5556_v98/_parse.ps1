param([Parameter(Mandatory=$true)][string]$File)
$ErrorActionPreference='Continue'
# Parse uiautomator dump XML: match both text="..." and text='...'
[xml]$x = Get-Content -Raw -Encoding UTF8 $File
$nodes = $x.SelectNodes("//*[@text or @content-desc or @clickable='true']")
$idx = 0
foreach ($n in $nodes) {
  $b = $n.GetAttribute("bounds")
  if (-not $b) { continue }
  if ($b -notmatch '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') { continue }
  $x1=[int]$Matches[1]; $y1=[int]$Matches[2]; $x2=[int]$Matches[3]; $y2=[int]$Matches[4]
  $t = $n.GetAttribute("text"); $d = $n.GetAttribute("content-desc")
  $cl= $n.GetAttribute("clickable"); $en=$n.GetAttribute("enabled")
  $cx=[int](($x1+$x2)/2); $cy=[int](($y1+$y2)/2)
  $line = "{0,-3} [{1},{2}][{3},{4}] ctr={5},{6} click={7} en={8}" -f $idx,$x1,$y1,$x2,$y2,$cx,$cy,$cl,$en
  if ($t) { $line += " TEXT=`"$t`"" }
  if ($d) { $line += " DESC=`"$d`"" }
  $line
  $idx++
}
"TOTAL_NODES=$idx"
