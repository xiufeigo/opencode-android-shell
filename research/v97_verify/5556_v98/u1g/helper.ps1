# u1g helper: dump + pull + parse, focus gate, reading helpers
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev = "emulator-5556"
$pkg = "com.xiufeigo.opencodeshell"
$ev  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1g"
$PFX = "u1g_"

function A { & $adb -s $dev @args }

# ---- Focus / liveness gate ----
function Get-Focus {
  (A shell dumpsys window | Select-String 'mCurrentFocus' | Select-Object -First 1).Line
}
function Get-InputShown {
  $l = (A shell dumpsys window | Select-String 'mInputShown' | Select-Object -First 1)
  if ($l) { $l.Line } else { "mInputShown=<not found>" }
}
function Get-Pidof { (A shell pidof $pkg) }

function Assert-Focus([string]$where) {
  $f = Get-Focus
  if ($f -match 'opencodeshell') { Write-Output "FOCUS-OK[$where]: $f" ; return $true }
  Write-Output "FOCUS-FAIL[$where]: $f"
  return $false
}

# ---- dump: on-device compressed, then pull ----
function Dump([string]$tag) {
  $remote = "/sdcard/$PFX$tag.xml"
  A shell "rm -f $remote" | Out-Null
  A shell "uiautomator dump --compressed $remote" 2>&1 | Out-Null
  A pull $remote "$ev\$PFX$tag.xml" 2>&1 | Out-Null
  A shell "rm -f $remote" | Out-Null
  $p = "$ev\$PFX$tag.xml"
  if (Test-Path $p) { return $p } else { return $null }
}

function Shot([string]$tag) {
  $remote = "/sdcard/$PFX$tag.png"
  A shell "rm -f $remote" | Out-Null
  A shell "screencap -p $remote" 2>&1 | Out-Null
  A pull $remote "$ev\$PFX$tag.png" 2>&1 | Out-Null
  A shell "rm -f $remote" | Out-Null
}

# ---- parse: match text="..." and text='...' ----
function Get-Nodes([string]$path) {
  if (-not (Test-Path $path)) { return @() }
  $raw = [IO.File]::ReadAllText($path)
  $out = New-Object System.Collections.Generic.List[object]
  $rx = [regex]'<node[^>]*>'
  foreach ($m in $rx.Matches($raw)) {
    $s = $m.Value
    $o = [ordered]@{}
    foreach ($k in 'text','content-desc','resource-id','class','bounds','clickable','scrollable','enabled','focused','package','index') {
      $mm = [regex]::Match($s, ($k + '="([^"]*)"'))
      if (-not $mm.Success) { $mm = [regex]::Match($s, ($k + "='([^']*)'")) }
      $o[$k] = if ($mm.Success) { $mm.Groups[1].Value } else { '' }
    }
    $b = $o['bounds']
    if ($b -match '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') {
      $o['x1']=[int]$Matches[1]; $o['y1']=[int]$Matches[2]
      $o['x2']=[int]$Matches[3]; $o['y2']=[int]$Matches[4]
      $o['w']=$o['x2']-$o['x1']; $o['h']=$o['y2']-$o['y1']
    } else { $o['x1']=-1;$o['y1']=-1;$o['x2']=-1;$o['y2']=-1;$o['w']=-1;$o['h']=-1 }
    $out.Add([pscustomobject]$o)
  }
  return $out
}

function Show-Nodes($nodes, [string]$filter = '') {
  if ($filter -ne '') { $nodes = $nodes | Where-Object { $_.text -like $filter -or $_.'content-desc' -like $filter } }
  $nodes | Sort-Object y1, x1 | ForEach-Object {
    $label = if ($_.text -ne '') { $_.text } elseif ($_.'content-desc' -ne '') { $_.'content-desc' } else { '' }
    if ($label.Length -gt 34) { $label = $label.Substring(0,34) }
    "{0,-26} y={1,5}..{2,-5} h={3,4} x={4,5}..{5,-5} clk={6,-5} scr={7,-5} | {8}" -f `
      $_.class.Replace('android.widget.','').Replace('android.view.',''), $_.y1, $_.y2, $_.h, $_.x1, $_.x2, $_.clickable, $_.scrollable, $label
  }
}

# ---- Main criterion: pane vs composer host ----
function Read-Layout([string]$tag) {
  $p = Dump $tag
  if (-not $p) { Write-Output "DUMP FAILED for $tag"; return }
  $n = Get-Nodes $p
  # pane = topmost full-width content View below the header (y1>100, w>1000)
  $pane = $n | Where-Object { $_.w -gt 1000 -and $_.y1 -gt 100 } | Sort-Object y1 | Select-Object -First 1
  # composer subtree members
  $et  = $n | Where-Object { $_.class -eq 'android.widget.EditText' } | Select-Object -First 1
  $add = $n | Where-Object { $_.'content-desc' -eq 'Add' } | Select-Object -First 1
  $snd = $n | Where-Object { $_.'content-desc' -eq 'Send' } | Select-Object -First 1
  $cparts = @($et,$add,$snd) | Where-Object { $_ -ne $null }
  $hosts = @(); if($cparts.Count -gt 0){
    $hosts = $cparts | ForEach-Object {
      $m = $n | Where-Object { $_.y1 -ge $_.y1 -and $_.y1 -le $_.y2 -and $_.x1 -ge ($add.x1) -and $_.x1 -le ($add.x2) } | Out-Null
      $_
    }
  }
  $ims = (A shell dumpsys input_method | Select-String 'mInputShown' | Select-Object -First 1).Line
  $mfr = (A shell dumpsys window | Select-String 'InsetsSource id=3 type=ime frame' | Select-Object -First 1).Line
  Write-Output "----- $tag -----"
  Write-Output "mInputShown : $ims"
  Write-Output "IME mFrame  : $($mfr -replace '\s+',' ')"
  if($pane){
    Write-Output ("PANE        : bounds={0,-28} y={1}..{2}  h={3}  w={4}" -f $pane.bounds,$pane.y1,$pane.y2,$pane.h,$pane.w)
  } else { Write-Output "PANE        : <NOT FOUND>" }
  if($cparts.Count -gt 0){
    $hTop = ($cparts | Measure-Object y1 -Minimum).Minimum
    $hBot = ($cparts | Measure-Object y2 -Maximum).Maximum
    Write-Output ("HOST members: ET={0} Add={1} Send={2}" -f $(if($et){$et.bounds}else{'-'}), $(if($add){$add.bounds}else{'-'}), $(if($snd){$snd.bounds}else{'-'}))
    Write-Output ("HOST union  : y={0}..{1}  h={2}" -f $hTop,$hBot,($hBot-$hTop))
    if($pane){
      $diff = $hBot - $pane.y2
      $hostH = $hBot - $hTop
      $hostPaid = if($et){ $et.h } else { 0 }
      $readable = $pane.h - $hostPaid
      Write-Output ("COMPOSER BOTTOM : {0}" -f $hBot)
      Write-Output ("PANE BOTTOM     : {0}" -f $pane.y2)
      Write-Output ("DIFF (cBot-paneBot) = {0}  => {1}" -f $diff, $(if($diff -le 0){'PASS (<=0)'}else{'FAIL (>0) OVERFLOW'}))
      Write-Output ("HOST union h = {0} ; HOSTPAID (EditText h = 宿主高) = {1}" -f $hostH,$hostPaid)
      Write-Output ("readable = pane({0}) - hostPaid({1}) = {2}" -f $pane.h,$hostPaid,$readable)
    }
  } else { Write-Output "HOST union  : <composer NOT PRESENT - host height 0>" }
  # count composer nodes
  $cnt = ($n | Where-Object { $_.class -eq 'android.widget.EditText' -or $_.'content-desc' -in @('Add','Send') }).Count
  Write-Output "COMPOSER NODE COUNT (EditText/Add/Send) = $cnt"
  return $pane
}
