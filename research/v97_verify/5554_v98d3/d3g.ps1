# d3g.ps1 - D3-4 three-state closeout round on emulator-5554.
# Reuses the proven v97 helper library (nav / form fill / card answering).
param(
    [ValidateSet('baseline','mkeny','connect1','connecting','authreq','cleanup','enum')][string]$Phase = 'baseline',
    [string]$Dev = 'emulator-5554'
)
$ErrorActionPreference = 'Stop'
$HELP = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554'
. "$HELP\E2ECHK5554_run.ps1" -Dev $Dev
$global:DIR = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3'
$PFX = 'D3G'
$LOG = Join-Path $global:DIR 'd3g_run.log'
function Log($m) { $s = "[{0:HH:mm:ss}] {1}" -f (Get-Date), $m; Write-Host $s; Add-Content -Path $LOG -Value $s }
$KEYPATH = '/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519'
$HOSTOK  = 'ssh -p 2222 sshtest@127.0.0.1'

AssertDevice
Log "PHASE=$Phase dev=$global:DEV"

function ScrollListEnd($n) {
    # panel does NOT auto-scroll to appended rows -> drive to the end, then back a touch
    for ($i = 0; $i -lt 5; $i++) { A shell "input swipe 540 1700 540 850 400" | Out-Null; Start-Sleep -Milliseconds 500 }
    for ($i = 0; $i -lt 2; $i++) { A shell "input swipe 540 850 540 1700 400" | Out-Null; Start-Sleep -Milliseconds 400 }
}
function ShowState($tag) {
    $n = Nodes $tag
    Log "--- STATE $tag ---"
    $hits = @()
    foreach ($p in @('Connected','Authentication required','Connecting over SSH','Connection failed','Disconnected','Server healthy','Could not establish','left running','Reconnect','Disconnect')) {
        if (Has $n $p) { $hits += $p; Log "   HAS: $p" }
    }
    if ($hits.Count -eq 0) { Log '   (no known state token matched)' }
    return $n
}

switch ($Phase) {
  'mkeny' {
    ResetFaillock | Out-Null; Log 'faillock reset'
    GotoServers
    OpenAddForm
    Fill-SshForm -Target $HOSTOK -Name 'E2ECHK5554d3g-key' -Auth PrivateKey -KeyPath $KEYPATH -Passphrase 'keypass123' -Tag 'KEY'
    Shot "${PFX}_KEY_22_ready"
    SubmitForm
    Start-Sleep -Seconds 2
    $n = ScrollListEnd; $n = Nodes "${PFX}_KEY_23_after"
    Log '--- after submit (list end) ---'
    $n | Select-String -Pattern 'Server actions|E2ECHK5554d3g|127\.0\.0\.1|10\.0\.2\.2|EditText' | ForEach-Object { Log "  NODE $($_.Line)" }
    Shot "${PFX}_KEY_24_after"
  }
  'connect1' {
    $n = ScrollListEnd; $n = Nodes "${PFX}_C1_00_list"
    $row = $n | Select-String -Pattern 'E2ECHK5554d3g-key' | Select-Object -First 1
    if (-not $row) { Log 'TARGET ROW NOT FOUND'; ($n | Select-String 'TextView') | Select-Object -First 25 | ForEach-Object { Log "  $($_.Line)" }; throw 'no target row' }
    $rr = RectOf $row.Line; $nameCy = [int](($rr[1] + $rr[3]) / 2)
    Log "TARGET row nameCy=$nameCy bounds=[$($rr[0]),$($rr[1])][$($rr[2]),$($rr[3])]"
    $n | Select-String -Pattern 'Server actions' | ForEach-Object { Log "  KEBAB $($_.Line)" }
    # row actions: Connect/Reconnect button lives inside the row, right of name
    $btn = $n | Select-String -Pattern '^Connect\t|^Reconnect\t' | Select-Object -First 1
    if ($btn) {
        $bp = CenterOf $btn.Line
        Log "TAP row action '$((($btn.Line -split "`t")[0]))' @ $($bp[0]),$($bp[1])"
        Tap $bp[0] $bp[1] 1200
    } else { Log 'row action button not found; dumping row neighbourhood' }
    # A challenge card (passphrase / Password:) may be up. Answer it in a TIGHT block.
    $n2 = Nodes "${PFX}_C1_01_aftertap"
    $fld = $n2 | Select-String -Pattern 'EditText\t' | Select-Object -First 1
    $con = $n2 | Select-String -Pattern '^Continue\t|^Connect\t' | Select-Object -First 1
    if ($fld -and $con) {
        Log 'CARD UP -> answering challenge'
        $fp = CenterOf $fld.Line; $cp = CenterOf $con.Line
        A shell "input tap $($fp[0]) $($fp[1])" | Out-Null; Start-Sleep -Milliseconds 800
        A shell "input text 'keypass123'" | Out-Null; Start-Sleep -Milliseconds 1200
        A shell "input tap $($cp[0]) $($cp[1])" | Out-Null
        Log 'challenge submitted'
    } else { Log 'no challenge card' }
    Start-Sleep -Seconds 4
    $n3 = ShowState "${PFX}_C1_02_result"
    $n3 | Select-String -Pattern 'E2ECHK5554d3g-key|Server actions|Reconnect|Disconnect' | ForEach-Object { Log "  NODE $($_.Line)" }
    Shot "${PFX}_C1_03_result"
    SshdAcceptedPub '-4min' | ForEach-Object { Log "  SSHD $_" }
  }
  'connecting' {
    GotoServers; OpenAddForm
    Fill-SshForm -Target 'ssh -p 2222 sshtest@10.255.255.1' -Name 'E2ECHK5554d3g-unr' -Auth PrivateKey -KeyPath $KEYPATH -Passphrase 'keypass123' -Tag 'UNR'
    SubmitForm; Start-Sleep -Seconds 2
    $n = ScrollListEnd; $n = Nodes "${PFX}_U_00_list"
    $n | Select-String -Pattern 'E2ECHK5554d3g|Server actions' | ForEach-Object { Log "  NODE $($_.Line)" }
    Shot "${PFX}_U_01_list"
  }
  'enum' {
    $n = ScrollListEnd; $n = Nodes "${PFX}_E_00"
    $n | Select-String -Pattern 'E2ECHK5554d3g|10\.0\.2\.2|127\.0\.0\.1|Server actions|Connected|Connecting|Disconnected|healthy' | ForEach-Object { Log "  NODE $($_.Line)" }
  }
}
Log 'PHASE DONE'
