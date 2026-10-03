param(
  [Parameter(Mandatory=$true)][string]$SshHost,
  [Parameter(Mandatory=$true)][string]$Name,
  [string]$Pass = "opencode"
)
$ErrorActionPreference = 'Continue'
$env:PATH = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools;" + $env:PATH

function Get-ET {
  adb -s emulator-5564 shell "uiautomator dump --compressed /data/local/tmp/d.xml" 2>&1 | Out-Null
  Start-Sleep -Milliseconds 400
  $raw = (adb -s emulator-5564 shell "cat /data/local/tmp/d.xml" 2>&1) -join ""
  $list = @()
  foreach ($m in [regex]::Matches($raw, '<node\b[^>]*>')) {
    $n = $m.Value
    if ($n -notmatch 'class="[^"]*EditText') { continue }
    $t = ""; if ($n -match 'text="([^"]*)"') { $t = $Matches[1] }
    if ($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
      $l=[int]$Matches[1];$tp=[int]$Matches[2];$r=[int]$Matches[3];$b=[int]$Matches[4]
      $list += [pscustomobject]@{L=$l;T=$tp;R=$r;B=$b;CtrX=[int][math]::Floor(($l+$r)/2);CtrY=[int][math]::Floor(($tp+$b)/2);Text=$t}
    }
  }
  return ,$list
}

# 1. open dialog: find the "添加 SSH 服务" node in the servers list
adb -s emulator-5564 shell "uiautomator dump --compressed /data/local/tmp/d.xml" 2>&1 | Out-Null
Start-Sleep -Milliseconds 400
$raw = (adb -s emulator-5564 shell "cat /data/local/tmp/d.xml" 2>&1) -join ""
$addNode = $null
foreach ($m in [regex]::Matches($raw, '<node\b[^>]*>')) {
  $n = $m.Value
  $t = ""; if ($n -match 'text="([^"]*)"') { $t = $Matches[1] }
  if ($t -like '添加 SSH 服务*' -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' -and [int]$Matches[1] -gt 1500) {
    $addNode = [pscustomobject]@{X=[int][math]::Floor(([int]$Matches[1]+[int]$Matches[3])/2);Y=[int][math]::Floor(([int]$Matches[2]+[int]$Matches[4])/2)}
  }
}
if (-not $addNode) { $addNode = [pscustomobject]@{X=1700;Y=602}; Write-Output "WARN: add-node text match failed, using fallback (1700,602)" }
Write-Output "TAP add ($($addNode.X),$($addNode.Y))"
adb -s emulator-5564 shell input tap $addNode.X $addNode.Y
Start-Sleep -Seconds 2

# 2. locate Host field = dialog EditText (x within 700..1730), the LOWEST one
$ets = Get-ET
$dlg = $ets | Where-Object { $_.L -ge 690 -and $_.R -le 1730 } | Sort-Object { $_.B } -Descending
if (-not $dlg) { Write-Output "FATAL: host field not found. all:"; $ets | ForEach-Object { Write-Output "  L=$($_.L) T=$($_.T) R=$($_.R) B=$($_.B) t='$($_.Text)'" }; exit 1 }
$hostEt = $dlg[0]
Write-Output "HOST field at ($($hostEt.CtrX),$($hostEt.CtrY))"
adb -s emulator-5564 shell input tap $hostEt.CtrX $hostEt.CtrY
Start-Sleep -Milliseconds 800
adb -s emulator-5564 shell input text $SshHost
Start-Sleep -Seconds 1

# 3. verify host
$ets = Get-ET
$h = ($ets | Where-Object { $_.L -ge 690 -and $_.R -le 1730 -and $_.Text -ne "" } | Sort-Object { $_.B })[0]
Write-Output "HOST READBACK: t='$($h.Text)'"
if ($h.Text -ne $SshHost) { Write-Output "FATAL: host mismatch (expected '$SshHost')"; exit 2 }

# 4. TAB -> Name
adb -s emulator-5564 shell input keyevent 61
Start-Sleep -Milliseconds 500
adb -s emulator-5564 shell input text $Name
Start-Sleep -Milliseconds 800
# 5. TAB x2 -> password
adb -s emulator-5564 shell input keyevent 61
Start-Sleep -Milliseconds 300
adb -s emulator-5564 shell input keyevent 61
Start-Sleep -Milliseconds 500
adb -s emulator-5564 shell input text $Pass
Start-Sleep -Seconds 1

# 6. verify name + password + auth, find Add button
$raw2 = (adb -s emulator-5564 shell "cat /data/local/tmp/d.xml" 2>&1) -join ""
adb -s emulator-5564 shell "uiautomator dump --compressed /data/local/tmp/d.xml" 2>&1 | Out-Null
Start-Sleep -Milliseconds 400
$raw2 = (adb -s emulator-5564 shell "cat /data/local/tmp/d.xml" 2>&1) -join ""
$add = $null
foreach ($m in [regex]::Matches($raw2, '<node\b[^>]*>')) {
  $n = $m.Value
  $t = ""; if ($n -match 'text="([^"]*)"') { $t = $Matches[1] }
  if ($t -like '添加服务*' -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
    $add = [pscustomobject]@{X=[int][math]::Floor(([int]$Matches[1]+[int]$Matches[3])/2);Y=[int][math]::Floor(([int]$Matches[2]+[int]$Matches[4])/2)}
  }
  if ($t -and $t.Trim() -and ($t -match '认证方式|密码|私钥|E2ECHK|添加服务|取消')) {
    if ($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
      Write-Output ("  LABEL ctr=({0},{1}) t='{2}'" -f [int][math]::Floor(([int]$Matches[1]+[int]$Matches[3])/2),[int][math]::Floor(([int]$Matches[2]+[int]$Matches[4])/2),$t)
    }
  }
}
if (-not $add) { Write-Output "FATAL: add button not found"; exit 3 }
Write-Output "TAP submit ($($add.X),$($add.Y))"
Write-Output ("SUBMIT_AT=" + (Get-Date -Format "HH:mm:ss"))
adb -s emulator-5564 shell input tap $add.X $add.Y
