# seed_native.ps1 — 原生壳首次启动的 "Connect to a server" 引导页播种。
# 新建 AVD 上原生壳的 oc_server_config 也是空的（与 web 壳的 SecretStore 空同理），
# 不播种则四量全在测引导页。
param(
  [string]$Dev = 'emulator-5830',
  [string]$Password = 'opencode',
  [string]$Backend = 'http://127.0.0.1:4095'
)
$ErrorActionPreference='Continue'
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Pkg = 'com.xiufeigo.opencodeshell'
$Act = 'com.xiufeigo.opencodeshell.OcActivity'
function A { & $Adb -s $Dev @args }
function Say { param($m) Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }
function DumpXml { A shell uiautomator dump /sdcard/_n.xml | Out-Null; (A shell cat /sdcard/_n.xml) -join '' }
function Nodes {
  param([string]$Xml)
  $out=@()
  foreach ($m in [regex]::Matches($Xml,'<node[^>]*')) {
    $n=$m.Value
    $c=''; if($n -match 'class="([^"]*)"'){$c=$Matches[1]}
    $t=''; if($n -match 'text="([^"]*)"'){$t=$Matches[1]}
    $b=$null; if($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' -and $n -match 'enabled="true"'){
      $b=@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]) }
    $out += ,[pscustomobject]@{ class=$c; text=$t; bounds=$b; node=$n }
  }
  $out
}
function TapB { param($b,[int]$ms=700) A shell input tap ([int](($b[0]+$b[2])/2)) ([int](($b[1]+$b[3])/2)) | Out-Null; Start-Sleep -Milliseconds $ms }

Say "=== seed native ==="
A shell am force-stop $Pkg; Start-Sleep -Seconds 1
A logcat -c | Out-Null
A shell am start -n "$Pkg/$Act" | Out-Null
Start-Sleep -Seconds 6
$xml = DumpXml
$ns = Nodes $xml
Write-Output "--- editable nodes ---"
$eds = $ns | Where-Object { $_.class -match 'EditText' -and $_.bounds }
foreach ($e in $eds) { Write-Output ("  {0} text='{1}' bounds=[{2},{3}][{4},{5}]" -f $e.class,$e.text,$e.bounds[0],$e.bounds[1],$e.bounds[2],$e.bounds[3]) }
if ($eds.Count -lt 2) { Say "!! expected 2 EditText, got $($eds.Count)"; exit 2 }

# 1) address
TapB $eds[0].bounds 700
A shell input text ($Backend -replace ':','%s' -replace '/','%s') | Out-Null
Start-Sleep -Milliseconds 400
# 2) password —— **重新 dump**：IME 弹起会把整页顶上去，用旧坐标必点空（web 壳已踩过）
$xml2 = DumpXml
$eds2 = (Nodes $xml2) | Where-Object { $_.class -match 'EditText' -and $_.bounds }
if ($eds2.Count -ge 2) { TapB $eds2[1].bounds 700 } else { TapB $eds[1].bounds 700 }
A shell input text $Password | Out-Null
Start-Sleep -Milliseconds 400
# 3) Connect —— 同样重新 dump，取 text 含 Connect 的可点节点
$xml3 = DumpXml
$ns3 = Nodes $xml3
Write-Output "--- nodes after typing ---"
foreach ($e in $ns3) { if ($e.text) { Write-Output ("  {0} : '{1}'" -f $e.class,$e.text) } }
$conn = $ns3 | Where-Object { $_.text -eq 'Connect' -and $_.bounds } | Select-Object -First 1
if (-not $conn) { $conn = $ns3 | Where-Object { $_.text -match '^Connect' -and $_.bounds } | Select-Object -First 1 }
if ($conn) { Say "  tapping Connect bounds=[$($conn.bounds[0]),$($conn.bounds[1])][$($conn.bounds[2]),$($conn.bounds[3])]"; TapB $conn.bounds 2500 }
else { Say "  Connect node not found; trying ENTER"; A shell input keyevent KEYCODE_ENTER | Out-Null; Start-Sleep -Seconds 2 }
Start-Sleep -Seconds 8
$xml4 = DumpXml
Write-Output "--- after Connect: visible texts ---"
foreach ($e in (Nodes $xml4)) { if ($e.text) { Write-Output ("  {0} : '{1}'" -f $e.class,$e.text) } }
A shell screencap -p /sdcard/_n2.png
A pull /sdcard/_n2.png "$PSScriptRoot\out_p4\_seed_native.png" 2>&1 | Out-String
Write-Output "=== done ==="
