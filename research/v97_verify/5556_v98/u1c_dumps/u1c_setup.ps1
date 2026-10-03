$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"

function Send-Prompt {
    param([string]$Text)
    $n = Get-Nodes (Dump-UI "tmp_send")
    $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
    if (-not $et) { throw "no EditText" }
    Tap-Node $et | Out-Null
    Start-Sleep -Milliseconds 700
    Type-Chunked $Text
    Start-Sleep -Milliseconds 500
    $n2 = Get-Nodes (Dump-UI "tmp_send2")
    $snd = $n2 | Where-Object { $_.Desc -eq 'Send' -or $_.Text -eq 'Send' } | Select-Object -First 1
    if (-not $snd) { throw "no Send" }
    Tap-Node $snd | Out-Null
}

$log = @()

# ---- Step 1: long turn ----
$prompt = "Write a detailed guide about Android window insets"
$log += "== step1 long turn =="
Send-Prompt $prompt
Start-Sleep -Seconds 6

# ---- Step 2: draft so the Queue chip appears (running && hasContent) ----
$log += "== step2 draft =="
$n = Get-Nodes (Dump-UI "u1c_run1")
$log += "focus: " + (Get-Focus)
$ime = Get-ImeState
$log += "mInputShown(after long turn)=" + $ime.Shown
$et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
if ($et) { Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 700 }
Type-Chunked "toggle queue"
Start-Sleep -Seconds 1

# ---- Step 3: find and tap exact Queue chip ----
$n = Get-Nodes (Dump-UI "u1c_chip")
$chip = Find-Exact $n "Queue" 246 298
$log += "chip found: " + ($chip -ne $null)
if ($chip) {
    $log += "chip bounds: $($chip.B.X1),$($chip.B.Y1)-$($chip.B.X2),$($chip.B.Y2)"
    Tap-Node $chip | Out-Null
} else {
    $log += "ALL nodes near tool row:"
    $n | Where-Object { $_.B -and $_.B.Y1 -ge 240 -and $_.B.Y1 -le 300 } | ForEach-Object {
        $log += "   '$($_.Text)' | '$($_.Desc)' | $($_.B.X1),$($_.B.Y1)-$($_.B.X2),$($_.B.Y2)"
    }
}
Start-Sleep -Seconds 1
$n = Get-Nodes (Dump-UI "u1c_afterchip")
$chip2 = Find-Exact $n "Queue" 240 300
$log += "after toggle chip: '$($chip2.Text)' at $($chip2.B.X1),$($chip2.B.Y1)"
$snd = $n | Where-Object { $_.Desc -eq 'Send' } | Select-Object -First 1
$log += "Send now at: $($snd.B.X1),$($snd.B.Y1)-$($snd.B.X2),$($snd.B.Y2)"

# ---- Step 4: clear draft then send 3 queued prompts ----
$et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
for ($i=1; $i -le 3; $i++) {
    $n = Get-Nodes (Dump-UI "u1c_pre_q$i")
    $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
    if (-not $et) { $log += "no ET at q$i"; break }
    Tap-Node $et | Out-Null
    Start-Sleep -Milliseconds 600
    # select-all + delete to clear draft
    A "input keyevent KEYCODE_MOVE_END" | Out-Null
    for ($k=0; $k -lt 14; $k++) { A "input keyevent 67" | Out-Null }
    Start-Sleep -Milliseconds 300
    Type-Chunked "Queued prompt number $i for U1C queue depth test"
    Start-Sleep -Milliseconds 500
    $n2 = Get-Nodes (Dump-UI "u1c_qsend$i")
    $snd = $n2 | Where-Object { $_.Desc -eq 'Send' } | Select-Object -First 1
    if ($snd) { $log += "queued #$i via Send at $($snd.B.X1),$($snd.B.Y1)"; Tap-Node $snd | Out-Null }
    else { $log += "NO SEND at q$i" }
    Start-Sleep -Milliseconds 800
}

# ---- Step 5: assert queue bar present + IME ----
$ime = Get-ImeState
$log += "mInputShown(setup)=" + $ime.Shown
$n = Get-Nodes (Dump-UI "u1c_setup_done")
$steer = $n | Where-Object { $_.Text -eq 'Steer' -or $_.Desc -eq 'Steer' } | Select-Object -First 1
$log += "STEER present: " + ($steer -ne $null) + " bounds: " + $(if($steer){"$($steer.B.X1),$($steer.B.Y1)-$($steer.B.X2),$($steer.B.Y2)"})
$rm = $n | Where-Object { $_.Text -eq 'Remove' -or $_.Desc -eq 'Remove' } | Select-Object -First 1
$log += "REMOVE present: " + ($rm -ne $null) + " bounds: " + $(if($rm){"$($rm.B.X1),$($rm.B.Y1)-$($rm.B.X2),$($rm.B.Y2)"})
$log += "focus: " + (Get-Focus)
$log += "QUEUE CHIP still: " + (($n | Where-Object { $_.Text -eq 'Queue' -and $_.B.Y1 -ge 240 -and $_.B.Y1 -le 300 } | Select-Object -First 1) -ne $null)

$log | Set-Content -Encoding UTF8 "$d\u1c_setup.log"
$log | ForEach-Object { $_ }
