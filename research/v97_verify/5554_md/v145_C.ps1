# v145 C: explicit restore + /sdcard residue check.
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"

"### C.1 explicit wm restore"
adbx shell wm size 1366x768    | Out-Null
adbx shell wm density 160      | Out-Null
Start-Sleep -Seconds 3
$sz = (adbx shell wm size 2>&1 | Out-String)
$de = (adbx shell wm density 2>&1 | Out-String)
"  $($sz.Trim())"
"  $($de.Trim())"
$okS = $sz -match 'Override size: 1366x768'
$okD = $de -match 'Override density: 160'
"  restore size   1366x768 : $okS"
"  restore density 160    : $okD"

"### C.2 /sdcard residue"
"  -- files matching my own tool scratch (__e2e.*) --"
$mine = adbx shell "ls -la /sdcard/__e2e.* 2>/dev/null" 2>&1
if ($mine) { $mine | ForEach-Object { "    $_" } } else { "    (none listed)" }
"  -- top-level /sdcard --"
adbx shell "ls -1 /sdcard" 2>&1 | ForEach-Object { "    $_" }
"  -- any png/xml at /sdcard root (should be none) --"
$png = adbx shell "ls -1 /sdcard/*.png 2>/dev/null" 2>&1
$xml = adbx shell "ls -1 /sdcard/*.xml 2>/dev/null" 2>&1
"    png: $(if($png){($png|Out-String).Trim()}else{'none'})"
"    xml: $(if($xml){($xml|Out-String).Trim()}else{'none'})"

"### C.3 app state / attribution"
"  focus: $((Focus) -join '')"
"  appPid=$(AppPid)  (app left running, connected - no force-stop needed)"
"  reverse: $((adbx reverse --list 2>&1 | Out-String).Trim())"

"### C.4 final logcat sweep (app pid, -t)"
$appPid = (AppPid)
if ($appPid) {
  $lg = & adb -s $script:D logcat -t 2000 -v brief 2>&1 | Out-String
  $mine = ($lg -split "`n") | Where-Object { $_ -match "\s$appPid\s" }
  $hits = $mine | Select-String -Pattern 'FATAL|IllegalArgumentException|maxHeight'
  "  app lines=$($mine.Count)  matches=$($hits.Count)"
  if ($hits) { $hits | ForEach-Object { "    !! $_" } } else { "    FATAL|IllegalArgumentException|maxHeight = EMPTY (clean)" }
  $mine | Out-File -Encoding utf8 "$script:ART\A_logcat_final.log"
}
"### C done"
