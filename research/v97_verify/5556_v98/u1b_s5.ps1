. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

function Send1([string]$txt, [string]$tag) {
    $e = Ctr (DumpIt "${tag}_pre") 'Ask anything'
    if ($e) { Ado "input tap $($e[0]) $($e[1])" | Out-Null; Start-Sleep -Milliseconds 300 }
    TypeTxt $txt
    Start-Sleep -Milliseconds 200
    $f = DumpIt "${tag}_typed"
    $s = Ctr $f 'Send'
    if ($s) { Ado "input tap $($s[0]) $($s[1])" | Out-Null; Start-Sleep -Milliseconds 500; return $true }
    Write-Output "SENDFAIL '$txt'"; return $false
}

# clear the leftover draft by sending it (it becomes queue entry #1)
Write-Output "=== send leftover draft as entry 1 ==="
$f = DumpIt "15_draft"
$e = Ctr $f 'Ask anything'
if ($e) { Ado "input tap $($e[0]) $($e[1])" | Out-Null; Start-Sleep -Milliseconds 300 }
$s = Ctr $f 'Send'
if ($s) { Ado "input tap $($s[0]) $($s[1])" | Out-Null; Write-Output "SENT_DRAFT" }
Start-Sleep -Seconds 2
$v = DumpIt "16_after_draft"
$ns = GetNodes $v
$tl = $ns | Where-Object { $_.Scroll -eq 'true' -and $_.W -gt 800 } | Select-Object -First 1
Write-Output "TL=[$($tl.X1),$($tl.Y1)][$($tl.X2),$($tl.Y2)] h=$($tl.H) IME=$(ImeState)"
Write-Output "--- nodes with Queued/Steer/Remove ---"
foreach ($n in $ns) { if ($n.Txt -and ($n.Txt -match 'Queued|Steer|Remove|toggle queue')) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click) '$($n.Txt)'") } }
Write-Output "--- all scrollables ---"
foreach ($n in ($ns | Where-Object { $_.Scroll -eq 'true' })) { Write-Output ("  [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) cls=$($n.Cls)") }
ShotIt "16_after_draft" | Out-Null
