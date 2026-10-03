param([string]$Tag,[string]$Pattern,[string]$Attr="text")
$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"
$x=[xml](Get-Content "$d/$Tag.xml" -Raw -Encoding UTF8)
$nodes=$x.SelectNodes("//node")
$rx=[regex]::new($Pattern,[System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
foreach($n in $nodes){
  $t=$n.GetAttribute("text"); $cd=$n.GetAttribute("content-desc"); $cl=$n.GetAttribute("class"); $b=$n.GetAttribute("bounds"); $id=$n.GetAttribute("resource-id")
  $field = switch($Attr){"text"{$t} "desc"{$cd} "class"{$cl} "id"{$id} "bounds"{$b} default{$t}}
  if($field -and $rx.IsMatch($field)){
    $cx=0;$cy=0
    if($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ $cx=([int]$Matches[1]+[int]$Matches[3])/2; $cy=([int]$Matches[2]+[int]$Matches[4])/2 }
    "{0,-14} {1,-46} b={2,-24} c=({3},{4})" -f $cl.Substring([Math]::Max(0,$cl.LastIndexOf('.')+1)), ($t + "|" + $cd), $b, $cx, $cy
  }
}
