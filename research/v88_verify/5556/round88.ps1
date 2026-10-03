param([int]$Round, [string]$Mode = 'edit')
$ErrorActionPreference = 'Continue'
$adb = "C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$S = 'emulator-5556'
$d = "C:\sensorsdata\main\program\opencode-android-shell\research\v88_verify\5556"
$png = "$d\r${Round}_${Mode}_kbd.png"

function Get-Nodes {
  & $adb -s $S shell uiautomator dump /sdcard/ui.xml | Out-Null
  & $adb -s $S pull /sdcard/ui.xml "$d\_r.xml" | Out-Null
  $c = Get-Content "$d\_r.xml" -Raw
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
  Start-Sleep -Milliseconds 1700
  return $h
}
function Measure($p) {
  Add-Type -AssemblyName System.Drawing
  $bmp = [System.Drawing.Bitmap]::FromFile($p)
  $bt = $null; $bb = $null; $div = @()
  for ($y = 30; $y -le 360; $y++) {
    $c = $bmp.GetPixel(700, $y); $k = "{0:X2}{1:X2}{2:X2}" -f $c.R, $c.G, $c.B
    if ($k -eq '363B45' -or $k -eq '4A4F58') { if ($bt -eq $null) { $bt = $y }; $bb = $y; $div += $y }
  }
  $kb = $null
  for ($y = 30; $y -le 500; $y++) { $c = $bmp.GetPixel(1250, $y); if ($c.R -gt 200 -and $c.G -gt 200 -and $c.B -gt 200) { $kb = $y; break } }
  $bmp.Dispose()
  # borders excluding first (panel top) and last (card bottom)
  $cardTop = if ($div.Count -ge 2) { $div[1] } else { $null }
  $cardBot = $bb
  "  CARD y=$cardTop..$cardBot h=$(if($cardTop -ne $null){$cardBot-$cardTop+1})  KBD=$kb GAP=$(if($kb -ne $null){$kb-$cardBot-1})  dividers=$($div -join ',')"
}

"=== ROUND $Round ($Mode) ==="
if ($Mode -eq 'add') { $null = TapL 'Add SSH server' }
else { $null = TapL 'Server actions'; $null = TapL '^Edit$' }
Start-Sleep -Milliseconds 1200

$f = @(Get-Nodes | Where-Object { $_.Cls -like '*EditText*' -and $_.X1 -gt 440 -and $_.X1 -lt 900 })[0]
if ($f -eq $null) { "  !! no field"; exit 1 }
& $adb -s $S shell input tap $f.CX $f.CY
Start-Sleep -Seconds 3
"  kbd up via $($f.CX),$($f.CY)"

cmd /c "`"$adb`" -s $S exec-out screencap -p > `"$png`""
Start-Sleep -Milliseconds 400
Measure $png

$nn = @(Get-Nodes)
"  A11Y: " + (($nn | Where-Object { $_.Label -match '^Cancel$|^Save$|^Add server$' } | ForEach-Object { "$($_.Label.Trim())[$($_.X1),$($_.Y1)-$($_.X2),$($_.Y2)]" }) -join ' | ')
$ef = @($nn | Where-Object { $_.Cls -like '*EditText*' -and $_.X1 -gt 440 -and $_.X1 -lt 900 })
"  FIELDS: $($ef.Count) -> " + (($ef | ForEach-Object { "[$($_.X1),$($_.Y1)-$($_.X2),$($_.Y2)]" }) -join ' ')

# close with Cancel (never BACK)
$cn = @(Get-Nodes | Where-Object { $_.Label -match '^Cancel$' })[0]
if ($cn) { & $adb -s $S shell input tap $cn.CX $cn.CY; Start-Sleep -Seconds 2; "  cancelled via $($cn.CX),$($cn.CY)" }
else { "  !! no Cancel" }
"=== ROUND $Round done ==="
