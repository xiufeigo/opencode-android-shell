param([string]$Path, [string]$Tag)
$c = Get-Content $Path -Raw
"===== $Tag : $Path ====="
"--- ScrollViews (x1=434 form/page) ---"
foreach ($m in [regex]::Matches($c, '<node[^>]*class="android\.widget\.ScrollView"[^>]*>')) {
  $s=$m.Value
  $bm=[regex]::Match($s,'bounds="([^"]*)"').Groups[1].Value
  $sc=[regex]::Match($s,'scrollable="([^"]*)"').Groups[1].Value
  "  ScrollView $bm scrollable=$sc"
}
"--- form text/EditText nodes (x1 in 430..920) ---"
foreach ($m in [regex]::Matches($c, '<node[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"[^>]*>')) {
  $s=$m.Value
  $bm=[regex]::Match($s,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  $x1=[int]$bm.Groups[1].Value; $y1=[int]$bm.Groups[2].Value; $x2=[int]$bm.Groups[3].Value; $y2=[int]$bm.Groups[4].Value
  $t=[regex]::Match($s,'text="([^"]*)"').Groups[1].Value
  $cl=[regex]::Match($s,'class="([^"]*)"').Groups[1].Value -replace 'android\.widget\.','' -replace 'android\.view\.',''
  if ($x1 -ge 430 -and $x1 -le 920 -and $t -ne '') {
    if ($t -match '^(Home|New session|Command palette|More options|Close|General|Appearance|Notifications|Shortcuts|Pairing|Projects|Worktrees|Providers|Models|Extensions|Servers|Shell|Experimental|About)$') { continue }
    "  {0,-50} {1,-10} [{2},{3}]-[{4},{5}]" -f $t.Substring(0,[Math]::Min(50,$t.Length)), $cl, $x1,$y1,$x2,$y2
  }
}
"--- clickable Views in footer band y 555..615 ---"
foreach ($m in [regex]::Matches($c, '<node[^>]*clickable="true"[^>]*bounds="\[[0-9]+,5[0-9][0-9]\]\[[0-9]+,6[0-9][0-9]\]"[^>]*>')) {
  $s=$m.Value
  $bm=[regex]::Match($s,'bounds="([^"]*)"').Groups[1].Value
  $t=[regex]::Match($s,'text="([^"]*)"').Groups[1].Value
  "  footerBtn '$t' $bm"
}
""
