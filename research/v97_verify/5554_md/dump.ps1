# Dump + parse helper for OpenCode Android Shell queue verification
# Usage: pwsh -File dump.ps1 <tag> [filterRegex]
param(
  [string]$Tag = "dump",
  [string]$Filter = ""
)
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d   = "emulator-5554"
$ev  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\evidence"
$out = "$ev\$Tag.xml"

& $adb -s $d shell uiautomator dump --compressed /sdcard/$Tag.xml 2>&1 | Out-Null
& $adb -s $d pull /sdcard/$Tag.xml $out 2>&1 | Out-Null

[xml]$x = Get-Content $out -Raw -Encoding UTF8
$nodes = $x.SelectNodes("//node")

# Parse bounds -> center
function Get-Center($b) {
  if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $l=[int]$matches[1]; $t=[int]$matches[2]; $r=[int]$matches[3]; $bo=[int]$matches[4]
    return [pscustomobject]@{ cx=[int](($l+$r)/2); cy=[int](($t+$bo)/2);
                              l=$l; t=$t; r=$r; b=$bo; w=($r-$l); h=($bo-$t) }
  }
  return $null
}

$rows = @()
foreach ($n in $nodes) {
  $t  = $n.GetAttribute("text")
  $cd = $n.GetAttribute("content-desc")
  $cl = $n.GetAttribute("class")
  $bd = $n.GetAttribute("bounds")
  if ([string]::IsNullOrWhiteSpace($t) -and [string]::IsNullOrWhiteSpace($cd)) { continue }
  $c = Get-Center $bd
  if ($null -eq $c) { continue }
  $rows += [pscustomobject]@{
    text  = $t
    desc  = $cd
    cls   = ($cl -replace '.*\.','')
    click = $n.GetAttribute("clickable")
    focus = $n.GetAttribute("focused")
    sel   = $n.GetAttribute("selected")
    b     = $bd
    cx    = $c.cx; cy = $c.cy
    x=$c.l; y=$c.t; w=$c.w; h=$c.h
  }
}

if ($Filter -ne "") { $rows = $rows | Where-Object { ($_.text + "|" + $_.desc) -match $Filter } }

$rows | Sort-Object { [int]$_.y }, { [int]$_.x } | Format-Table `
  @{n='text';e={ if($_.text.Length -gt 46){$_.text.Substring(0,46)+'..'}else{$_.text} }},
  @{n='desc';e={ if($_.desc.Length -gt 30){$_.desc.Substring(0,30)}else{$_.desc} }},
  cls, click, focus, cx, cy, b -AutoSize

"--- total nodes with text/desc: $($rows.Count) ---"
