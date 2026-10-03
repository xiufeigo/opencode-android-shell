$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$S="emulator-5562"
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98"

# Dump: uiautomator dump --compressed, pulled (NO exec-out), /sdcard temp removed right after
function Dump([string]$n){
  foreach($t in 1..5){
    & $adb -s $S shell uiautomator dump --compressed /sdcard/u11.xml | Out-Null
    if(Test-Path "$d\$n.xml"){ Remove-Item "$d\$n.xml" -Force }
    & $adb -s $S pull /sdcard/u11.xml "$d\$n.xml" 2>$null | Out-Null
    & $adb -s $S shell rm -f /sdcard/u11.xml 2>$null | Out-Null
    if(Test-Path "$d\$n.xml"){ return $true }
    Start-Sleep -Milliseconds 800
  }
  return $false
}
# Shot: screencap -p + adb pull only (NO exec-out)
function Shot([string]$n){
  & $adb -s $S shell screencap -p /sdcard/u11p.png | Out-Null
  if(Test-Path "$d\$n.png"){ Remove-Item "$d\$n.png" -Force }
  & $adb -s $S pull /sdcard/u11p.png "$d\$n.png" 2>$null | Out-Null
  & $adb -s $S shell rm -f /sdcard/u11p.png 2>$null | Out-Null
}
# Find: matches BOTH text="..." and text='...'
function Find([string]$n,[string]$pat){
  $f="$d\$n.xml"
  if(-not (Test-Path $f)){ return "NOTFOUND-DUMP" }
  $x=Get-Content $f -Raw
  foreach($m in [regex]::Matches($x,'<node\b[^>]*>')){
    $ns=$m.Value; $t=$null
    foreach($a in @('text','content-desc')){
      $g=[regex]::Match($ns,"$a=`"([^`"]*)`"")
      if($g.Success -and $g.Groups[1].Value.Trim()){ $t=$g.Groups[1].Value; break }
      $g2=[regex]::Match($ns,"$a='([^']*)'")
      if($g2.Success -and $g2.Groups[1].Value.Trim()){ $t=$g2.Groups[1].Value; break }
    }
    if($null -eq $t){ continue }
    if($t -notmatch $pat){ continue }
    $b=[regex]::Match($ns,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if(-not $b.Success){ continue }
    "{0}|[{1},{2}][{3},{4}]|c={5},{6}" -f $t,$b.Groups[1].Value,$b.Groups[2].Value,$b.Groups[3].Value,$b.Groups[4].Value,[int](([int]$b.Groups[1].Value+[int]$b.Groups[3].Value)/2),[int](([int]$b.Groups[2].Value+[int]$b.Groups[4].Value)/2)
  }
}
# ImeShown: the hard IME gate for this round
function ImeShown(){
  $r = & $adb -s $S shell dumpsys input_method 2>$null | Select-String "mInputShown"
  if($r -match 'mInputShown=(true|false)'){ return $Matches[1] }
  return "unknown"
}
# Rows: the panel file-tree row labels only. Anchors:
#  - must live inside the SCROLLABLE container (x >= list left, y within container)
#  - row nodes are CLICKABLE View nodes with a text label
function Rows([string]$n){
  $f="$d\$n.xml"
  if(-not (Test-Path $f)){ return @() }
  $x=Get-Content $f -Raw
  $out=@()
  foreach($m in [regex]::Matches($x,'<node\b[^>]*>')){
    $ns=$m.Value
    $b=[regex]::Match($ns,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if(-not $b.Success){ continue }
    $x1=[int]$b.Groups[1].Value; $y1=[int]$b.Groups[2].Value
    $x2=[int]$b.Groups[3].Value; $y2=[int]$b.Groups[4].Value
    if($y1 -lt 280 -or $y2 -gt 725){ continue }          # below filter field, above nav bar
    if($x1 -lt 1000){ continue }                         # right-hand panel only
    $t=$null
    foreach($a in @('text','content-desc')){
      $g=[regex]::Match($ns,"$a=`"([^`"]*)`"")
      if($g.Success -and $g.Groups[1].Value.Trim()){ $t=$g.Groups[1].Value; break }
      $g2=[regex]::Match($ns,"$a='([^']*)'")
      if($g2.Success -and $g2.Groups[1].Value.Trim()){ $t=$g2.Groups[1].Value; break }
    }
    if($null -eq $t){ continue }
    $out += [pscustomobject]@{ t=$t; x1=$x1; y1=$y1; x2=$x2; y2=$y2; cy=[int](($y1+$y2)/2) }
  }
  $out | Sort-Object y1
}
# Container: the scrollable list node bounds (proves viewport height)
function Container([string]$n){
  $f="$d\$n.xml"
  if(-not (Test-Path $f)){ return "NOTFOUND" }
  $x=Get-Content $f -Raw
  foreach($m in [regex]::Matches($x,'<node\b[^>]*scrollable="true"[^>]*>')){
    $b=[regex]::Match($m.Value,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if($b.Success -and [int]$b.Groups[1].Value -ge 1000){
      return "scrollable bounds=[{0},{1}][{2},{3}] height={4}" -f $b.Groups[1].Value,$b.Groups[2].Value,$b.Groups[3].Value,$b.Groups[4].Value,([int]$b.Groups[4].Value-[int]$b.Groups[2].Value)
    }
  }
  return "NO-SCROLLABLE-RIGHT"
}
# ContainerBounds -> hashtable with x1,y1,x2,y2
function CBounds([string]$n){
  $f="$d\$n.xml"
  if(-not (Test-Path $f)){ return $null }
  $x=Get-Content $f -Raw
  foreach($m in [regex]::Matches($x,'<node\b[^>]*scrollable="true"[^>]*>')){
    $b=[regex]::Match($m.Value,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if($b.Success -and [int]$b.Groups[1].Value -ge 1000){
      return @{x1=[int]$b.Groups[1].Value;y1=[int]$b.Groups[2].Value;x2=[int]$b.Groups[3].Value;y2=[int]$b.Groups[4].Value}
    }
  }
  # A short (filtered) list makes LazyColumn stop reporting scrollable="true".
  # Fall back to the panel list rect so row collection never silently returns empty.
  return @{x1=1140;y1=285;x2=1366;y2=722;fallback=$true}
}
# Rows: file-tree row labels, strictly inside the scrollable container, View+clickable only.
# This is the fix for the previous round's misread: the dump carries the underlying
# session window (composer "Send", etc.) as siblings, so bounding alone is not enough.
function Rows([string]$n){
  $f="$d\$n.xml"
  if(-not (Test-Path $f)){ return @() }
  $cb=CBounds $n
  if($null -eq $cb){ return @() }
  $x=Get-Content $f -Raw
  $out=@()
  foreach($m in [regex]::Matches($x,'<node\b[^>]*>')){
    $ns=$m.Value
    if($ns -notmatch 'class="android\.widget\.TextView"'){ continue }
    $b=[regex]::Match($ns,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if(-not $b.Success){ continue }
    $x1=[int]$b.Groups[1].Value; $y1=[int]$b.Groups[2].Value
    $x2=[int]$b.Groups[3].Value; $y2=[int]$b.Groups[4].Value
    if($x1 -lt $cb.x1 -or $x2 -gt $cb.x2){ continue }
    if($y1 -lt $cb.y1 -or $y2 -gt $cb.y2){ continue }
    $t=$null
    foreach($a in @('text','content-desc')){
      $g=[regex]::Match($ns,"$a=`"([^`"]*)`"")
      if($g.Success -and $g.Groups[1].Value.Trim()){ $t=$g.Groups[1].Value; break }
      $g2=[regex]::Match($ns,"$a='([^']*)'")
      if($g2.Success -and $g2.Groups[1].Value.Trim()){ $t=$g2.Groups[1].Value; break }
    }
    if($null -eq $t){ continue }
    $out += [pscustomobject]@{ t=$t; x1=$x1; y1=$y1; x2=$x2; y2=$y2; cy=[int](($y1+$y2)/2) }
  }
  $out | Sort-Object y1
}
# Traverse: fast-swipe the panel list top->bottom, union the row labels.
# Returns @{ rows = <ordered unique names>; steps = <count>; viewport = <max container height> }
function Traverse([string]$tag,[int]$maxSteps=14){
  $seen=New-Object System.Collections.Generic.List[string]
  $lastSig=""
  $stable=0
  $steps=0
  $vp=0
  for($i=0;$i -lt $maxSteps;$i++){
    $n="$tag`_tr$i"
    if(-not (Dump $n)){ continue }
    $cb=CBounds $n
    if($null -ne $cb){ $h=$cb.y2-$cb.y1; if($h -gt $vp){ $vp=$h } }
    $r=Rows $n
    $sig=($r | ForEach-Object { $_.t }) -join ','
    foreach($row in $r){ if(-not $seen.Contains($row.t)){ $seen.Add($row.t) } }
    if($sig -eq $lastSig){ $stable++ } else { $stable=0 }
    if($stable -ge 2){ break }
    $lastSig=$sig
    # fast swipe up ~100px inside the container
    $cx=[int](($cb.x1+$cb.x2)/2)
    $yTop=$cb.y1+60; $yBot=$cb.y2-60
    & $adb -s $S shell input swipe $cx $yBot $cx ($yBot-100) 80 | Out-Null
    Start-Sleep -Milliseconds 350
    $steps++
  }
  return @{ rows=$seen; steps=$steps; viewport=$vp }
}
# Top: scroll the list back to the very top (deterministic precondition for every set)
function ToTop([string]$tag){
  if(-not (Dump "${tag}_top0")){ return "NO-DUMP" }
  $cb=CBounds "${tag}_top0"
  if($null -eq $cb){ return "NO-CONTAINER" }
  $cx=[int](($cb.x1+$cb.x2)/2)
  for($i=0;$i -lt 45;$i++){
    & $adb -s $S shell input swipe $cx ($cb.y1+60) $cx ($cb.y2-60) 60 | Out-Null
    Start-Sleep -Milliseconds 90
  }
  Start-Sleep -Milliseconds 500
  return "ok"
}
function Names($rows){ return (($rows | ForEach-Object { $_ }) -join " | ") }
