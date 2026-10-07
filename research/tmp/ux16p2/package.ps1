$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = "D:\XiaoZhuTouTou\opencode-android-shell"
$deliver = "$root\research\deliver"
$map = @{
  "$root\webapp\build\outputs\apk\debug\webapp-debug.apk"      = "$deliver\opencode-shell-web.apk"
  "$root\webapp\build\outputs\apk\release\webapp-release.apk"  = "$deliver\opencode-shell-web-release-nor8.apk"
  "$root\webapp\build\outputs\apk\releaseR8\webapp-releaseR8.apk" = "$deliver\opencode-shell-web-release-r8.apk"
}
foreach ($k in $map.Keys) { Copy-Item $k $map[$k] -Force }

"=== DELIVERABLES ==="
foreach ($k in $map.Keys) {
  $t = $map[$k]
  $fi = Get-Item $t
  $md5 = (Get-FileHash $t -Algorithm MD5).Hash
  "{0,-38} {1,10}  {2}" -f $fi.Name, $fi.Length, $md5
}

"`n=== PACKAGE SELF-CHECK (debug apk) ==="
$apk = $deliver + "\opencode-shell-web.apk"
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
try {
  $entries = $zip.Entries
  $ui = @($entries | Where-Object { $_.FullName -like "assets/www/ui/*" -and $_.Name -ne "" })
  "ui entries            = $($ui.Count)   (expected 1613)"
  $assetsHtml = @($entries | Where-Object { $_.FullName -like "assets/www/*.html" })
  "assets/www/*.html     = $(($assetsHtml | ForEach-Object { $_.FullName }) -join ', ')"

  # oc-host.js md5 as shipped inside the APK
  $e = $entries | Where-Object { $_.FullName -eq "assets/www/oc-host.js" }
  if ($e) {
    $ms = New-Object System.IO.MemoryStream
    $s = $e.Open(); $s.CopyTo($ms); $s.Close()
    $md5 = [System.Security.Cryptography.MD5]::Create().ComputeHash($ms.ToArray())
    $hex = ($md5 | ForEach-Object { $_.ToString("x2") }) -join ''
    "oc-host.js in APK md5 = $($hex.ToUpper())  (expected 659BED67B949F1A4466AD3EF3D2D1E06)"
  } else { "oc-host.js NOT FOUND in apk" }

  # diagnostic page shipped inside the APK must be valid UTF-8 and declare charset
  $d = $entries | Where-Object { $_.FullName -eq "assets/www/index.html" }
  if ($d) {
    $ms = New-Object System.IO.MemoryStream
    $s = $d.Open(); $s.CopyTo($ms); $s.Close()
    $bytes = $ms.ToArray()
    $strict = New-Object System.Text.UTF8Encoding($false, $true)
    try { $txt = $strict.GetString($bytes); $ok = "VALID-UTF8" } catch { $txt = ""; $ok = "INVALID-UTF8" }
    "apk assets/www/index.html bytes=$($bytes.Length) utf8=$ok charsetDeclared=$($txt -match '(?i)<meta[^>]+charset')"
  }

  "`n=== CREDENTIAL SCAN (all three apks) ==="
  $pat = 'opencodesh|sk-[A-Za-z0-9]{20,}|Bearer\s+[A-Za-z0-9._\-]{20,}|password\s*=\s*"[^"]{4,}"|api[_-]?key\s*=\s*"[^"]{8,}"|BEGIN (RSA|OPENSSH|PRIVATE)'
  foreach ($t in $map.Values) {
    $z = [System.IO.Compression.ZipFile]::OpenRead($t)
    $hits = 0
    foreach ($en in $z.Entries) {
      if ($en.Length -eq 0) { continue }
      if ($en.FullName -notmatch '\.(html|js|json|xml|txt|properties|kt|java|md)$') { continue }
      $r = New-Object System.IO.StreamReader($en.Open())
      $c = $r.ReadToEnd(); $r.Close()
      if ($c -match $pat) { $hits++; "   HIT in $($en.FullName)" }
    }
    $z.Dispose()
    "{0,-38} credentialHits={1}" -f (Split-Path $t -Leaf), $hits
  }
} finally { $zip.Dispose() }
