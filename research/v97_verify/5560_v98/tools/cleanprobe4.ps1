$ErrorActionPreference = 'Stop'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$root = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_v98"
$ev = "$root\ev4"
$probe = "$root\tools\probe4.ps1"
$pkg = "com.xiufeigo.opencodeshell"

function Get-Sig([string]$tag) {
  $p = Join-Path $ev "$tag.sig.txt"
  if (-not (Test-Path $p)) { return $null }
  $raw = Get-Content $p -Encoding UTF8
  $i = [array]::IndexOf($raw, "SIG_BEGIN"); $j = [array]::IndexOf($raw, "SIG_END")
  if ($i -lt 0 -or $j -lt 0) { return $null }
  $raw[($i+1)..($j-1)] | Where-Object { $_.Trim() -ne "" }
}
function Get-Count([string]$tag) {
  $raw = Get-Content (Join-Path $ev "$tag.sig.txt") -Encoding UTF8
  [int]((($raw | Where-Object { $_ -like "NODES_TOTAL=*" }) -split "=")[1])
}
function Get-StripTop([string]$tag) {
  $raw = Get-Content (Join-Path $ev "$tag.sig.txt") -Encoding UTF8
  $s = $raw | Where-Object { $_ -like "*STRIP *clickable=true*" -and $_ -like "*1452*" }
  ($s | ForEach-Object { if ($_ -match "STRIP \[(\d+),(\d+)\]") { [int]$Matches[2] } } | Measure-Object -Minimum).Minimum
}

# Restore: cold start -> open the same session -> baseline probe
function Restore([string]$tag) {
  & $adb -s emulator-5560 shell am force-stop $pkg | Out-Null
  Start-Sleep -Seconds 2
  & $adb -s emulator-5560 shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
  Start-Sleep -Seconds 7
  & $adb -s emulator-5560 shell input tap 600 894      # recent session row
  Start-Sleep -Seconds 4
  & $probe -Tag $tag -SettleSec 1 | Out-Null
}

# Candidates, each from a freshly restored baseline
$cands = @(
  @{n="D1_restore_ctrl";      act=$null;                                        note="restore only (control)"},
  @{n="D2_tab_277";           act=@{t=@(277,1500)};                             note="SessionTab center, from clean restore"},
  @{n="D3_tab_180";           act=@{t=@(180,1500)};                             note="SessionTab x=180, from clean restore"},
  @{n="D4_tab_320";           act=@{t=@(320,1500)};                             note="SessionTab x=320, from clean restore"},
  @{n="D5_home_42";           act=@{t=@(42,1500)};                              note="Home button (known-working control)"},
  @{n="D6_palette_587";       act=@{t=@(587,1500)};                             note="palette button (known-working control)"},
  @{n="D7_strip_edge_439";    act=@{t=@(439,1500)};                             note="x=439 = close-tab button (must NOT open drawer)"},
  @{n="D8_swipe_up_on_tab";   act=@{sw=@(250,1500,250,700,80)};                  note="fast swipe up starting ON SessionTab"}
)

$rows = @()
foreach ($c in $cands) {
  $r = Restore ("R_" + $c.n)
  $pre = Get-Count ("R_" + $c.n)
  $preStrip = Get-StripTop ("R_" + $c.n)
  & $adb -s emulator-5560 logcat -c
  if ($c.act) {
    $a = $c.act
    if ($a.t)  { & $adb -s emulator-5560 shell input tap $a.t[0] $a.t[1] }
    if ($a.sw) { & $adb -s emulator-5560 shell input swipe $a.sw[0] $a.sw[1] $a.sw[2] $a.sw[3] $a.sw[4] }
  }
  Start-Sleep -Seconds 2
  & $probe -Tag $c.n -SettleSec 1 | Out-Null
  $post = Get-Count $c.n
  $postStrip = Get-StripTop $c.n
  $sig = Get-Sig $c.n; $preSig = Get-Sig ("R_" + $c.n)
  $changed = $true
  if ($null -ne $sig -and $null -ne $preSig) { $changed = $null -ne (Compare-Object $preSig $sig) }
  $err = (& $adb -s emulator-5560 logcat -d 2>&1 | Select-String -Pattern "FATAL|AndroidRuntime|ANR in|Exception" | Measure-Object).Count
  $verdict = if (-not $changed) { "NO_CHANGE" } else { "CHANGED" }
  $rows += [pscustomobject]@{
    Candidate=$c.n; Note=$c.note; PreNodes=$pre; PostNodes=$post
    PreStripTop=$preStrip; PostStripTop=$postStrip; Verdict=$verdict; LogErrors=$err
  }
  Write-Host ("{0,-22} pre={1,-4} post={2,-4} strip {3}->{4}  {5,-10} err={6}" -f $c.n,$pre,$post,$preStrip,$postStrip,$verdict,$err)
}
$rows | Export-Csv (Join-Path $ev "entry_probe_clean.csv") -NoTypeInformation -Encoding UTF8
Write-Host "CLEAN TABLE SAVED"
