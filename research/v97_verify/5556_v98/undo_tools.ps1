# Undo runtime re-verification helpers (emulator-5556 only)
$global:ADB   = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$global:DEV   = "emulator-5556"
$global:OUT   = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"
$global:CREDS = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('opencode:opencode')) }

function Dump([string]$Tag) {
  & $global:ADB -s $global:DEV shell uiautomator dump --compressed /sdcard/$Tag.xml | Out-Null
  & $global:ADB -s $global:DEV pull /sdcard/$Tag.xml "$global:OUT\$Tag.xml" 2>&1 | Out-Null
  return "$global:OUT\$Tag.xml"
}

function Shot([string]$Tag) {
  & $global:ADB -s $global:DEV shell screencap -p /sdcard/$Tag.png
  & $global:ADB -s $global:DEV pull /sdcard/$Tag.png "$global:OUT\$Tag.png" 2>&1 | Out-Null
}

# Parse all nodes; matches both text="..." and text='...'
function Nodes([string]$XmlPath) {
  $raw = [IO.File]::ReadAllText($XmlPath)
  $list = @()
  $rx = [regex]'<node\b[^>]*>'
  foreach ($m in $rx.Matches($raw)) {
    $s = $m.Value
    $o = [ordered]@{}
    foreach ($pair in @('class','resource-id','text','content-desc','clickable','enabled','selected','bounds','index')) {
      $k = [regex]::Match($s, "$pair=`"(?<v>[^`"]*)`"")
      if (-not $k.Success) { $k = [regex]::Match($s, "$pair='(?<v>[^']*)'") }
      $o[$pair] = if ($k.Success) { $k.Groups['v'].Value } else { '' }
    }
    $b = [regex]::Match($o['bounds'], '\[(?<l>-?\d+),(?<t>-?\d+)\]\[(?<r>-?\d+),(?<b>-?\d+)\]')
    if ($b.Success) {
      $o['x1']=[int]$b.Groups['l'].Value; $o['y1']=[int]$b.Groups['t'].Value
      $o['x2']=[int]$b.Groups['r'].Value; $o['y2']=[int]$b.Groups['b'].Value
      $o['cx']=[int](($o['x1']+$o['x2'])/2); $o['cy']=[int](($o['y1']+$o['y2'])/2)
    }
    $list += [pscustomobject]$o
  }
  return $list
}

# Find nodes whose text OR content-desc matches regex; print compact
function Find([string]$XmlPath, [string]$Pat) {
  Nodes $XmlPath | Where-Object { $_.text -match $Pat -or $__.$null -or $_.'content-desc' -match $Pat } |
    Select-Object class, text, 'content-desc', clickable, enabled, bounds, cx, cy
}

# Find clickable row whose text/desc matches: walks to the clickable ancestor by geometry
function FindRow([string]$XmlPath, [string]$Label) {
  $all = Nodes $XmlPath
  $label = $all | Where-Object { $_.text -eq $Label -or $_.'content-desc' -eq $Label -or $_.text -like "*$Label*" } | Select-Object -First 1
  if (-not $label) { return $null }
  $cand = $all | Where-Object {
    $_.clickable -eq 'true' -and
    $_.x1 -le $label.x1 -and $_.x2 -ge $label.x2 -and
    $_.y1 -le $label.y1 -and $_.y2 -ge $label.y2
  } | Sort-Object { ($_.x2-$_.x1)*($_.y2-$_.y1) } | Select-Object -First 1
  if ($cand) { return $cand }
  return $label
}

# Tap the row for a label, coordinates resolved at runtime
function TapRow([string]$XmlPath, [string]$Label) {
  $r = FindRow $XmlPath $Label
  if (-not $r) { Write-Output "TAPROW MISS: $Label"; return $null }
  Write-Output ("TAPROW {0} -> ({1},{2}) clickable={3} bounds={4}" -f $Label, $r.cx, $r.cy, $r.clickable, $r.bounds)
  & $global:ADB -s $global:DEV shell input tap $r.cx $r.cy
  return $r
}

# Visible text lines, de-duplicated in document order
function Texts([string]$XmlPath) {
  (Nodes $XmlPath | Where-Object { $_.text -and $_.text.Trim() -ne '' } | ForEach-Object { $_.text }) -join ' | '
}

function GetApi([string]$Path) {
  try {
    $r = Invoke-WebRequest -Uri "http://127.0.0.1:4095$Path" -UseBasicParsing -TimeoutSec 25 -Headers $global:CREDS
    return ($r.Content | ConvertFrom-Json)
  } catch { Write-Output "API-ERR $Path :: $($_.Exception.Message)"; return $null }
}

# Backend ground truth: message count + type sequence + revert
function Backend([string]$SessionId, [string]$Label) {
  $msgs = GetApi "/api/session/$SessionId/message?order=asc&limit=80"
  $sess = GetApi "/api/session/$SessionId"
  $data = if ($msgs -and $msgs.data) { $msgs.data } else { @() }
  $types = ($data | ForEach-Object { $_.type }) -join ','
  $users = @($data | Where-Object { $_.type -eq 'user' })
  $rv = $sess.revert
  $rvStr = if ($null -eq $rv) { 'null' } else { ($rv | ConvertTo-Json -Compress) }
  $line = "[$Label] count=$($data.Count) users=$($users.Count) revert=$rvStr"
  Write-Output $line
  Write-Output "  types: $types"
  $data | ForEach-Object { $t=$_.text; if($t -and $t.Length -gt 60){$t=$t.Substring(0,60)+'...'}; Write-Output ("   {0,-20} {1}  {2}" -f $_.type, $_.id, $t) }
  return @{ count=$data.Count; users=$users; revert=$rvStr; data=$data }
}
