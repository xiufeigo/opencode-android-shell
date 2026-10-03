param(
  [Parameter(Mandatory=$true)][string]$Tag,   # output name, e.g. 05_add_kbd
  [string]$Mode = 'add',                      # add | edit
  [int]$ExtraTapX = 0, [int]$ExtraTapY = 0
)
$ErrorActionPreference = 'Continue'
$adb = "C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$S = 'emulator-5556'
$d = "C:\sensorsdata\main\program\opencode-android-shell\research\v88_verify\5556"
$png = "$d\$Tag.png"

function Get-Nodes {
  & $adb -s $S shell uiautomator dump /sdcard/ui.xml | Out-Null
  & $adb -s $S pull /sdcard/ui.xml "$d\_t.xml" | Out-Null
  $c = Get-Content "$d\_t.xml" -Raw
  $res = @()
  foreach ($n in [regex]::Matches($c, '<node[^>]*>')) {
    $s = $n.Value
    $t = ''; if ($s -match 'text="([^"]*)"') { $t = $Matches[1] }
    $cd = ''; if ($s -match 'content-desc="([^"]*)"') { $cd = $Matches[1] }
    $lb = if ($t.Trim() -ne '') { $t } else { $cd }
    $cls = ''; if ($s -match 'class="([^"]*)"') { $cls = $Matches[1] }
    if ($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
      $res += [pscustomobject]@{
        Label = $lb; Cls = $cls
        X1 = [int]$Matches[1]; Y1 = [int]$Matches[2]; X2 = [int]$Matches[3]; Y2 = [int]$Matches[4]
        CX = [int](([int]$Matches[1] + [int]$Matches[3])/2); CY = [int](([int]$Matches[2] + [int]$Matches[4])/2)
      }
    }
  }
  return $res
}
function TapL([string]$pat, [int]$skip = 0) {
  $n = @(Get-Nodes | Where-Object { $_.Label -match $pat })
  if ($n.Count -le $skip) { "  !! no node '$pat'"; return $null }
  $h = $n[$skip]
  & $adb -s $S shell input tap $h.CX $h.CY
  Start-Sleep -Milliseconds 1600
  "  tap '$($h.Label)' -> $($h.CX),$($h.CY)"
  return $h
}

"=== $Tag ($Mode) ==="
if ($Mode -eq 'add') { $null = TapL 'Add SSH server' }
else { $null = TapL 'Server actions'; $null = TapL '^Edit$' }
Start-Sleep -Milliseconds 1000

# raise keyboard on first form field
$f = @(Get-Nodes | Where-Object { $_.Cls -like '*EditText*' -and $_.X1 -gt 440 -and $_.X1 -lt 900 })[0]
if ($f -eq $null) { "  !! no field"; exit 1 }
& $adb -s $S shell input tap $f.CX $f.CY
Start-Sleep -Seconds 3
"  kbd via field $($f.CX),$($f.CY)"

cmd /c "`"$adb`" -s $S exec-out screencap -p > `"$png`""
Start-Sleep -Milliseconds 400

# ---- geometry: single column scan at card mid x=700 ----
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile($png)
$segs = @(); $prev = $null; $st = 30
for ($y = 30; $y -le 360; $y++) {
  $c = $bmp.GetPixel(700, $y)
  $k = "{0:X2}{1:X2}{2:X2}" -f $c.R, $c.G, $c.B
  if ($k -ne $prev) {
    if ($prev -ne $null -and ($y - $st) -ge 1) { $segs += "  y=$st..$($y-1) #$prev" }
    $prev = $k; $st = $y
  }
}
$segs | ForEach-Object { $_ }
# card box: border color 363B45 / 4A4F58 rows, panel fill 1F2530
$cardTop = $null; $cardBot = $null
for ($y = 30; $y -le 360; $y++) {
  $c = $bmp.GetPixel(700, $y); $k = "{0:X2}{1:X2}{2:X2}" -f $c.R, $c.G, $c.B
  if ($k -eq '363B45' -or $k -eq '4A4F58') {
    if ($cardTop -eq $null) { $cardTop = $y }
    $cardBot = $y
  }
}
$kbTop = $null
for ($y = 30; $y -le 500; $y++) {
  $c = $bmp.GetPixel(1250, $y)
  if ($c.R -gt 200 -and $c.G -gt 200 -and $c.B -gt 200) { $kbTop = $y; break }
}
"CARD_TOP=$cardTop CARD_BOT=$cardBot CARD_H=$(if($cardTop -ne $null){$cardBot-$cardTop+1})  KBD_TOP=$kbTop  GAP=$(if($kbTop -ne $null -and $cardBot -ne $null){$kbTop-$cardBot-1})"
$bmp.Dispose()

$nn = @(Get-Nodes)
"  A11Y: " + (($nn | Where-Object { $_.Label -match 'Cancel|Save|Add server|Edit server|Server address|Host or SSH' } | ForEach-Object { "$($_.Label.Trim())[$($_.X1),$($_.Y1)-$($_.X2),$($_.Y2)]" }) -join ' | ')
$ef = @($nn | Where-Object { $_.Cls -like '*EditText*' -and $_.X1 -gt 440 -and $_.X1 -lt 900 })
"  FIELDS: $($ef.Count) -> " + (($ef | ForEach-Object { "[$($_.X1),$($_.Y1)-$($_.X2),$($_.Y2)]" }) -join ' ')
