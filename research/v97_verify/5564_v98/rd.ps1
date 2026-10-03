param([string]$Tag = "d", [int]$WaitMs = 0)
$ErrorActionPreference = 'Continue'
$env:PATH = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools;" + $env:PATH
if ($WaitMs -gt 0) { Start-Sleep -Milliseconds $WaitMs }
$out = adb -s emulator-5564 shell "uiautomator dump --compressed /data/local/tmp/d.xml" 2>&1
Start-Sleep -Milliseconds 400
$raw = (adb -s emulator-5564 shell "cat /data/local/tmp/d.xml" 2>&1) -join ""
if ($raw.Length -lt 200) { $raw = (adb -s emulator-5564 shell "uiautomator dump /data/local/tmp/d2.xml" 2>&1) -join ""; Start-Sleep -Milliseconds 400; $raw = (adb -s emulator-5564 shell "cat /data/local/tmp/d2.xml" 2>&1) -join "" }
$W = "$env:TEMP\e2echk5564"
New-Item -ItemType Directory -Force -Path $W | Out-Null
Set-Content -Path "$W\$Tag.xml" -Value $raw -Encoding UTF8
$seen = @{}
foreach ($m in [regex]::Matches($raw, '<node\b[^>]*>')) {
  $n = $m.Value
  $t = $null
  if ($n -match 'text="([^"]*)"') { $t = $Matches[1] }
  elseif ($n -match "text='([^']*)'") { $t = $Matches[1] }
  if (-not $t -or $t.Trim() -eq "") { continue }
  $t = $t -replace '\s+', ' '
  $x = -1; $y = -1
  if ($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
    $l = [int]$Matches[1]; $tp = [int]$Matches[2]; $r = [int]$Matches[3]; $b2 = [int]$Matches[4]
    $x = [int][math]::Floor(($l + $r) / 2); $y = [int][math]::Floor(($tp + $b2) / 2)
  }
  $k = "$x,$y"
  if ($seen.ContainsKey($k)) { continue }
  $seen[$k] = 1
  Write-Output "  ctr=($x,$y) t='$t'"
}
Write-Output "  [len=$($raw.Length)]"
