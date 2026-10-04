Set-Location "C:\sensorsdata\main\program\opencode-android-shell"

function Has($hay, $needle) { return [bool]($hay -match [regex]::Escape($needle)) }

$act  = Get-Content webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt -Raw
$man  = Get-Content webapp/src/main/AndroidManifest.xml -Raw
$nh   = Get-Content webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/NotifierHost.kt -Raw
$html = Get-Content webapp/src/main/assets/www/index.html -Raw

$a1 = Has $act "override fun onPermissionRequest("
$a2 = Has $act "isTrustedWebOrigin(origin)"
$a3 = Has $act "private fun sameOrigin(a: String, b: String)"
$m1 = Has $man "android.permission.CAMERA"
$m2 = Has $man "android.hardware.camera"
$h1 = Has $html "new window.Notification("
$h2 = Has $html "oc.postMessage("
Write-Output "onPermissionRequest_override = $a1"
Write-Output "isTrustedWebOrigin_gate      = $a2"
Write-Output "sameOrigin_exact_compare     = $a3"
Write-Output "manifest_CAMERA              = $m1"
Write-Output "manifest_uses_feature_camera = $m2"
Write-Output "index_uses_shim              = $h1"
Write-Output "index_direct_postMessage     = $h2  (must be False)"

$bad = @()
foreach ($t in @("hasFocus","dedup","eventID","localStorage")) { if (Has $nh $t) { $bad += $t } }
$joined = $bad -join ","
Write-Output "NotifierHost_policy_tokens   = [$joined]  (empty = host stays policy-free)"

Write-Output "---- deliverables ----"
Get-ChildItem research/deliver/opencode-shell-web*.apk | ForEach-Object {
  $h = (Get-FileHash $_.FullName -Algorithm MD5).Hash.ToLower()
  Write-Output "  $($_.Name)  $($_.Length) B  md5=$h"
}
Write-Output "---- report / lock / device / backend ----"
Write-Output "FIX_F3.md bytes = $((Get-Item research/web/FIX_F3.md).Length)"
Write-Output "lock exists      = $(Test-Path .agent-gradle-lock)"
$procs = Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" -ErrorAction SilentlyContinue
$c6050 = @($procs | Where-Object { $_.CommandLine -match "port 6050" }).Count
Write-Output "6050 procs       = $c6050"
Write-Output "6050 LISTEN      = $((Get-NetTCPConnection -LocalPort 6050 -State Listen -ErrorAction SilentlyContinue | Measure-Object).Count)"
$be = Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue
Write-Output "backend 4095 pid = $($be.OwningProcess)"
$ports = @()
foreach ($p in $procs) { if ($p.CommandLine -match "port (\d+)") { $ports += $Matches[1] } }
Write-Output "emulator ports alive = $($ports -join ',')  (only non-6050 = other agents, untouched)"
