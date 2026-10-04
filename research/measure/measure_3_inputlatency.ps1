# measure_3_inputlatency.ps1 — input -> on-screen latency PROXY (offline, no backend).
#
# *** THIS IS A BASELINE PROXY, NOT A TRUE END-TO-END USER-PERCEIVED LATENCY. ***
# Rationale / limits, stated up front:
#   * Backend v2 is stopped, so there is no message round-trip to measure.
#     "Send a prompt -> first token on screen" is NOT measurable here.  -> see
#     "not measurable" section of BASELINE_NATIVE.md.
#   * The app ships no Perfetto/instrumentation hook and we must not rebuild
#     (concurrency discipline: no gradle, no reinstall).
#   * Therefore we measure the RENDERING half of the pipeline, from the
#     platform's own per-frame trace:
#         adb shell dumpsys gfxinfo <pkg> framestats
#     Each frame row carries, in nanoseconds:
#         NewestInputEvent  = timestamp of the newest MotionEvent folded into
#                             this frame (0 => frame was not input-driven)
#         FrameCompleted    = timestamp the frame finished rendering
#     => input_to_frame = FrameCompleted - NewestInputEvent
#     That is a real, platform-measured input->on-screen interval, but it
#     EXCLUDES the user's own reaction time and any network/LLM time, and it
#     is bounded below by the vsync/frame interval.
#
# Method:
#   1. `dumpsys gfxinfo <pkg> reset`  (clears framestats)
#   2. dispatch N taps that each cause a visible screen change
#   3. `dumpsys gfxinfo <pkg> framestats`, parse the CSV
#   4. report percentiles of (FrameCompleted - NewestInputEvent)
#   5. cross-check against gfxinfo's own "Number High input latency"

. (Join-Path $PSScriptRoot 'common.ps1')

$TAPS = 12

# settle on the home screen
EnsureForeground | Out-Null
Start-Sleep -Seconds 2
ResetGfx

Write-Output "### dispatching $TAPS input-driven taps (alternating New Session / Plugins) ..."
$hosts = @()
for ($i=1; $i -le $TAPS; $i++) {
  if ($i % 2 -eq 1) { $tx=$C.SideNewSess[0]; $ty=$C.SideNewSess[1] } else { $tx=$C.SidePlugins[0]; $ty=$C.SidePlugins[1] }
  $sw = [Diagnostics.Stopwatch]::StartNew()
  A shell input tap $tx $ty | Out-Null
  $sw.Stop()
  $hosts += [math]::Round($sw.Elapsed.TotalMilliseconds,1)
  Start-Sleep -Milliseconds 900
  Back
  A shell am start -n $Act | Out-Null     # keep the process warm & foregrounded
  Start-Sleep -Milliseconds 700
}
Write-Output ("### host-side `input tap` round-trip ms: " + ($hosts -join ', '))
Write-Output "### NOTE: that number is adb+app_process startup overhead, NOT the in-app latency."

# ---- pull framestats ----
EnsureForeground | Out-Null
Start-Sleep -Seconds 2
$fs  = A shell dumpsys gfxinfo $Pkg framestats
$fs | Out-File -Encoding utf8 (Join-Path $OutDir 'framestats_raw.txt')
$lines = @($fs)
$raw = $lines -join "`n"
$raw | Out-File -Encoding utf8 (Join-Path $OutDir 'framestats_raw_join.txt')

# ---- parse the CSV block ----
# NOTE: on this Android build the framestats schema is the NEW one:
#   Flags,FrameTimelineVsyncId,IntendedVsync,Vsync,InputEventId,
#   HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,
#   FrameDeadline,FrameStartTime,FrameInterval,WorkloadTarget,AnimationTime,
#   AnimationCounter,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,
#   FrameCompleted,DequeueBufferDuration,QueueBufferDuration,GpuCompleted,
#   SwapBuffersCompleted,DisplayPresentTime,CommandSubmissionCompleted
# The classic Android<=13 columns OldestInputEvent / NewestInputEvent are GONE,
# replaced by InputEventId + HandleInputStart. We therefore use:
#   HandleInputStart  = when the app began handling the input for this frame
#   FrameCompleted    = when the frame finished
#   SwapBuffersCompleted / DisplayPresentTime = closest thing to "on screen"
$startIdx = -1
for ($i=0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^\s*Flags,') { $startIdx = $i; break } }
if ($startIdx -lt 0) {
  Write-Output "### framestats CSV header NOT FOUND — dump head:"
  ($lines | Select-Object -First 25) | ForEach-Object { Write-Output "  $_" }
  exit 1
}
$header = ($lines[$startIdx] -split ',') | ForEach-Object { $_.Trim() }
function Col($n) { return [array]::IndexOf($header, $n) }
$idxHS  = Col 'HandleInputStart'
$idxFC  = Col 'FrameCompleted'
$idxSBC = Col 'SwapBuffersCompleted'
$idxDPT = Col 'DisplayPresentTime'
$idxFS  = Col 'FrameStartTime'
$idxDL  = Col 'FrameDeadline'
$idxIV  = Col 'IntendedVsync'
$idxFI  = Col 'FrameInterval'
$idxIE  = Col 'InputEventId'
Write-Output ("### columns: HandleInputStart=$idxHS FrameCompleted=$idxFC SwapBuffersCompleted=$idxSBC DisplayPresentTime=$idxDPT IntendedVsync=$idxIV FrameInterval=$idxFI InputEventId=$idxIE")

$rows = @()
for ($i=$startIdx+1; $i -lt $lines.Count; $i++) {
  $l = $lines[$i].Trim()
  if ($l -notmatch '^[0-9]+(,[0-9]+)*,?$') { break }
  $p = $l -split ','
  if ($p.Count -lt $header.Count) { continue }
  $hs = [int64]$p[$idxHS];  $fc = [int64]$p[$idxFC];  $sbc= [int64]$p[$idxSBC]
  $dpt= [int64]$p[$idxDPT]; $fsv= [int64]$p[$idxFS];  $dl = [int64]$p[$idxDL]
  $iv = [int64]$p[$idxIV];  $fi = [int64]$p[$idxFI];  $ie = [int64]$p[$idxIE]
  $rows += [pscustomobject]@{
    intended_vsync=$iv; frame_interval=$fi; input_event_id=$ie
    handle_input_start=$hs; frame_start=$fsv; deadline=$dl
    frame_completed=$fc; swap_buffers_completed=$sbc; display_present=$dpt
    input_to_frame_completed_ms = $(if ($hs -gt 0 -and $fc -gt 0) { [math]::Round(($fc - $hs)/1e6, 2) } else { $null })
    input_to_swap_ms             = $(if ($hs -gt 0 -and $sbc -gt 0) { [math]::Round(($sbc - $hs)/1e6, 2) } else { $null })
    input_to_present_ms          = $(if ($hs -gt 0 -and $dpt -gt 0) { [math]::Round(($dpt - $hs)/1e6, 2) } else { $null })
    frame_ms = $(if ($iv -gt 0 -and $fc -gt 0) { [math]::Round(($fc - $iv)/1e6, 2) } else { $null })
  }
}
$rows | Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $OutDir 'framestats_parsed.csv')

$total  = $rows.Count
$inputDriven = @($rows | Where-Object { $_.input_to_frame_completed_ms -ne $null })
$idle  = @($rows | Where-Object { $_.handle_input_start -le 0 -and $_.frame_ms -ne $null })
$withPresent = @($rows | Where-Object { $_.input_to_present_ms -ne $null })

Write-Output ""
Write-Output "### framestats frames parsed : $total"
Write-Output "### input-driven frames       : $($inputDriven.Count)"
Write-Output "### non-input (animation/idle): $($idle.Count)"
Write-Output "### frames w/ DisplayPresentTime: $($withPresent.Count)"

function Pct($arr, $p) {
  if ($arr.Count -eq 0) { return 'N/A' }
  $s = @($arr | Sort-Object)
  $i = [math]::Min($s.Count-1, [math]::Max(0, [math]::Ceiling($p/100.0*$s.Count)-1))
  return $s[$i]
}

if ($inputDriven.Count -gt 0) {
  $v = @($inputDriven | ForEach-Object { $_.input_to_frame_completed_ms })
  Write-Output ""
  Write-Output "### ---- HandleInputStart -> FrameCompleted (ms), n=$($v.Count)  [主口径] ----"
  Write-Output ("  min    = " + ($v | Measure-Object -Minimum).Minimum)
  Write-Output ("  p50    = " + (Pct $v 50))
  Write-Output ("  p90    = " + (Pct $v 90))
  Write-Output ("  p95    = " + (Pct $v 95))
  Write-Output ("  p99    = " + (Pct $v 99))
  Write-Output ("  max    = " + ($v | Measure-Object -Maximum).Maximum)
  Write-Output ("  mean   = " + [math]::Round(($v | Measure-Object -Average).Average,2))
}
$swap = @($rows | Where-Object { $_.input_to_swap_ms -ne $null } | ForEach-Object { $_.input_to_swap_ms })
if ($swap.Count -gt 0) {
  Write-Output ""
  Write-Output "### ---- HandleInputStart -> SwapBuffersCompleted (ms), n=$($swap.Count) [上屏代理] ----"
  Write-Output ("  p50 = " + (Pct $swap 50) + "   p90 = " + (Pct $swap 90) + "   p99 = " + (Pct $swap 99) + "   max = " + ($swap | Measure-Object -Maximum).Maximum)
}
if ($withPresent.Count -gt 0) {
  $pv = @($withPresent | ForEach-Object { $_.input_to_present_ms })
  Write-Output ""
  Write-Output "### ---- HandleInputStart -> DisplayPresentTime (ms), n=$($pv.Count) [真正的上屏] ----"
  Write-Output ("  p50 = " + (Pct $pv 50) + "   p90 = " + (Pct $pv 90) + "   p99 = " + (Pct $pv 99))
} else {
  Write-Output ""
  Write-Output "### ---- HandleInputStart -> DisplayPresentTime: 0 frames carry DisplayPresentTime (field is 0)"
  Write-Output "###      => the hardware present timestamp is NOT populated on this emulator build;"
  Write-Output "###         SwapBuffersCompleted is used as the closest available on-screen proxy."
}
$fi = @($rows | ForEach-Object { $_.frame_interval } | Where-Object { $_ -gt 0 } | Select-Object -Unique)
if ($fi.Count) { Write-Output ""; Write-Output ("### display FrameInterval = " + ((($fi | ForEach-Object { [math]::Round($_/1e6,3) }) -join ', ')) + " ms  => " + [math]::Round(1e9/$fi[0],1) + " Hz") }
if ($idle.Count -gt 0) {
  $v2 = @($idle | ForEach-Object { $_.frame_ms })
  Write-Output ""
  Write-Output "### ---- non-input frame time IntendedVsync->FrameCompleted (ms), n=$($v2.Count) ----"
  Write-Output ("  p50    = " + (Pct $v2 50))
  Write-Output ("  p90    = " + (Pct $v2 90))
  Write-Output ("  p99    = " + (Pct $v2 99))
}

# ---- cross-check ----
$gi = (A shell dumpsys gfxinfo $Pkg) -join "`n"
$gi | Out-File -Encoding utf8 (Join-Path $OutDir 'gfxinfo_after_input.txt')
foreach ($pair in @(@('Number High input latency','High input latency frames'), @('Number Missed Vsync','Missed Vsync'), @('Total frames rendered','Total frames'), @('Janky frames','Janky'))) {
  if ($gi -match ('(?m)^\s*' + [regex]::Escape($pair[0]) + ':\s*(.+)$')) { Write-Output ("gfxinfo {0,-24} = {1}" -f $pair[1], $Matches[1].Trim()) }
}
