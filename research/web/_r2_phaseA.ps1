$ErrorActionPreference='Continue'
$root="C:\sensorsdata\main\program\opencode-android-shell"
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S="emulator-5900"
$PKG='com.xiufeigo.opencodeshell.web.debug'
$ACT='com.xiufeigo.opencodeshell.web.WebHostActivity'
$out="$root\research\web\_r2_phaseA.log"
function Say($m){ $s="[$(Get-Date -Format 'HH:mm:ss')] $m"; Write-Host $s; $s | Out-File $out -Append -Encoding UTF8 }
function A { & $adb -s $S @args }
Say "=== PHASE A: setup / install / seed / identity ==="

"--- geometry (1080x2400@420) ---" | Out-File $out -Append -Encoding UTF8
A shell wm size 1080x2400 | Out-Null
A shell wm density 420 | Out-Null
$geo = ((A shell wm size | Out-String).Trim() + ' | ' + ((A shell wm density | Out-String).Trim()))
Say "geometry = $geo"
$geo | Out-File $out -Append -Encoding UTF8

A reverse tcp:4095 tcp:4095 | Out-Null
Say "reverse --list: $((A reverse --list | Out-String).Trim())"

"--- install RELEASE apk ---" | Out-File $out -Append -Encoding UTF8
$relApk="$root\webapp\build\outputs\apk\release\webapp-release.apk"
$relMd5=(Get-FileHash $relApk -Algorithm MD5).Hash.ToLower()
Say "installing RELEASE md5=$relMd5 size=$((Get-Item $relApk).Length)"
$ins = (A install -r -g $relApk 2>&1 | Out-String).Trim()
Say "install: $ins"
$ins | Out-File $out -Append -Encoding UTF8

A shell am force-stop $PKG | Out-Null
Start-Sleep -Seconds 2
A logcat -c | Out-Null
Say "launch for seeding"
A shell am start -n $ACT | Out-Null
Start-Sleep -Seconds 12

# --- seed password: find dialog, type, rightmost button ---
A shell uiautomator dump /sdcard/r2a.xml | Out-Null
$xml = (A shell cat /sdcard/r2a.xml 2>&1 | Out-String)
$hasDialog = ($xml -match 'password="true"')
Say "password dialog present = $hasDialog"
$pb=$null
foreach($m in [regex]::Matches($xml,'<node[^>]*')){
  $n=$m.Value
  if($n -match 'password="true"' -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ $pb=@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]); break }
}
if($pb){
  Say "seed: password field bounds = [$($pb[0]),$($pb[1])][$($pb[2]),$($pb[3])]"
  A shell input tap ([int](($pb[0]+$pb[2])/2)) ([int](($pb[1]+$pb[3])/2)) | Out-Null
  Start-Sleep -Milliseconds 800
  A shell input text "opencode" | Out-Null
  Start-Sleep -Milliseconds 800
  # rightmost enabled button
  $btns=@()
  foreach($m in [regex]::Matches($xml,'<node[^>]*')){
    $n=$m.Value
    if($n -match 'class="android\.widget\.Button"' -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' -and $n -notmatch 'enabled="false"'){
      $btns += ,@([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4])
    }
  }
  if($btns.Count -gt 0){
    $ok=$btns|Sort-Object{($_[0]+$_[2])/2}|Select-Object -Last 1
    Say "seed: buttons=$($btns.Count) rightmost=[$($ok[0]),$($ok[1])][$($ok[2]),$($ok[3])] -> tap"
    A shell input tap ([int](($ok[0]+$ok[2])/2)) ([int](($ok[1]+$ok[3])/2)) | Out-Null
  } else { Say "seed: no button -> ENTER"; A shell input keyevent KEYCODE_ENTER | Out-Null }
  Start-Sleep -Seconds 10
} else { Say "seed: no dialog (unexpected on fresh AVD)" }

# --- seeding verdict from host log ---
$lc = (A logcat -d -s OcWebHost:V OcPerf:V OcPerfJS:V 2>&1 | Out-String)
$verdict = if($lc -match 'credentials stored'){'SEEDED OK'} elseif($lc -match 'no password in SecretStore'){'STILL EMPTY'} else {'UNKNOWN'}
Say "SEED VERDICT = $verdict"
$lc | Out-File "$root\research\web\_r2_seed_logcat.txt" -Encoding UTF8
Say "=== PHASE A DONE ==="
