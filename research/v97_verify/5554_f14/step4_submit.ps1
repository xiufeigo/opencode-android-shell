# F14 step 4: confirm the HOST field survived scrolling, then submit and capture the
# passphrase card. Every button is re-located by label from a FRESH dump - never by
# remembered coordinates.
. "$PSScriptRoot\f14lib.ps1"

# ---- A. scroll up to re-prove the host value is still in the widget tree ----
$xml = Dump 'F14_vh_a'
$eds = EditTexts $xml
$hasHost = $eds | Where-Object { $_.Text -and $_.Text -like '*sshtest*' }
if (-not $hasHost) {
    Write-Host 'host not in viewport -> fast swipe down to reveal it'
    # reuse a band with no EditText under it
    $used = $eds | ForEach-Object { $_.Y1..$_.Y2 } | Sort-Object -Unique
    for ($y = 880; $y -le 980; $y++) { if ($used -notcontains $y) { $band = $y; break } }
    if (-not $band) { $band = 900 }
    SwipeForm 540 $band 540 ($band + 130) 80
    $xml = Dump 'F14_vh_b'
    $hasHost = (EditTexts $xml) | Where-Object { $_.Text -and $_.Text -like '*sshtest*' }
}
if ($hasHost) { Write-Host "HOST STILL PRESENT -> '$($hasHost[0].Text)' bounds=$($hasHost[0].Raw)" }
else { Write-Host 'WARNING: host not visible even after scroll - relying on step1 proof' }

Write-Host ''
Write-Host '=== full form state pre-submit ==='
ShowEdits $xml 'pre-submit'
foreach ($t in (Texts $xml)) { if ($t.Y1 -gt 500 -and $t.Y1 -lt 1800) { Write-Host ("  '{0}' @({1},{2})" -f $t.Text, $t.CX, $t.CY) } }

# ---- B. submit ----
Write-Host ''
CollapseIme
$xml2 = Dump 'F14_pre_submit'
$add = (Texts $xml2) | Where-Object { $_.Text -eq 'Add server' } | Select-Object -First 1
if (-not $add) { Write-Host 'FAIL: Add server button not found'; Shot 'F14_submit_abort'; exit 1 }
Write-Host "tapping 'Add server' @($($add.CX),$($add.CY))"
TapXY $add.CX $add.CY
Start-Sleep -Seconds 3

# ---- C. what did the app show? card vs immediate error ----
$xml3 = Dump 'F14_after_submit'
Write-Host ''
Write-Host '=== AFTER SUBMIT: all texts y>400 ==='
foreach ($t in (Texts $xml3)) { if ($t.Y1 -gt 400) { Write-Host ("  '{0}' [{1},{2}][{3},{4}] c=({5},{6})" -f $t.Text, $t.X1, $t.Y1, $t.X2, $t.Y2, $t.CX, $t.CY) } }
Write-Host '--- EditTexts ---'
ShowEdits $xml3 'after submit'
