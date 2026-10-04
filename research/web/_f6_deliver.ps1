$ErrorActionPreference = 'Stop'
$root = "C:\sensorsdata\main\program\opencode-android-shell"
$W = "$root\research\web"
$log = "$W\_f6_deliver.txt"
"" | Set-Content $log
function L($m){ $s=($m|Out-String).TrimEnd(); Write-Output $s; $s | Add-Content $log }

$apkDir = "$root\webapp\build\outputs\apk"
$rel = "$apkDir\release\webapp-release.apk"
$dbg = "$apkDir\debug\webapp-debug.apk"

# canonical deliver names only (no new suffixed names)
$dstRel = "$root\research\deliver\opencode-shell-web-release-nor8.apk"
$dstDbg = "$root\research\deliver\opencode-shell-web.apk"
Copy-Item $rel $dstRel -Force
Copy-Item $dbg $dstDbg -Force

foreach ($f in @($dstDbg, $dstRel)) {
  $it = Get-Item $f
  $h  = Get-FileHash $f -Algorithm MD5
  $sh = Get-FileHash $f -Algorithm SHA256
  L ""
  L "FILE=$($it.Name)"
  L "  SIZE=$($it.Length)"
  L "  MD5=$($h.Hash.ToLower())"
  L "  SHA256=$($sh.Hash.ToLower())"
}

# signature fingerprint
$bt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$signer = Join-Path $bt.FullName "apksigner.bat"
L ""
L "BUILDTOOLS=$($bt.Name)"
L (& $signer verify --print-certs $dstRel 2>&1)

# package self-check: does the shipped dex actually contain the fix?
$dexdump = Join-Path $bt.FullName "dexdump.exe"
$tmp = "$W\_f6_apkx"
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($dstRel, $tmp)
$dex = Get-ChildItem $tmp -Filter *.dex | Select-Object -First 1
L ""
L "DEX=$($dex.Name) SIZE=$($dex.Length)"
$bytes = [System.IO.File]::ReadAllBytes($dex.FullName)
$txt = [System.Text.Encoding]::ASCII.GetString($bytes)
L "DEX_HAS_buildProbeRequest=$($txt -match 'buildProbeRequest')"
L "DEX_HAS_PROBE_PATH_api_info=$($txt -match '/api/info')"
L "DEX_HAS_passLen=$($txt -match 'passLen')"
L "DEX_HAS_authHeaderSent=$($txt -match 'authHeaderSent')"
# package self-check: no password literal in dex strings
L "DEX_HAS_literal_opencode_pw_pair=$($txt -match 'opencode:opencode')"
L "DELIVER_DONE"
