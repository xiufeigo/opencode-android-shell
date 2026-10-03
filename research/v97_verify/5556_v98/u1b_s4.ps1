. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'
$O = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"

# long turn is streaming. Send N more prompts to build the queue.
# Each: tap composer -> type -> Send. Do it fast with minimal dumps.
function Send1([string]$txt) {
    $e = Ctr (DumpIt "q_pre") 'Ask anything'
    if ($e) { Ado "input tap $($e[0]) $($e[1])" | Out-Null; Start-Sleep -Milliseconds 350 }
    TypeTxt $txt
    Start-Sleep -Milliseconds 250
    $f = DumpIt "q_typed"
    $s = Ctr $f 'Send'
    if ($s) { Ado "input tap $($s[0]) $($s[1])" | Out-Null; Start-Sleep -Milliseconds 450; return $true }
    Write-Output "SENDFAIL '$txt'"; return $false
}

$sent = 0
for ($i = 1; $i -le 12; $i++) {
    $ok = Send1 ("Queued prompt number $i for U1B queue depth test")
    if ($ok) { $sent++ }
    # verify it landed
    $v = DumpIt "q_v$i"
    $hit = @(GetNodes $v | Where-Object { $_.Txt -and $_.Txt.Contains("number $i for U1B") })
    $qcnt = @(GetNodes $v | Where-Object { $_.Txt -and $_.Txt.ToLower().Contains('queued') }).Count
    Write-Output "MSG$i sent=$ok inTree=$($hit.Count) queuedTextNodes=$qcnt"
}
Write-Output "TOTAL_SENT=$sent"
Write-Output "IME=$(ImeState) IMETOP=$(ImeTopPx) FOCUS=$(FocusNow)"
