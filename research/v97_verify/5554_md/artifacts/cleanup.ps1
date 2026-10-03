# v140 cleanup attempt: long-press the self-created E2ECHK5554u1w message row and look for a delete affordance.
param(
  [string]$Adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe",
  [string]$Ser = "emulator-5554",
  [string]$Art = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\artifacts"
)
function Dump([string]$t){ $l="$Art\$t.xml"; & $Adb -s $Ser shell uiautomator dump "/sdcard/$t.xml" 2>&1|Out-Null; & $Adb -s $Ser pull "/sdcard/$t.xml" $l 2>&1|Out-Null; $l }
$d = Dump 'v140_cl0'
$x = Get-Content $d -Raw
$hits = [regex]::Matches($x, '<node\s[^>]*E2ECHK5554u1w[^>]*>')
"nodes containing E2ECHK5554u1w: $($hits.Count)"
$tgt = $null
foreach($h in $hits){
  $v=$h.Value
  $c=if($v -match 'class="([^"]*)"'){$Matches[1]}else{''}
  $b=if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){"$($Matches[1]),$($Matches[2]),$($Matches[3]),$($Matches[4])"}else{''}
  $lc=if($v -match 'long-clickable="([^"]*)"'){$Matches[1]}else{'false'}
  "  cls=$c long-clickable=$lc bounds=$b"
  if($tgt -eq $null -and $b -ne ''){ $p=$b -split ','; $tgt=[pscustomobject]@{x=[int](([int]$p[0]+[int]$p[2])/2); y=[int](([int]$p[1]+[int]$p[3])/2); lc=$lc} }
}
if($tgt -eq $null){ "no target"; exit 1 }
"target center=($($tgt.x),$($tgt.y)) long-clickable=$($tgt.lc)"
& $Adb -s $Ser shell input swipe $tgt.x $tgt.y $tgt.x $tgt.y 1200 | Out-Null
Start-Sleep -Milliseconds 1500
$d2 = Dump 'v140_cl1'
$x2 = Get-Content $d2 -Raw
"--- after long-press: delete affordances ---"
foreach($w in @('Delete','Rem'+'ove','Delete message','oc_delete','discard')){
  if($x2 -match [regex]::Escape($w)){ "FOUND: $w" } else { "absent: $w" }
}
$m = [regex]::Matches($x2, '<node\s[^>]*(?:content-desc|text)="([^"]*(?:Delete|Rem)[^"]*)"[^>]*>')
if($m.Count -eq 0){ "=> NO delete affordance appeared" } else { foreach($i in $m){ "   " + $i.Value.Substring(0,[Math]::Min(180,$i.Value.Length)) } }
