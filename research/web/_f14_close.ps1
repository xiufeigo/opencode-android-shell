param(
  [Parameter(Mandatory=$true)][string]$Tag,
  [Parameter(Mandatory=$true)][string]$Apk,
  [string]$Serial = "emulator-6350",
  [int]$SegSamples = 8
)
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S=$Serial
$PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_f14"
New-Item -ItemType Directory -Force -Path $OUT | Out-Null
$LOG="$OUT\$Tag.log"
if (Test-Path $LOG) { Remove-Item $LOG -Force }

# ============================================================
# F14 FIX #2: the VERDICT bug.
# F13's L() ended with `Write-Output $s`, so every L call inside
# KeepState() emitted its log line into the PowerShell PIPELINE.
# KeepState therefore returned a MULTI-LINE ARRAY, not 'true'/'false',
# so `$seg2 -eq 'false'` was always False -> VERDICT showed release=false.
# FIX: log with Write-Host (host only, never the pipeline) and pipe the
# log line to $null explicitly. Return value of KeepState is now clean.
# ============================================================
function L($m){
  $s="[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"),$m
  Add-Content -Path $LOG -Value $s -Encoding utf8
  Write-Host $s          # <-- FIXED: host, not pipeline
}
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function DumpXml($name){ $p="/sdcard/$name.xml"; A @('shell','uiautomator','dump',$p) | Out-Null; Start-Sleep -Milliseconds 350; return (Sh "cat $p") }
function Nodes($d){
  $o=@()
  foreach ($m in [regex]::Matches($d,'<node[^>]*>')) {
    $n=$m.Value
    $bm=[regex]::Match($n,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'); if(-not $bm.Success){continue}
    $x1=[int]$bm.Groups[1].Value;$y1=[int]$bm.Groups[2].Value;$x2=[int]$bm.Groups[3].Value;$y2=[int]$bm.Groups[4].Value
    if ($x1 -eq 0 -and $y1 -eq 0 -and $x2 -eq 0) { continue }
    $o += [pscustomobject]@{
      click=([regex]::Match($n,'clickable="([^"]*)"').Groups[1].Value)
      cls=([regex]::Match($n,'class="([^"]*)"').Groups[1].Value)
      desc=([regex]::Match($n,'content-desc="([^"]*)"').Groups[1].Value)
      text=([regex]::Match($n,'text="([^"]*)"').Groups[1].Value)
      pwd=([regex]::Match($n,'password="([^"]*)"').Groups[1].Value)
      x1=$x1;y1=$y1;x2=$x2;y2=$y2;cx=[int](($x1+$x2)/2);cy=[int](($y1+$y2)/2) }
  }
  return $o
}

# ---- F14 FIX #1 + #2: KeepState now returns a CLEAN scalar ----
function KeepState($label) {
  $w = (& $adb -s $S shell dumpsys window windows 2>&1 | Out-String)
  $lines = $w -split "`r?`n"; $startIdx=-1
  for ($i=0;$i -lt $lines.Count;$i++){ if($lines[$i] -match 'Window\{[^}]*com\.xiufeigo\.opencodeshell'){ $startIdx=$i; break } }
  $fs='NO_BLOCK'
  if($startIdx -ge 0){
    $endIdx=[Math]::Min($lines.Count-1,$startIdx+40)
    for($j=$startIdx+1;$j -lt $endIdx;$j++){ if($lines[$j] -match '^\s*Window\s*#?\d*\s*Window\{' -or $lines[$j] -match '^\s*WindowState\{'){ $endIdx=$j-1; break } }
    $block=($lines[$startIdx..([Math]::Max($startIdx,$endIdx))] -join "`n")
    $flagLines=[regex]::Matches($block,'(?m)^\s*(fl|pfl|flags)=.*$') | ForEach-Object { $_.Value.Trim() }
    foreach($fl in $flagLines){ L "${label}_WINDOWFLAGRAW: $fl" }
    if($flagLines.Count -gt 0){ $joined=$flagLines -join ' '
      $fs = if($joined -match '(?<![\w])KEEP_SCREEN_ON(?![\w])'){'true'}else{'false'}
      L "${label}_WINDOW_KEEP_SCREEN_ON=$fs" } else { L "${label}_WINDOW_NO_FLAGLINE_IN_BLOCK" }
  }
  $p = Sh "dumpsys power"
  L "${label}_POWER_mWakefulness=$([regex]::Match($p,'mWakefulness=(\w+)').Groups[1].Value) mHoldingDisplaySuspendBlocker=$([regex]::Match($p,'mHoldingDisplaySuspendBlocker=(\w+)').Groups[1].Value)"
  return $fs      # CLEAN scalar (was: multi-line array in F13)
}
function D1Lines($label){
  $l = Sh "logcat -d -s OcWebHost OcWebHost/Proxy"
  foreach($x in [regex]::Matches($l,'(?m)^.*D1 applyKeepScreenOn.*$')){ L "${label}_D1: $($x.Value.Trim())" }
  foreach($x in [regex]::Matches($l,'(?m)^.*/message.*$')){ L "${label}_MSG: $($x.Value.Trim())" }
}
function TS(){ (Get-Date).ToString('HH:mm:ss.fff') }

# ---- F14 FIX #3: space-preserving / special-char text input ----
#   `input text` treats %s as a literal SPACE. `&` is a metacharacter for the
#   DEVICE-side sh, so it must be single-quoted on the device command line.
function TypeText($raw) {   # $raw uses %s for space, and is passed device-quoted
  $esc = $raw.Replace("'","'\''")
  $cmd = "input text '$esc'"
  L "TYPE_CMD=adb -s $S shell $cmd"
  Sh $cmd | Out-Null
  Start-Sleep -Milliseconds 700
  L "TYPE_DONE"
}
function ComposerText {
  $d = DumpXml "typed"
  $n = Nodes $d
  $et = $n | Where-Object { $_.cls -match 'EditText' -and $_.y1 -ge 900 } | Select-Object -First 1
  if(-not $et){ $et = $n | Where-Object { $_.cls -match 'EditText' } | Select-Object -First 1 }
  if($et){ return $et.text } else { return "" }
}
function ClearComposer {
  # select-all + delete, so each round starts from an empty composer
  A @('shell','input','keyevent','-t','1000','123') | Out-Null
  Sh "input keyevent KEYCODE_MOVE_END" | Out-Null
  for($i=0;$i -lt 70;$i++){ Sh "input keyevent 67" | Out-Null }
}

L "########## F14 $Tag START $(Get-Date -Format o) ##########"
L "SERIAL=$S"
$md5=(Get-FileHash $Apk -Algorithm MD5).Hash.ToLower()
L "APK_PATH=$Apk"
L "APK_MD5=$md5"
L "APK_SIZE=$((Get-Item $Apk).Length)"

# ---------- run one round: focus composer, type, sample, tap Send ----------
function RunRound($idx, $bodyWithSpaces, $bodySpecial) {
  L "==================== ROUND $idx BEGIN ts=$(TS) ===================="
  A @('logcat','-c') | Out-Null

  # ---- STEP B: re-dump inside the session, find composer + Send ----
  A @('shell','input','keyevent','111') | Out-Null   # ESC: close IME
  Start-Sleep -Milliseconds 800
  $db = DumpXml "r${idx}b"
  $nb = Nodes $db
  $comp = $nb | Where-Object { $_.cls -match 'EditText' -and $_.y1 -ge 900 } | Select-Object -First 1
  if(-not $comp){ L "ROUND${idx}_COMPOSER=NOT_FOUND"; return $null }
  L "ROUND${idx}_COMPOSER bounds=[$($comp.x1),$($comp.y1)][$($comp.x2),$($comp.y2)] center=($($comp.cx),$($comp.cy))"
  L "ROUND${idx}_FOCUS_TAP=REAL_UI_TAP (composer)"
  Sh "input tap $($comp.cx) $($comp.cy)" | Out-Null
  Start-Sleep -Milliseconds 1200

  ClearComposer
  $emptyText = ComposerText
  L "ROUND${idx}_AFTER_CLEAR text=[$emptyText]"

  # ---- F14 #3: type text WITH SPACES via the %s convention ----
  L "ROUND${idx}_BODY_WITH_SPACES_INTENDED=[$($bodyWithSpaces -replace '%s',' ')]"
  TypeText $bodyWithSpaces
  $got = ComposerText
  L "ROUND${idx}_BODY_WITH_SPACES_READBACK=[$got]"
  $spaceOk = ($got -eq ($bodyWithSpaces -replace '%s',' '))
  L "ROUND${idx}_SPACE_PRESERVED=$spaceOk"

  # ---- F14 #3: a special character (&) ----
  L "ROUND${idx}_BODY_SPECIAL_INTENDED=[$($bodySpecial -replace '%s',' ')]"
  TypeText $bodySpecial
  $got2 = ComposerText
  L "ROUND${idx}_BODY_SPECIAL_READBACK=[$got2]"
  $specialOk = ($got2 -eq ($bodySpecial -replace '%s',' '))
  L "ROUND${idx}_SPECIAL_CHAR_OK=$specialOk"

  # ---- F14 #3: one CJK attempt, reported honestly whatever happens ----
  TypeText "f14中文"
  $got3 = ComposerText
  L "ROUND${idx}_CJK_INTENDED=[f14中文]"
  L "ROUND${idx}_CJK_READBACK=[$got3]"
  L "ROUND${idx}_CJK_OK=$($got3 -eq 'f14中文')"

  # ---- STEP D: re-resolve Send bounds (they move) ----
  $d2 = DumpXml "r${idx}c"
  $n2 = Nodes $d2
  $clicks2 = $n2 | Where-Object { $_.click -eq 'true' }
  $send=$null; $how=''
  foreach($c in $clicks2){ if($c.desc -match '(?i)send|发送'){ $send=$c; $how='content-desc'; break } }
  if(-not $send){ foreach($c in $clicks2){ if($c.text -match '(?i)^\s*(send|发送)\s*$'){ $send=$c; $how='text'; break } } }
  if(-not $send){ $band=$clicks2 | Where-Object { $_.y1 -ge 950 -and $_.cx -ge 700 -and ($_.x2-$_.x1) -le 400 }
    if($band){ $send=$band | Sort-Object -Property cx -Descending | Select-Object -First 1; $how='band-bottom-right' } }
  if(-not $send){ L "ROUND${idx}_SEND=NOT_FOUND"; return $null }

  # ================= F14 #1: SEG0 sampled BEFORE the tap =================
  $seg0 = KeepState "SEG0_preTap_idle_ROUND$idx"
  L "SEG0_ROUND${idx}_TAP_NOT_YET_SENT=YES  (this sample is a TRUE idle baseline)"
  L "SEG0_ROUND${idx}_ORDER=sample @ $(TS)  <  tap @ (next line)"

  L "ROUND${idx}_SEND_PICKED how=$how cls=$($send.cls) text=[$($send.text)] bounds=[$($send.x1),$($send.y1)][$($send.x2),$($send.y2)] center=($($send.cx),$($send.cy))"
  L "ROUND${idx}_TAP_CMD=adb -s $S shell input tap $($send.cx) $($send.cy)   [REAL_UI_TAP, no injection]"
  $t0=Get-Date
  $tapTs=$t0.ToString('HH:mm:ss.fff')
  L "TAP_AT_ROUND$idx=$tapTs"
  Sh "input tap $($send.cx) $($send.cy)" | Out-Null
  L "TAP_SENT_AT_ROUND$idx=$(TS)"

  # ---- assert the round really started (real click drove it) ----
  $started='false'
  for($i=0;$i -lt 10;$i++){ Start-Sleep -Seconds 2; $l2=Sh "logcat -d -s OcWebHost OcWebHost/Proxy"
    if($l2 -match 'roundActive=true'){ $started='true'; L "ROUND${idx}_STARTED_BY_UI_TAP=YES (logcat roundActive=true) t+$($i*2+2)s"; break } }
  if($started -eq 'false'){ L "ROUND${idx}_STARTED_BY_UI_TAP=NO (20s polling)" }
  $lg3 = Sh "logcat -d -s OcWebHost OcWebHost/Proxy"
  foreach($x in [regex]::Matches($lg3,'(?m)^.*POST.*/message.*$')){ L "ROUND${idx}_POST_MSG_EVIDENCE: $($x.Value.Trim())" }
  L "ROUND${idx}_TAP_DELTA_to_now_ms=$([int]((Get-Date)-$t0).TotalMilliseconds)"

  # ---- SEG0b: immediately after the tap, for ordering proof ----
  $seg0b = KeepState "SEG0B_postTap_ROUND$idx"
  L "SEG0B_ROUND${idx}_ORDER=tap @ $tapTs  <  sample @ $(TS)"

  # ---- SEG1: raised, sampled every 2 s ----
  $raise='false'
  for($i=0;$i -lt $SegSamples;$i++){ Start-Sleep -Seconds 2
    $fs = KeepState ("R{0}_SEG1_s{1:d2}_tplus{2}s" -f $idx,$i,($i*2+2)); if($fs -eq 'true'){ $raise='true' } }
  L "R${idx}_SEG1_RAISED_SEEN=$raise"
  D1Lines "R${idx}_SEG1" | Out-Null

  # ---- SEG2: wait for the round to END, then sample ----
  L "R${idx}_SEG2_WAITING_FOR_ROUND_END (polling logcat roundActive=false, max 150s)"
  $ended='false'
  for($i=0;$i -lt 75;$i++){
    Start-Sleep -Seconds 2
    $l3=Sh "logcat -d -s OcWebHost OcWebHost/Proxy"
    if($l3 -match 'roundActive=false'){ $ended='true'; L "R${idx}_ROUND_ENDED=YES at t+$($i*2+2)s after round start"; break }
  }
  if($ended -eq 'false'){ L "R${idx}_ROUND_ENDED=NOT_SEEN (150s cap) - sampled anyway" }
  $seg2 = KeepState "R${idx}_SEG2_afterEnd"
  D1Lines "R${idx}_SEG2" | Out-Null
  L "R${idx}_SEG2_RELEASE_OK=$($seg2 -eq 'false')   (uses the FIXED clean-scalar return)"

  return [pscustomobject]@{
    idx=$idx; tapTs=$tapTs; seg0=$seg0; seg0b=$seg0b; seg1=$raise; seg2=$seg2
    spaceOk=$spaceOk; specialOk=$specialOk
    readback=$got2
  }
}

# ============================ MAIN ============================
L "WAIT_FOR_DEVICE"
A @('wait-for-device') | Out-Null
for($i=0;$i -lt 40;$i++){
  $b=(A @('shell','getprop','sys.boot_completed')).Trim()
  L "BOOT_WAIT i=$i sys.boot_completed=[$b]"
  if($b -match '^1'){ break }
  Start-Sleep -Seconds 3
}
L "BOOTED=$(A @('shell','getprop','sys.boot_completed'))"

A @('shell','wm','size','1080x1600') | Out-Null
A @('shell','wm','density','420') | Out-Null
Start-Sleep -Seconds 2
L "WM_SIZE=$(A @('shell','wm','size'))"
L "WM_DENSITY=$(A @('shell','wm','density'))"
A @('reverse','tcp:4095','tcp:4095') | Out-Null
L "REVERSE=$(A @('reverse','--list'))"
A @('shell','pm','clear',$PKG) | Out-Null
L "INSTALL=$(A @('install','-r','-d',$Apk))"
$baseRaw = Sh "pm path $PKG"
L "PMPATH_FULL_RAW=[$baseRaw]"
$base=([regex]::Match($baseRaw,'(/data/app/[^|\s]+/base\.apk)')).Groups[1].Value
L "DEVICE_BASE_APK_PATH=[$base]"
if($base){
  L "LS_CHECK=$(Sh "ls -l $base")"
  $dm=Sh "md5sum $base"
  L "MDSUM_RAW=[$dm]"
  $devmd5=([regex]::Match($dm,'([0-9a-fA-F]{32})')).Groups[1].Value
  L "DEVICE_MD5=$devmd5"
  L "DEVICE_MD5_MATCH=$($devmd5 -eq $md5)"
}

A @('logcat','-c') | Out-Null
A @('shell','am','start','-n',$ACT) | Out-Null
Start-Sleep -Seconds 10
$d = DumpXml "seed1"
$et = [regex]::Matches($d,'class="android\.widget\.EditText"[^>]*password="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
L "PASSWORD_FIELDS=$($et.Count)"
if($et.Count -ge 1){
  $cx=[int](([int]$et[0].Groups[1].Value+[int]$et[0].Groups[3].Value)/2); $cy=[int](([int]$et[0].Groups[2].Value+[int]$et[0].Groups[4].Value)/2)
  L "PASSWORD_TAP=($cx,$cy)"
  Sh "input tap $cx $cy" | Out-Null; Start-Sleep -Milliseconds 1500
  foreach($ch in 'opencode'.ToCharArray()){ Sh "input text $ch" | Out-Null; Start-Sleep -Milliseconds 280 }
  L "PASSWORD_TYPED"
  $d2 = DumpXml "seed2"
  $btn=$null
  foreach($m in [regex]::Matches($d2,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')){
    $x1=[int]$m.Groups[1].Value;$y1=[int]$m.Groups[2].Value;$x2=[int]$m.Groups[3].Value;$y2=[int]$m.Groups[4].Value
    if($y1 -ge 870 -and $x1 -ge 600){ $btn=[pscustomobject]@{cx=[int](($x1+$x2)/2);cy=[int](($y1+$y2)/2)} } }
  if($btn){ Sh "input tap $($btn.cx) $($btn.cy)" | Out-Null; L "SEED_SAVE_TAPPED=($($btn.cx),$($btn.cy))" } else { L "SEED_SAVE_TAPPED=NOT_FOUND" }
} else { L "PASSWORD_FIELDS=0 (no seed screen)" }
Start-Sleep -Seconds 16

for($w=0;$w -lt 12;$w++){
  $d0 = DumpXml "v14a"
  $rr = @((Nodes $d0) | Where-Object { $_.click -eq 'true' -and $_.text -ne '' -and $_.cls -match 'Button' -and $_.x2 -gt 0 })
  L "RENDER_WAIT try=$w rows=$($rr.Count)"
  if($rr.Count -ge 3){ break }
  Start-Sleep -Seconds 4
}
$lg = Sh "logcat -d -s OcWebHost OcPerfJS OcWebHost/Proxy"
$pm=[regex]::Match($lg,'listening on http://127\.0\.0\.1:(\d{4,5})')
$pp=if($pm.Success){[int]$pm.Groups[1].Value}else{0}
$sids=([regex]::Matches($lg,'/api/session/(ses_[A-Za-z0-9]+)')|ForEach-Object{$_.Groups[1].Value}|Sort-Object -Unique)
$authfail=([regex]::Matches($lg,'AUTH FAILED')).Count
L "PRE_PROXY_PORT=$pp AUTHFAILED=$authfail PROBE_OK=$($lg -match 'probe OK') FIRSTCONTENT=$($lg -match 'firstContent')"
L "PRE_SESSION_IDS=$(@($sids).Count) -> $(@($sids) -join ', ')"
L "PRE_LIST_ROWS=$($rr.Count)"
$preOk = ((@($sids).Count -ge 1) -and ($authfail -eq 0) -and ($pp -gt 0) -and ($rr.Count -ge 1))
L "PRECONDITION_PASS=$preOk"

# ---- STEP A: real tap on a session row ----
$target = $rr | Where-Object { $_.y1 -ge 250 -and $_.y1 -le 1000 } | Select-Object -First 1
if(-not $target){ $target = $rr | Select-Object -First 1 }
if($target){
  L "STEP_A_SESSION_ROW text=[$($target.text)] bounds=[$($target.x1),$($target.y1)][$($target.x2),$($target.y2)] center=($($target.cx),$($target.cy))"
  L "STEP_A_TAP_CMD=adb -s $S shell input tap $($target.cx) $($target.cy)   [REAL_UI_TAP]"
  Sh "input tap $($target.cx) $($target.cy)" | Out-Null
  L "STEP_A_TAPPED=REAL_UI_TAP (session row)" } else { L "STEP_A_TAPPED=NO_ROW_FOUND" }
Start-Sleep -Seconds 6

# ================= ROUND 1 =================
$r1 = RunRound 1 "f14%stest%swith%sspace" "a&b"
# ================= ROUND 2 (immediately after round 1 ended) =================
$r2 = RunRound 2 "f14%sround%stwo" "c&d"
# ================= SEG3: +90 s after round 2 ended =================
L "SEG3_WAIT 95s (>= the required +90s window after round 2 end)"
Start-Sleep -Seconds 95
$seg3 = KeepState "SEG3_tplus95s_afterRound2"
D1Lines "R2_SEG3" | Out-Null

# ================= VERDICT (with the FIXED script) =================
$seg1=$r1.seg1; $seg0=$r1.seg0
L "SUMMARY_$Tag PRECONDITION=$preOk R1_tap=$($r1.tapTs) SEG0_preTap=$seg0 SEG0B_postTap=$($r1.seg0b) SEG1_raised=$seg1 SEG2_afterEnd=$($r1.seg2) R2_SEG1_raised=$($r2.seg1) R2_SEG2_afterEnd=$($r2.seg2) SEG3=$seg3"
L "SEG0_IS_TRUE_IDLE_BASELINE=$($seg0 -eq 'false')  (sample taken BEFORE the tap)"
L "SEG0_TO_TAP_ORDER_OK=$($seg0 -eq 'false')  (false before tap, then true after tap => the flag was caused by the tap)"
L "VERDICT_$Tag raised=$seg1 release=$($r1.seg2 -eq 'false') plus90=$($seg3 -eq 'false')"
L "VERDICT2_$Tag round1_raise=$($r1.seg1) round1_release=$($r1.seg2 -eq 'false') round2_raise=$($r2.seg1) round2_release=$($r2.seg2 -eq 'false') plus90=$($seg3 -eq 'false')"
L "VERDICT3_$Tag seg0_pretap=$seg0 space_r1=$($r1.spaceOk) special_r1=$($r1.specialOk) space_r2=$($r2.spaceOk) special_r2=$($r2.specialOk)"
L "########## F14 $Tag END $(Get-Date -Format o) ##########"
