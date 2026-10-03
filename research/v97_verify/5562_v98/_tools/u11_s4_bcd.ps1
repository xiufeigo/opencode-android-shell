$ErrorActionPreference='Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\_tools\u11_lib.ps1"
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
$log=New-Object System.Collections.Generic.List[string]
function L([string]$s){ $log.Add($s); Write-Host $s }

$ALL = @()

function TapEditText([string]$tag){
  $n="${tag}_te.xml"
  if(-not (Dump "${tag}_te")){ L "  TAP EditText -> DUMP FAILED"; return $false }
  $x=Get-Content $n -Raw
  foreach($m in [regex]::Matches($x,'<node\b[^>]*>')){
    if($m.Value -notmatch 'class="android\.widget\.EditText"'){ continue }
    $b=[regex]::Match($m.Value,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if(-not $b.Success){ continue }
    $x1=[int]$b.Groups[1].Value; $y1=[int]$b.Groups[2].Value
    $x2=[int]$b.Groups[3].Value; $y2=[int]$b.Groups[4].Value
    if($x1 -lt 1100 -or $y1 -lt 200 -or $y2 -gt 290){ continue }
    $cx=[int](($x1+$x2)/2); $cy=[int](($y1+$y2)/2)
    & $adb -s $S shell input tap $cx $cy
    L "  TAP EditText -> $cx,$cy bounds=[$x1,$y1][$x2,$y2]"
    return $true
  }
  L "  TAP EditText -> NOTFOUND"
  return $false
}
function CloseIme([string]$tag){
  $imeState=ImeShown
  if($imeState -eq 'false'){ L "  IME[$tag]=false (already closed)"; return $imeState }
  & $adb -s $S shell input tap 1250 245
  Start-Sleep -Milliseconds 900
  $imeState=ImeShown
  if($imeState -ne 'false'){
    L "  IME[$tag]: tap-dismiss left $imeState; keyevent 111 (no dialog on screen)"
    & $adb -s $S shell input keyevent 111
    Start-Sleep -Milliseconds 900
    $imeState=ImeShown
  }
  L "  IME[$tag]=$imeState"
  return $imeState
}
# Wipe whatever the field already holds, so the step is idempotent after a retry.
function ClearField([string]$tag){
  & $adb -s $S shell input keyevent 123 | Out-Null
  foreach($i in 1..30){ & $adb -s $S shell input keyevent 67 | Out-Null }
  Start-Sleep -Milliseconds 400
  L "  field cleared (MOVE_END + 30x DEL)"
}
function TapFind([string]$tag,[string]$pat){
  if(-not (Dump "${tag}_tf")){ L "  TAP '$pat' -> DUMP FAILED"; return $false }
  $h = Find "${tag}_tf" $pat | Select-Object -First 1
  if($h -match 'c=(\d+),(\d+)'){
    & $adb -s $S shell input tap $Matches[1] $Matches[2]
    L ("  TAP '" + $pat + "' -> " + $Matches[1] + "," + $Matches[2])
    return $true
  }
  L "  TAP '$pat' -> NOTFOUND"
  return $false
}
function SetEq($a,$b){
  $x=@($a | Sort-Object); $y=@($b | Sort-Object)
  return ((Compare-Object $x $y).Count -eq 0)
}
function Traverse2([string]$tag,[int]$maxSteps){
  $seen=New-Object System.Collections.Generic.List[string]
  $lastSig=""; $stable=0; $steps=0; $vp=0
  for($i=0;$i -lt $maxSteps;$i++){
    $n="${tag}_tr$i"
    if(-not (Dump $n)){ continue }
    $cb=CBounds $n
    if($null -ne $cb){ $h=$cb.y2-$cb.y1; if($h -gt $vp){ $vp=$h } }
    $r=Rows $n
    $sig=($r | ForEach-Object { $_.t }) -join ','
    foreach($row in $r){ if(-not $seen.Contains($row.t)){ $seen.Add($row.t) } }
    if($sig -eq $lastSig -and $steps -gt 0){ $stable++ } else { $stable=0 }
    if($stable -ge 2){ L "    [$tag] stabilized at step $i"; break }
    $lastSig=$sig
    $cx=[int](($cb.x1+$cb.x2)/2); $yBot=$cb.y2-60
    & $adb -s $S shell input swipe $cx $yBot $cx ($yBot-100) 80 | Out-Null
    Start-Sleep -Milliseconds 320
    $steps++
  }
  return @{ rows=$seen; steps=$steps; viewport=$vp; hitCap=($steps -ge $maxSteps) }
}

L "--- STEP A: baseline re-derive -> ALL (same run, same method) ---"
L ("  IME=" + (ImeShown))
# Make sure we are on an UNFILTERED list before calling anything a baseline.
CloseIme "A0" | Out-Null
if(Dump "u11_29_pre"){ if(Find "u11_29_pre" 'Clear filter'){ L "  stale filter present -> clearing first"; TapFind "u11_29" 'Clear filter' | Out-Null; Start-Sleep -Seconds 2; CloseIme "A1" | Out-Null } }
L ("  ClearButton_now=" + $(if(Dump "u11_29b"){ if((Find "u11_29b" 'Clear filter')){'STILL_PRESENT'}else{'gone'} }else{'dumpfail'}))
L ("  container=" + (Container "u11_10_files"))
L ("  ToTop=" + (ToTop "u11_30"))
$a=Traverse2 "u11_31" 45
$ALL=@($a.rows)
L ("  |ALL|=" + $ALL.Count + " steps=" + $a.steps + " hitCap=" + $a.hitCap + " viewport=" + $a.viewport)
L ("  ALL_first=" + $(if($ALL.Count){$ALL[0]}else{'n/a'}))
$ALL | Out-File -FilePath "$d\_u11_ALL_names.txt" -Encoding utf8

L "--- STEP B: filter 'gradle' -> F ---"
L ("  IME=" + (ImeShown))
TapEditText "u11_40" | Out-Null
Start-Sleep -Seconds 2
ClearField "u11_40"
& $adb -s $S shell input text "gradle"
Start-Sleep -Seconds 2
L ("  IME_after_typing=" + (ImeShown))
L ("  container_while_ime=" + (Container "u11_40_te"))
CloseIme "B" | Out-Null
L ("  ToTop=" + (ToTop "u11_41"))
$b2=Traverse2 "u11_42" 20
$F=$b2.rows
L ("  |F|=" + $F.Count + " steps=" + $b2.steps + " hitCap=" + $b2.hitCap + " viewport=" + $b2.viewport)
L ("  F=" + (Names $F))
$F | Out-File -FilePath "$d\_u11_F_names.txt" -Encoding utf8
L ("  ClearButton_present=" + $(if((Find "u11_42_tr0" 'Clear filter')){'yes'}else{'no'}))
L ("  EditText_text=" + $(if((Find "u11_42_tr0" 'gradle')){'shows gradle'}else{'n/a'}))

L "--- STEP C: Clear filter (x) -> C ---"
TapFind "u11_50" 'Clear filter' | Out-Null
Start-Sleep -Seconds 2
CloseIme "C" | Out-Null
L ("  ToTop=" + (ToTop "u11_51"))
$c2=Traverse2 "u11_52" 45
$C=$c2.rows
L ("  |C|=" + $C.Count + " steps=" + $c2.steps + " hitCap=" + $c2.hitCap + " viewport=" + $c2.viewport)
L ("  container_after_clear=" + (Container "u11_52_tr0"))
L ("  ClearButton_after_clear=" + $(if((Find "u11_52_tr0" 'Clear filter')){'STILL_PRESENT'}else{'gone'}))
$C | Out-File -FilePath "$d\_u11_C_names.txt" -Encoding utf8

L "--- STEP D: Refresh -> R ---"
CloseIme "D" | Out-Null
TapFind "u11_60" 'Refresh' | Out-Null
Start-Sleep -Seconds 6
CloseIme "D2" | Out-Null
L ("  ToTop=" + (ToTop "u11_61"))
$r2=Traverse2 "u11_62" 45
$R=$r2.rows
L ("  |R|=" + $R.Count + " steps=" + $r2.steps + " hitCap=" + $r2.hitCap + " viewport=" + $r2.viewport)
$R | Out-File -FilePath "$d\_u11_R_names.txt" -Encoding utf8

L "--- ASSERTIONS ---"
$ca=SetEq $C $ALL; $ra=SetEq $R $ALL; $rf=SetEq $R $F; $fs=SetEq $F $ALL
L ("  C_setEquals_ALL=" + $ca)
L ("  R_setEquals_ALL=" + $ra)
L ("  R_setEquals_F=" + $rf)
L ("  F_setEquals_ALL(should be FALSE)=" + $fs)
if(-not $ca){ L ("  DIFF C-ALL: " + ((Compare-Object @($C|Sort-Object) @($ALL|Sort-Object) | ForEach-Object { "$($_.SideIndicator)$($_.InputObject)" }) -join ' ')) }
if(-not $ra){ L ("  DIFF R-ALL: " + ((Compare-Object @($R|Sort-Object) @($ALL|Sort-Object) | ForEach-Object { "$($_.SideIndicator)$($_.InputObject)" }) -join ' ')) }
L ("  FIRST_ALL=" + $(if($ALL.Count){$ALL[0]}else{'n/a'}))
L ("  FIRST_F=" + $(if($F.Count){$F[0]}else{'n/a'}))
L ("  FIRST_C=" + $(if($C.Count){$C[0]}else{'n/a'}))
L ("  FIRST_R=" + $(if($R.Count){$R[0]}else{'n/a'}))
L ("  VERDICT=" + $(if($ca -and $ra -and -not $rf){"PASS"}else{"CHECK"}))
$log | Out-File -FilePath "$d\_u11_sets.txt" -Encoding utf8
L "DONE"
