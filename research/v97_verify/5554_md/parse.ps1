# Generic dump+parse: prints every node carrying text or content-desc, sorted by y then x.
# Long strings are truncated for readability. Run: pwsh -NoProfile -File parse.ps1 -Tag <name>
param([string]$Tag = "p", [int]$YMin = -1, [int]$YMax = 99999)
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d   = "emulator-5554"
$ev  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\evidence"

& $adb -s $d shell uiautomator dump --compressed /sdcard/$Tag.xml 2>&1 | Out-Null
& $adb -s $d pull /sdcard/$Tag.xml "$ev\$Tag.xml" 2>&1 | Out-Null
$raw = Get-Content "$ev\$Tag.xml" -Raw -Encoding UTF8

$ime = (& $adb -s $d shell dumpsys input_method 2>$null | Select-String "mInputShown" | Select-Object -First 1)
"### IME: $ime"

$out = @()
foreach ($m in [regex]::Matches($raw, '<node[^>]*>')) {
  $n = $m.Value
  $b = [regex]::Match($n, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $b.Success) { continue }
  $l=[int]$b.Groups[1].Value; $t=[int]$b.Groups[2].Value
  $r=[int]$b.Groups[3].Value; $bt=[int]$b.Groups[4].Value
  if ($t -lt $YMin -or $t -gt $YMax) { continue }
  $tx=[regex]::Match($n,'text="([^"]*)"').Groups[1].Value
  $cd=[regex]::Match($n,'content-desc="([^"]*)"').Groups[1].Value
  $cl=[regex]::Match($n,'class="([^"]*)"').Groups[1].Value -replace '.*\.',''
  $ck=[regex]::Match($n,'clickable="([^"]*)"').Groups[1].Value
  $fo=[regex]::Match($n,'focused="([^"]*)"').Groups[1].Value
  if ([string]::IsNullOrWhiteSpace($tx) -and [string]::IsNullOrWhiteSpace($cd)) { continue }
  # skip the right-edge suggestion chip rail (x >= 1310 and tiny height) to cut noise
  if ($l -ge 1310 -and ($bt-$t) -le 6) { continue }
  if ($tx.Length -gt 42) { $tx = $tx.Substring(0,42)+'~' }
  if ($cd.Length -gt 42) { $cd = $cd.Substring(0,42)+'~' }
  $cx=[int](($l+$r)/2); $cy=[int](($t+$bt)/2)
  $out += [pscustomobject]@{
    b="$l,$t-$r,$bt"; yy=$t; cx=$cx; cy=$cy; cls=$cl; click=$ck; foc=$fo; text=$tx; desc=$cd
  }
}
$out | Sort-Object yy, cx | Format-Table b, cx, cy, cls, click, foc, text, desc -AutoSize
"### nodes: $($out.Count)"
