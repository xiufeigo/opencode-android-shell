# p5_precheck.ps1 — P5 前置门禁：确认跑的是官方 UI，而不是口令/配置对话框。
# 未通过本门禁的数字一律作废（P4 的教训：SecretStore 为空 => 弹对话框 => 四量全测成对话框却"看起来跑完了"）。
# 判据（三条独立证据）：
#   A) logcat: passwordPresent=true 且 **没有** "no password in SecretStore, asking"
#   B) logcat: OcPerfJS 出现 >0 次（P4：测对话框时该 tag 出现 0 次）
#   C) CDP: document.title / 可见文本属于官方 opencode UI（不是"密码/保存并连接"）
param(
  [string]$Dev = 'emulator-5840',
  [ValidateSet('web','native')][string]$Side = 'web'
)
$ErrorActionPreference = 'Continue'
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Out = "$PSScriptRoot\out_p5"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
function A { & $Adb -s $Dev @args }
function Say { param($m) Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }
if ($Side -eq 'web') { $Pkg='com.xiufeigo.opencodeshell.web.debug'; $Act='com.xiufeigo.opencodeshell.web.WebHostActivity' }
else { $Pkg='com.xiufeigo.opencodeshell'; $Act='com.xiufeigo.opencodeshell.OcActivity' }

Say "=== PRECHECK [$Side] cold start ==="
A shell am force-stop $Pkg | Out-Null; Start-Sleep -Seconds 2
A logcat -c | Out-Null
A shell am start -n "$Pkg/$Act" | Out-Null
Start-Sleep -Seconds 22

$lc = (A logcat -d -s OcWebHost:V OcPerf:V OcPerfJS:V OcNative:V '*:S') -join "`n"
$lc | Out-File -Encoding utf8 (Join-Path $Out "precheck_${Side}_logcat.txt")
Say "--- logcat (filtered) ---"
($lc -split "`r?`n") | Where-Object { $_ -match 'OcWebHost|OcPerf|OcNative' } | Select-Object -First 40 | Write-Output

$a_passwordPresent = ($lc -match 'passwordPresent=true')
$a_notAsking      = -not ($lc -match 'no password in SecretStore, asking')
$b_perfjs         = ([regex]::Matches($lc,'OcPerfJS')).Count
$soload = ([regex]::Matches($lc,'loadUrl|onPageFinished|paint first-paint')).Count

# C) CDP 读官方 UI 身份
$c_title=''; $c_text=''; $c_nodes=-1; $c_loose=''; $c_pass=-1
if ($Side -eq 'web') {
  $probe = @'
(function(){
  try{
    var t=document.title||'';
    var body=(document.body&&document.body.innerText||'').slice(0,300);
    var n=document.getElementsByTagName('*').length;
    var pass=document.querySelectorAll('input[type=password]').length;
    // 真正的原生口令对话框签名：input[type=password] 存在，或正按钮文案"保存并连接"作为独立按钮存在。
    var dialogBtn=Array.prototype.some.call(document.querySelectorAll('button'),function(b){
       return (b.innerText||'').trim()==='保存并连接';});
    // 宽松关键词命中（仅作诊断，**不作判据**：用户自己的会话标题里就含"连接"等词）
    var loose=null, m=body+t.match(/[^\n]{0,20}(保存并连接|密码|password|sign in|连接)[^\n]{0,20}/i);
    if(m) loose=m[0];
    return JSON.stringify({title:t,body:body,nodes:n,passwordInputs:pass,
       dialogSaveBtn:dialogBtn, looseKeywordHit:loose,
       looksLikeLoginDialog:(pass>0||dialogBtn)});
  }catch(e){return JSON.stringify({err:String(e)});}
})()
'@
  $probeFile = Join-Path $Out '_precheck_probe.js'
  $probe | Out-File -Encoding utf8 $probeFile
  $r = (pwsh -NoProfile -File "$PSScriptRoot\..\env\cdp_run_e6.ps1" -Serial $Dev -JsFile $probeFile 2>&1) -join "`n"
  $r | Out-File -Encoding utf8 (Join-Path $Out "precheck_${Side}_cdp.txt")
  Say "--- CDP probe ---"
  Write-Output $r
  if ($r -match '(\{.*\})') {
    try {
      $o = $Matches[1] | ConvertFrom-Json
      $c_title=$o.title; $c_text=$o.body; $c_nodes=$o.nodes
      $c_loose=$o.looseKeywordHit; $c_pass=$o.passwordInputs
      # 判据 C：官方 UI 身份 = title 是官方 opencode 标题 **且** 无口令输入框 **且** 无"保存并连接"对话框按钮
      #        **且** DOM 节点数远超对话框量级（P4 测对话框时 OcPerfJS=0；官方首页 845~910 节点）
      $c_notDialog = ($o.passwordInputs -eq 0) -and (-not $o.dialogSaveBtn) -and ($o.nodes -gt 100)
    } catch { $c_notDialog=$false }
  }
} else {
  # 原生：无 CDP。用 uiautomator 可见文本 + 截图判据
  A shell uiautomator dump /sdcard/_ui.xml | Out-Null
  $xml = ((A shell cat /sdcard/_ui.xml) | Out-String)
  $xml | Out-File -Encoding utf8 (Join-Path $Out 'precheck_native_ui.xml')
  $passInputs = ([regex]::Matches($xml,'password="true"')).Count
  $editTexts  = ([regex]::Matches($xml,'class="android\.widget\.EditText"')).Count
  $nodes = ([regex]::Matches($xml,'<node ')).Count
  $c_nodes = $nodes
  Say "--- native uiautomator: nodes=$nodes EditText=$editTexts password=true:$passInputs"
  $c_notDialog = ($editTexts -eq 0 -and $passInputs -eq 0 -and $nodes -gt 5)
  $c_title = "native-uiautomator nodes=$nodes EditText=$editTexts"
}

$verdict = ($a_passwordPresent -and $a_notAsking -and ($b_perfjs -gt 0 -or $soload -gt 0) -and $c_notDialog)
$res = [ordered]@{
  side=$Side; serial=$Dev
  A_passwordPresent=$a_passwordPresent
  A_not_asking_dialog=$a_notAsking
  B_OcPerfJS_occurrences=$b_perfjs
  B_page_markers=$soload
  C_cdp_title=$c_title
  C_loose_keyword_diagnostic=$c_loose
  C_password_inputs=$c_pass
  C_cdp_text_sample=if($c_text){$c_text.Substring(0,[Math]::Min(160,$c_text.Length))}else{''}
  C_dom_nodes=$c_nodes
  C_not_a_dialog=$c_notDialog
  VERDICT_OFFICIAL_UI=$verdict
}
$res | ConvertTo-Json -Depth 5 | Out-File -Encoding utf8 (Join-Path $Out "precheck_${Side}.json")
$res | ConvertTo-Json -Depth 5 | Write-Output
if ($verdict) { Say "PRECHECK PASS: official UI confirmed" } else { Say "PRECHECK FAIL: numbers from here on are VOID"; exit 1 }
