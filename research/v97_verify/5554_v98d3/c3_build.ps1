. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3_entry.ps1"
$ErrorActionPreference = 'Stop'
InitDir
$null = FocusInfo
$null = Cap "B0_start"
ClosePrompt
Start-Sleep -Milliseconds 800
$null = Cap "B1_afterclose"
ListRows "B1"
Say "formOpen=$(FormOpen)"

$null = AddEntry "B2" "ssh -p 2222 sshtest@127.0.0.1" "keypass123"
$null = AddEntry "B3" "ssh -p 2222 sshtest@127.0.0.1" "keypass123"
$null = AddEntry "B4" "ssh -p 2222 sshtest@127.0.0.1" "keypass123"
$null = Cap "B9_final"
ListRows "B9"
Say "DONE_BUILD"
