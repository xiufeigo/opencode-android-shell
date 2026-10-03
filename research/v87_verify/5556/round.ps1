param(
  [int]$Round,
  [string]$Mode = 'edit'
)
$ErrorActionPreference = 'Continue'
$adb = "C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$S = 'emulator-5556'
$d = "C:\sensorsdata\main\program\opencode-android-shell\research\v87_verify\5556"
$xml = "$d\ui_r$Round.xml"
$png = "$d\r{0}_{1}_kbd.png" -f $Round, $Mode

function Get-Nodes {
  & $adb -s $S shell uiautomator dump /sdcard/ui.xml | Out-Null
  & $adb -s $S pull /sdcard/ui.xml $xml | Out-Null
  $c = Get-Content $xml -Raw
  $res = @()
  foreach ($n in [regex]::Matches($c, '<node[^>]*>')) {
    $s = $n.Value
    $t = ''; if ($s -match 'text="([^"]*)"') { $t = $Matches[1] }
    $cd = ''; if ($s -match 'content-desc="([^"]*)"') { $cd = $Matches[1] }
    $lb = if ($t.Trim() -ne '') { $t } else { $cd }
    $cls = ''; if ($s -match 'class="([^"]*)"') { $cls = $Matches[1] }
    if ($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
      $bx1 = [int]$Matches[1]; $by1 = [int]$Matches[2]; $bx2 = [int]$Matches[3]; $by2 = [int]$Matches[4]
      $res += [pscustomobject]@{
        Label = $lb; Cls = $cls
        X1 = $bx1; Y1 = $by1; X2 = $bx2; Y2 = $by2
        CX = [int](($bx1 + $bx2)/2); CY = [int](($by1 + $by2)/2)
      }
    }
  }
  return $res
}
function TapLabel([string]$pat, [int]$skip = 0) {
  $n = Get-Nodes | Where-Object { $_.Label -match $pat }
  if ($n.Count -le $skip) { "  !! no node for '$pat'"; return $null }
  $h = $n[$skip]
  & $adb -s $S shell input tap $h.CX $h.CY
  Start-Sleep -Milliseconds 1800
  "  tap '$($h.Label)' [$($h.X1),$($h.Y1)-$($h.X2),$($h.Y2)] -> $($h.CX),$($h.CY)"
  return $h
}
function FirstEditTextCenter {
  $n = Get-Nodes | Where-Object { $_.Cls -like '*EditText*' -and $_.X1 -gt 430 -and $_.X1 -lt 900 -and $_.Y1 -gt 300 }
  if ($n.Count -eq 0) { return $null }
  return $n[0]
}

"=== ROUND $Round ($Mode) ==="
if ($Mode -eq 'edit') {
  $null = TapLabel 'Server actions'
  $null = TapLabel '^Edit$'
} else {
  $null = TapLabel 'Add SSH server'
}
Start-Sleep -Milliseconds 1200

# raise keyboard: tap first form field (keyboard-down position)
$f = FirstEditTextCenter
if ($f -eq $null) { "  !! no form field found"; exit 1 }
& $adb -s $S shell input tap $f.CX $f.CY
Start-Sleep -Seconds 3
"  field tapped $($f.CX),$($f.CY)"

cmd /c "`"$adb`" -s $S exec-out screencap -p > `"$png`""
Start-Sleep -Milliseconds 400

# measure
$lines = & powershell -NoProfile -File "$d\cardbox.ps1" -Path $png
$lines | ForEach-Object { "  $_" }

# a11y footer + field
$nn = Get-Nodes
$nn | Where-Object { $_.Label -match 'Cancel|Save|Add server|Server address|Host or SSH' } | ForEach-Object { "  A11Y: $($_.Label) [$($_.X1),$($_.Y1)-$($_.X2),$($_.Y2)]" }
($nn | Where-Object { $_.Cls -like '*EditText*' -and $_.X1 -gt 430 -and $_.X1 -lt 900 } | ForEach-Object { "  A11Y-EDIT: $($_.Label) [$($_.X1),$($_.Y1)-$($_.X2),$($_.Y2)]" })

# cancel
$null = TapLabel '^Cancel$'
Start-Sleep -Seconds 1
"=== ROUND $Round done ==="
