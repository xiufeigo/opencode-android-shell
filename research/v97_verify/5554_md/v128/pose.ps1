# v128 Part B: three poses hunting the Queue chip.
# Gate chain read from source:
#   Composer.kt:1808  if (!shellMode && alternateDelivery != null) DeliveryToggleChip(...)
#   Composer.kt:633-637 alternateDelivery = if (!queueSupported) null
#                  else ComposerDelivery.alternate(running, followUpBehavior, queueEditOpen, hasContent)
#   ComposerDelivery.kt:54-58 alternate: if editingQueued -> STEER; if !running || !hasContent -> null
#   ComposerQueue.kt:41-42 isQueueSupported: generation != "V1"
# => the chip exists ONLY while a turn is RUNNING and the composer HAS CONTENT.
$ErrorActionPreference='Stop'
. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v128\helpers.ps1

function ChipRow([string]$Tag) {
  $f = Dump "${Tag}_full.xml"
  $n = Nodes $f
  $pane = $n | Where-Object { $_.x1 -le 4 -and $_.wd -ge 900 -and $_.y1 -ge 100 } | Sort-Object ht | Select-Object -Last 1
  ""
  "===== CHIP ROW @ $Tag  (paneBottom=$($pane.y2)) ====="
  $n | Where-Object { $_.y1 -ge ($pane.y2 - 60) -and $_.y2 -le $pane.y2 } | ForEach-Object {
    "  desc=[$($_.desc)] text=[$($_.text)] [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] w=$($_.wd) h=$($_.ht) clk=$($_.clickable)"
  }
  "  --- Queue/Steer/x scan over FULL tree ---"
  $hits = $n | Where-Object { $_.desc -match 'Queue|Steer' -or $_.text -match 'Queue|Steer' -or $_.desc -eq ([char]0x00D7) -or $_.text -eq ([char]0x00D7) }
  if ($hits) { $hits | ForEach-Object { "    HIT desc=[$($_.desc)] text=[$($_.text)] [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] clk=$($_.clickable)" } }
  else { "    (none)" }
  "  --- running evidence (Stop present? Working? elapsed?) ---"
  $n | Where-Object { $_.desc -match 'Stop' -or $_.text -match 'Working|Stop' } | ForEach-Object {
    "    RUN desc=[$($_.desc)] text=[$($_.text)] [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)]"
  }
  return $n
}

$TAG = $args[0]
$POSE = $args[1]
"##### POSE $POSE #####"
"focus: $(Focus)   appPid: $(AppPid)   ime: $((A shell dumpsys input_method | Select-String mInputShown | Select-Object -First 1).ToString().Trim())"

# composer editor (runtime-parsed), type a long prompt
$n = Nodes (Dump "${TAG}_pre.xml")
$ed = $n | Where-Object { $_.class -match 'EditText' -and $_.wd -ge 200 } | Select-Object -First 1
"editor: [$($ed.x1),$($ed.y1)][$($ed.x2),$($ed.y2)] cx=$($ed.cx) cy=$($ed.cy)"

if ($POSE -eq '1' -or $POSE -eq '3') {
  # ---- long turn ----
  TapXY $ed.cx $ed.cy
  TypeText 'E2ECHK5554u1r count from 1 to 4000 one number per line'
  $n2 = Nodes (Dump "${TAG}_typed.xml")
  $et = ($n2 | Where-Object { $_.class -match 'EditText' -and $_.wd -ge 200 } | Select-Object -First 1).text
  "typed-readback: [$et]"
  $snd = $n2 | Where-Object { $_.desc -eq 'Send' -and $_.clickable -eq 'true' } | Select-Object -First 1
  "send: [$($snd.x1),$($snd.y1)][$($snd.x2),$($snd.y2)] cx=$($snd.cx) cy=$($snd.cy)"
  TapXY $snd.cx $snd.cy
  Start-Sleep -Seconds 3
  $stop = Nodes (Dump "${TAG}_running.xml") | Where-Object { $_.desc -eq 'Stop' } | Select-Object -First 1
  if ($stop) { "RUNNING CONFIRMED: Stop at [$($stop.x1),$($stop.y1)][$($stop.x2),$($stop.y2)]" } else { "WARNING: no Stop button yet" }
}

if ($POSE -eq '1' -or $POSE -eq '2') {
  # composer must HAVE CONTENT while running -> this is what makes alternateDelivery non-null
  $n3 = Nodes (Dump "${TAG}_mid.xml")
  $ed3 = $n3 | Where-Object { $_.class -match 'EditText' -and $_.wd -ge 200 } | Select-Object -First 1
  "editor@mid: [$($ed3.x1),$($ed3.y1)][$ed3.x2,$($ed3.y2)] cx=$($ed3.cx) cy=$($ed3.cy)"
  TapXY $ed3.cx $ed3.cy
  TypeText 'E2ECHK5554u1r follow up while running'
  $ver = Nodes (Dump "${TAG}_content.xml")
  $edv = $ver | Where-Object { $_.class -match 'EditText' -and $_.wd -ge 200 } | Select-Object -First 1
  "CONTENT PRESENT: [$($edv.text)]"
  $st = $ver | Where-Object { $_.desc -eq 'Stop' } | Select-Object -First 1
  "Stop still present: $(if($st){'YES'}else{'NO'})"
  "ime: $((A shell dumpsys input_method | Select-String mInputShown | Select-Object -First 1).ToString().Trim())"
  ChipRow "${TAG}_p1"
}

if ($POSE -eq '3') {
  # fire 2 more follow-ups WHILE first still running, then dump immediately
  foreach ($k in 2,3) {
    $nk = Nodes (Dump "${TAG}_k$k.xml")
    $ek = $nk | Where-Object { $_.class -match 'EditText' -and $_.wd -ge 200 } | Select-Object -First 1
    $sk = $nk | Where-Object { ($_.desc -eq 'Send' -or $_.desc -eq 'Stop') -and $_.clickable -eq 'true' } | Select-Object -First 1
    "follow-up $k : editor cx=$($ek.cx) cy=$($ek.cy) ; submit btn desc=[$($sk.desc)] at cx=$($sk.cx) cy=$($sk.cy)"
    if ($k -eq 2) { TapXY $ek.cx $ek.cy; TypeText "E2ECHK5554u1r follow up number two queued behind"; }
    TapXY $sk.cx $sk.cy
    Start-Sleep -Milliseconds 1200
  }
  ChipRow "${TAG}_p3"
}
Shot "${TAG}_pose${POSE}" | Out-Null
"##### POSE $POSE DONE #####"

