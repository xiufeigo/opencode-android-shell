. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c5lib.ps1

# ---- ground truth: identify the ssh_json key by base64(SHA256(keyName)) ----
function KeyHash([string]$k) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  $b = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($k))
  return [Convert]::ToBase64String($b)
}

function GT {
  $raw = (Sh @('run-as',$script:PKG,'cat','shared_prefs/oc_server_config.xml')) -join "`n"
  $ms = [regex]::Matches($raw, '<string name="([^"]+)">([^<]*)</string>')
  $rows = @()
  foreach ($m in $ms) {
    $rows += [pscustomobject]@{ Name=$m.Groups[1].Value; ValLen=$m.Groups[2].Value.Length; Val=$m.Groups[2].Value }
  }
  return $rows
}

$SSHJSON = KeyHash 'ssh_json'
"GT ssh_json expected key = $SSHJSON"
$g = GT
"==== PREFS KEY TABLE ===="
foreach ($r in $g) { "  {0,-46} len={1,5} {2}" -f $r.Name,$r.ValLen,$(if($r.Name -eq $SSHJSON){'  <== ssh_json'}else{''}) }
$data = @($g | Where-Object { $_.Name -notlike '__androidx*' })
"GT_DATA_KEYS = {0}   (infra keysets = {1})" -f $data.Count, @($g | Where-Object {$_.Name -like '__androidx*'}).Count
$sj = @($g | Where-Object { $_.Name -eq $SSHJSON })
if (@($sj).Count -gt 0) { "GT_SSH_JSON_CIPHERTEXT_LEN = {0}" -f @($sj)[0].ValLen } else { "GT_SSH_JSON_KEY_NOT_PRESENT" }

# ---- navigate to Servers, re-reading coordinates immediately before each tap ----
$ok = $false
for ($try=0; $try -lt 6 -and -not $ok; $try++) {
  $doc = Dump ("navt{0}" -f $try)
  $srv = FindByLabel $doc 'Servers'
  if (@($srv).Count -eq 0) {
    A @('shell','input','swipe','980','744','200','744','400') | Out-Null
    Start-Sleep -Milliseconds 800
    continue
  }
  $b = NBnd @($srv)[0]
  $cx = [int](($b.L+$b.R)/2); $cy = [int](($b.T+$b.B)/2)
  "NAV try=$try tap Servers @($cx,$cy)"
  TapXY $cx $cy
  Start-Sleep -Seconds 2
  $doc2 = Dump ("srvchk{0}" -f $try)
  $hdr = @($doc2.SelectNodes('//node') | Where-Object { (NText $_) -eq 'Servers' })
  if (@($hdr).Count -gt 0) { "NAV_OK panel title 'Servers' present"; $ok = $true; $doc = $doc2 }
}
if (-not $ok) { "NAV_FAILED"; Cap 'navfail'; exit 1 }

"==== SERVERS PANEL SCROLLABLES ===="
foreach ($b in (ScrollableBounds $doc)) { "  SV [{0},{1}][{2},{3}]" -f $b.L,$b.T,$b.R,$b.B }
"==== kebabs ===="
foreach ($k in (KebabList $doc)) { "  KEB [{0},{1}][{2},{3}] title='{4}'" -f $k.Bnd.L,$k.Bnd.T,$k.Bnd.R,$k.Bnd.B,(RowTitleFor $doc $k) }
"==== all text ===="
foreach ($n in (AllNodes $doc)) {
  $t = NText $n; if (-not $t) { continue }
  $b = NBnd $n; if ($null -eq $b) { continue }
  "  T [{0},{1}][{2},{3}] clk={4} en={5} | {6}" -f $b.L,$b.T,$b.R,$b.B,(NClk $n),(NEn $n),$t
}
