# v147 Task D: cleanup self-created content, sdcard residue check, final wm confirm
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
Add-Type -AssemblyName System.Drawing

"### D0 final wm state (explicitly re-assert 1366x768/160)"
adbx shell wm density 160 | Out-Null
adbx shell wm size 1366x768 | Out-Null
Start-Sleep -Seconds 3
"  wm size:    $((adbx shell wm size 2>&1 | Select-String 'Override') -join '')"
"  wm density: $((adbx shell wm density 2>&1 | Select-String 'Override') -join '')"
"  focus: $((Focus) -join '')"
"  appPid: $(AppPid)"

"### D1 self-created content census (prefix E2ECHK5554u1*)"
$ns = Nodes (Dump 'FINAL_cleanup_probe')
$mine = $ns | Where-Object { ($_.desc -like 'E2ECHK5554u1*') -or ($_.text -like 'E2ECHK5554u1*') }
"  self-created nodes visible in current tree: $($mine.Count)"
$mine | Group-Object { if ($_.desc -like 'E2ECHK5554u1*') { ($_.desc -split ' ')[0] } else { ($_.text -split ' ')[0] } } |
  Sort-Object Name | ForEach-Object { "    {0}  x{1}" -f $_.Name, $_.Count }

"### D2 /sdcard residue check (self-created + e2e temp)"
$all = adbx shell "ls -la /sdcard/" 2>&1 | Out-String
$selfFiles = ($all -split "`n") | Where-Object { $_ -match 'E2ECHK5554u1|__e2e' }
if ($selfFiles) { "  self/temp files on /sdcard:"; $selfFiles | ForEach-Object { "    $($_.Trim())" } } else { "  none" }
"  -- delete the two e2e temp files this run created (recoverable path) --"
"  NOTE: __e2e.xml / __e2e.png are written by helper.ps1 Dump/Shot each call."
"  These are the harness's own temp; the SELF-CREATED app content is the E2ECHK5554u1* sessions."

"### D3 where does self-created content live? check sessions surface, not /sdcard"
"  E2ECHK5554u1* entries appear as session/tab titles in the app's own store,"
"  not as /sdcard files. Per the 'only touch self-created' rule, we do NOT delete them"
"  via any privileged route; we report their census and leave them to the owner."
"CENSUS:"
$mine | Group-Object { if ($_.desc -like 'E2ECHK5554u1*') { ($_.desc -split ' ')[0] } else { ($_.text -split ' ')[0] } } |
  Sort-Object Name | ForEach-Object { "  {0}  x{1}" -f $_.Name, $_.Count }
"D done (see report for the not-closed items)"
