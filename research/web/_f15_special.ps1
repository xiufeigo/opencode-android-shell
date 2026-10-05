# F15 item3 supplement: isolate the special char '&' by CLEARING the composer
# first, so the readback can be compared to the bare intended string.
# This removes the F14 script's artifact (it never cleared between the two
# TypeText calls, so readback was "previous + new" and full-equality failed).
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S="emulator-6370"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_f14"
$L="$OUT\_f15_special.txt"
if(Test-Path $L){Remove-Item $L -Force}
function L($m){ $s="[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"),$m; Add-Content $L $s -Encoding utf8; Write-Host $s }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Nodes($d){
  foreach($m in [regex]::Matches($d,'<node[^>]*>')){
    $n=$m.Value
    $text=([regex]::Match($n,'text="([^"]*)"')).Groups[1].Value
    $cls =([regex]::Match($n,'class="([^"]*)"')).Groups[1].Value
    $b   =([regex]::Match($n,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'))
    if($b.Success){
      $x1=[int]$b.Groups[1].Value;$y1=[int]$b.Groups[2].Value;$x2=[int]$b.Groups[3].Value;$y2=[int]$b.Groups[4].Value
      [pscustomobject]@{text=$text;cls=$cls;x1=$x1;y1=$y1;x2=$x2;y2=$y2;cx=[int](($x1+$x2)/2);cy=[int](($y1+$y2)/2)}
    }
  }
}
function ComposerText(){
  Sh "uiautomator dump /sdcard/sp.xml" | Out-Null; Start-Sleep -Milliseconds 450
  $d = Sh "cat /sdcard/sp.xml"
  $n = Nodes $d
  $et = $n | Where-Object { $_.cls -match 'EditText' -and $_.y1 -ge 900 } | Select-Object -First 1
  if(-not $et){ $et = $n | Where-Object { $_.cls -match 'EditText' } | Select-Object -First 1 }
  if($et){ return $et.text } else { return "" }
}
function ClearComposer(){
  Sh "input keyevent -t 1000 123" | Out-Null          # select all
  Sh "input keyevent KEYCODE_MOVE_END" | Out-Null
  for($i=0;$i -lt 70;$i++){ Sh "input keyevent 67" | Out-Null }   # 70x backspace
}
function TypeText($raw){
  $esc=$raw.Replace("'","'\''")
  $cmd="input text '$esc'"
  L "TYPE_CMD=adb -s $S shell $cmd"
  Sh $cmd | Out-Null; Start-Sleep -Milliseconds 800
}

L "F15_SPECIAL_ISOLATED_START $(Get-Date -Format o)"
Sh "input keyevent 111" | Out-Null                    # ESC closes IME
Start-Sleep -Milliseconds 800
# re-find composer and tap it for real so the field is focused
Sh "uiautomator dump /sdcard/sp0.xml" | Out-Null; Start-Sleep -Milliseconds 400
$n0 = Nodes (Sh "cat /sdcard/sp0.xml")
$comp = $n0 | Where-Object { $_.cls -match 'EditText' -and $_.y1 -ge 900 } | Select-Object -First 1
if(-not $comp){ $comp = $n0 | Where-Object { $_.cls -match 'EditText' } | Select-Object -First 1 }
if($comp){
  L "COMPOSER bounds=[$($comp.x1),$($comp.y1)][$($comp.x2),$($comp.y2)] center=($($comp.cx),$($comp.cy))"
  L "FOCUS_TAP=REAL_UI_TAP"
  Sh "input tap $($comp.cx) $($comp.cy)" | Out-Null; Start-Sleep -Milliseconds 1200
} else { L "COMPOSER=NOT_FOUND" }

ClearComposer
$empty = ComposerText
L "AFTER_CLEAR readback=[$empty]  isEmpty=$([string]::IsNullOrEmpty($empty))"

# ---- TEST 1: bare special char '&' ----
$intended = 'a&b'
L "SPECIAL_INTENDED=[$intended]"
TypeText $intended
$got = ComposerText
L "SPECIAL_READBACK=[$got]"
L "SPECIAL_RAW_ENTITIES_ARE_XML_ESCAPED: '&amp;' in dump == literal '&' on screen"
L "SPECIAL_EQUALS_INTENDED=$($got -eq $intended)"

# ---- TEST 2: spaces + '&' together, still on a cleared field ----
ClearComposer
$intended2 = 'x&y z w'
L "MIXED_INTENDED=[$intended2]"
TypeText ($intended2 -replace ' ','%s')
$got2 = ComposerText
L "MIXED_READBACK=[$got2]"
L "MIXED_EQUALS_INTENDED=$($got2 -eq $intended2)"

ClearComposer
L "F15_SPECIAL_ISOLATED_END"
