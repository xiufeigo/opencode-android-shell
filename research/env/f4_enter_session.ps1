# F4: launch app -> wait for official UI -> enter first session -> return nav probe JSON
param(
  [Parameter(Mandatory=$true)][string]$Serial,
  [string]$Tag = 'fold'
)
$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = 'com.xiufeigo.opencodeshell.web.debug'
Set-Location $PSScriptRoot

& $adb -s $Serial shell am force-stop $pkg
& $adb -s $Serial shell am start -n "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity" | Out-Null

# wait for official UI (title=OpenCode), up to 60s
$ready = $false
for ($i=0; $i -lt 12; $i++) {
  Start-Sleep -Seconds 5
  $o = & .\cdp_run_e6.ps1 -Serial $Serial -Js "(function(){return JSON.stringify({t:document.title,o:location.origin,n:document.querySelectorAll('*').length,href:location.href});})()" -TimeoutSec 20 2>&1 | Out-String
  if ($o -match '"t":"OpenCode"') { Write-Output "OFFICIAL_UI_READY after $(($i+1)*5)s"; Write-Output "IDENTITY: $($o -split "`n" | Select-Object -Last 1)"; $ready = $true; break }
  else { Write-Output "[$((($i+1)*5))s] not-ready: $($o -split "`n" | Select-Object -Last 1)" }
}
if (-not $ready) { Write-Output "OFFICIAL_UI_NOT_READY"; exit 1 }

# locate first session row, tap it via device coords
$loc = & .\cdp_run_e6.ps1 -Serial $Serial -Js "(function(){var b=[].slice.call(document.querySelectorAll('button'));for(var i=0;i<b.length;i++){var t=(b[i].innerText||'').replace(/\s+/g,' ').trim();if(t.length>12&&/session|DSH|Counting|Today|Older|greeting|DRAFT|android/i.test(t)){var r=b[i].getBoundingClientRect();if(r.height>=40)return JSON.stringify({cx:Math.round(r.x+r.width/2),cy:Math.round(r.y+r.height/2),dpr:devicePixelRatio,txt:t.slice(0,40)});}}return 'NOTFOUND';})()" -TimeoutSec 20 2>&1 | Out-String
Write-Output "SESSION_ROW: $($loc -split "`n" | Select-Object -Last 1)"
if ($loc -match '"cx":(\d+),"cy":(\d+),"dpr":([\d.]+)') {
  $cx=[int]$Matches[1]; $cy=[int]$Matches[2]; $dpr=[double]$Matches[3]
  $dcx=[int][Math]::Round($cx*$dpr); $dcy=[int][Math]::Round($cy*$dpr)
  Write-Output "TAP device($dcx,$dcy) from css($cx,$cy) dpr=$dpr"
  & $adb -s $Serial shell input tap $dcx $dcy
  Start-Sleep -Seconds 9
  Write-Output "=== NAV PROBE [$Tag] ==="
  & .\cdp_run_e6.ps1 -Serial $Serial -JsFile ".\probe_f4_nav.js" -TimeoutSec 30
}
