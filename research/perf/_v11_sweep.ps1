$ErrorActionPreference='Continue'
Add-Type -AssemblyName System.Drawing
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6230"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\perf\out_v11"
$RAW="$OUT\sweep_raw.txt"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = $m; Add-Content -Path $RAW -Value $s -Encoding utf8; Write-Output $s }

# heights are kept <= 1600 px on purpose (F4/R2/V6 silent-exit pattern was height > physical 1600)
$cells=@(
  @{id='phone360dp';  w=720;  h=1280; d=320; dp=360},
  @{id='phone412dp';  w=824;  h=1280; d=320; dp=412},
  @{id='fold442dp';   w=884;  h=1280; d=320; dp=442},
  @{id='fold708dp';   w=1416; h=1280; d=320; dp=708},
  @{id='bound700dp';  w=1400; h=1280; d=320; dp=700},
  @{id='bound820dp';  w=1640; h=1280; d=320; dp=820},
  @{id='probe1000dp'; w=2000; h=1280; d=320; dp=1000},
  @{id='probe1024dp'; w=2048; h=1280; d=320; dp=1024},
  @{id='tablet1366x768'; w=1366; h=768; d=160; dp=1366}
)

# ---- image analysis: ink metrics + locate the light-gray search box ----
function Get-Px($bmp,$x,$y){ $c=$bmp.GetPixel($x,$y); return @($c.R,$c.G,$c.B) }
function Analyze($path){
  $bmp=[System.Drawing.Bitmap]::FromFile($path)
  $W=$bmp.Width; $H=$bmp.Height
  $ink=0; $tot=0
  $leftInk=0; $leftTot=0; $rightInk=0; $rightTot=0; $botInk=0; $botTot=0
  for($y=0;$y -lt $H;$y+=6){
    for($x=0;$x -lt $W;$x+=6){
      $p=Get-Px $bmp $x $y; $tot++
      $isInk = ($p[0] -lt 235 -or $p[1] -lt 235 -or $p[2] -lt 235)
      if($isInk){ $ink++ }
      if($x -lt [int]($W*0.18) -and $y -gt [int]($H*0.10) -and $y -lt [int]($H*0.62)){ $leftTot++; if($isInk){$leftInk++} }
      if($x -gt [int]($W*0.80)){ $rightTot++; if($isInk){$rightInk++} }
      if($y -gt [int]($H*0.90)){ $botTot++; if($isInk){$botInk++} }
    }
  }
  # find search box: longest horizontal run of light gray in top 30%
  $bestRun=0; $bestY=0; $bestX=0
  for($y=[int]($H*0.06); $y -lt [int]($H*0.30); $y+=3){
    $run=0; $start=0
    for($x=0; $x -lt $W; $x+=2){
      $p=Get-Px $bmp $x $y
      $isGray = ([Math]::Abs($p[0]-$p[1]) -lt 8 -and [Math]::Abs($p[1]-$p[2]) -lt 8 -and $p[0] -ge 222 -and $p[0] -le 248)
      if($isGray){ if($run -eq 0){$start=$x}; $run++ } else { if($run -gt $bestRun){$bestRun=$run;$bestY=$y;$bestX=$start}; $run=0 }
    }
    if($run -gt $bestRun){$bestRun=$run;$bestY=$y;$bestX=$start}
  }
  $bmp.Dispose()
  return @{
    W=$W; H=$H
    inkPct=[math]::Round(100*$ink/[math]::Max(1,$tot),2)
    leftInkPct=[math]::Round(100*$leftInk/[math]::Max(1,$leftTot),2)
    rightInkPct=[math]::Round(100*$rightInk/[math]::Max(1,$rightTot),2)
    botInkPct=[math]::Round(100*$botInk/[math]::Max(1,$botTot),2)
    boxRun=$bestRun; boxX=$bestX; boxY=$bestY
  }
}

L ""
L "================ V11 FORM-FACTOR SWEEP  start $(Get-Date -Format 'HH:mm:ss') ================"
L "device=$S apk=$([System.IO.Path]::GetFileName('opencode-shell-web-release-nor8.apk')) pkg=$PKG"
L "NOTE: heights <= 1600px on purpose (F4/R2/V6 silent-exit pattern = geometry height > physical 1600)"
L "NOTE: CD 'dumpsys window insets' is an INVALID subcommand; the raw line lives in 'dumpsys window' / 'dumpsys window displays'"

$json=@()
foreach($c in $cells){
  L ""
  L "################ CELL $($c.id)  target $($c.dp)dp = $($c.w)x$($c.h)@$($c.d) ################"
  # --- apply geometry ---
  A @('shell','wm','size',"$($c.w)x$($c.h)") | Out-Null
  A @('shell','wm','density',"$($c.d)") | Out-Null
  Start-Sleep -Seconds 4
  $rbS = A @('shell','wm','size')
  $rbD = A @('shell','wm','density')
  L "WM_SET size=$($c.w)x$($c.h) density=$($c.d)"
  L "READBACK wmSize    : $rbS"
  L "READBACK wmDensity : $rbD"
  L "DPR=$($c.d)/160  => expected width dp = $([math]::Round($c.w*160/$c.d))"

  # --- cold relaunch ---
  A @('shell','am','force-stop',$PKG) | Out-Null
  Start-Sleep -Seconds 3
  A @('shell','logcat','-c') | Out-Null
  A @('shell','am','start','-n',$ACT) | Out-Null
  L "COLD: force-stop + 3s + am start"

  # --- PRECONDITION: wait until authed AND data delivered ---
  $authOk=$false; $dataOk=$false; $waited=0
  while($waited -lt 40){
    Start-Sleep -Seconds 2; $waited+=2
    $lg = A @('shell','logcat','-d','-s','OcWebHost','OcWebHost/Proxy')
    $authOk = ($lg -match 'F5 probe OK code=200')
    $dataOk = ($lg -match 'GET http://127.0.0.1:4095/api/session\?limit=5000')
    if($authOk -and $dataOk){ break }
  }
  L "PRECOND_AUTH  probe OK code=200 = $authOk   (waited ${waited}s)"
  L "PRECOND_DATA  GET /api/session?limit=5000 = $dataOk"
  $lg = A @('shell','logcat','-d','-s','OcWebHost','OcWebHost/Proxy')
  $failed = ($lg -match 'AUTH FAILED')
  $n401 = ([regex]::Matches($lg,'AUTH FAILED')).Count
  $nGet = ([regex]::Matches($lg,'GET http://127\.0\.0\.1:4095/api/')).Count
  L "PROBE_LINES: $(([regex]::Matches($lg,'F5 probe [^|]*') | ForEach-Object { $_.Value.Trim() }) -join ' || ')"
  L "PROXY_API_GET_COUNT=$nGet  AUTH_FAILED_COUNT=$n401"
  L "API_GET_UNIQUE: $((([regex]::Matches($lg,'GET http://127\.0\.0\.1:4095/api/[^ ]*') | ForEach-Object { $_.Value } | Select-Object -Unique)) -join ' ; ')"

  # --- screenshot + pixel metrics (no CDP on this build) ---
  Start-Sleep -Seconds 2
  $png="/sdcard/$($c.id).png"; $local="$OUT\$($c.id).png"
  A @('shell','screencap','-p',$png) | Out-Null
  A @('pull',$png,$local) | Out-Null
  $bytes=(Get-Item $local).Length
  $m = Analyze $local
  L "SCREENSHOT path=$local bytes=$bytes"
  L "PIXELS ${($c.id)}: size=$($m.W)x$($m.H) inkPct=$($m.inkPct) leftBandInkPct=$($m.leftInkPct) rightBandInkPct=$($m.rightInkPct) bottomBandInkPct=$($m.botInkPct)"
  L "SEARCHBOX_DETECT longestGrayRunPx=$($m.boxRun) at x=$($m.boxX) y=$($m.boxY)"

  # --- IME: focus the in-page search field, assert mInputShown, read raw insets line ---
  $tapX=[int]($m.boxX + $m.boxRun/2); $tapY=$m.boxY
  if($m.boxRun -lt 40){ $tapX=[int]($m.W*0.55); $tapY=[int]($m.H*0.16); L "IME_TAP fallback(fractional)" } else { L "IME_TAP detected-box" }
  L "IME_TAP coords=($tapX,$tapY)"
  A @('shell','input','tap',"$tapX","$tapY") | Out-Null
  Start-Sleep -Seconds 3
  $im = A @('shell','dumpsys','input_method')
  $shown = ($im -match 'mInputShown=true')
  $rawShown = (([regex]::Matches($im,'mInputShown=\w+') | ForEach-Object { $_.Value }) -join ',')
  $served = (([regex]::Matches($im,'mServedView=\S+') | ForEach-Object { $_.Value }) -join ',')
  L "IME_ASSERT mInputShown=true => $shown  [$rawShown]"
  L "IME_SERVEDVIEW $served"
  $win = A @('shell','dumpsys','window')
  $imeLine = (([regex]::Matches($win,'InsetsSource id=\d+ type=ime frame=\[[^\]]+\]\[[^\]]+\][^\r\n]*') | Select-Object -First 1) | ForEach-Object { $_.Value.Trim() })
  L "IME_RAW_LINE: $imeLine"
  $imeTop=$null
  if($imeLine -match 'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ $imeTop=[int]$Matches[2]; $imeBot=[int]$Matches[4]; $imeH=$imeBot-$imeTop }
  $cInsets = (([regex]::Matches($im,'contentTopInsets=\d+') | Select-Object -First 1) | ForEach-Object { $_.Value })
  $ct = if($cInsets -match 'contentTopInsets=(\d+)'){ [int]$Matches[1] } else { $null }
  $boundsTop=0
  if($im -match 'WindowMetricsNotification\{bounds=Rect\((\d+), (\d+) -'){ $boundsTop=[int]$Matches[2] }
  $contentBottom = if($null -ne $ct -and $boundsTop -ne $null){ $boundsTop + $ct } else { $null }
  L "IME_GEOMETRY imeTop=$imeTop imeBottom=$imeBot imeHeightPx=$(if($imeTop -ne $null){$imeH}) contentTopInsets=$ct windowBoundsTop=$boundsTop contentBottom=$contentBottom"
  $ovWin = if($null -ne $contentBottom -and $null -ne $imeTop){ [math]::Max(0,$contentBottom-$imeTop) } else { 'N/A' }
  L "IME_OVERLAP_WINDOW px=$ovWin  (contentBottom - imeTop; 0 = window fully resized above IME)"
  # focused input box bottom, re-detected with IME up
  A @('shell','screencap','-p',"/sdcard/$($c.id)_ime.png") | Out-Null
  A @('pull',"/sdcard/$($c.id)_ime.png","$OUT\$($c.id)_ime.png") | Out-Null
  $m2 = Analyze "$OUT\$($c.id)_ime.png"
  $boxBottom = $m2.boxY
  L "IME_SCREENSHOT path=$OUT\$($c.id)_ime.png bytes=$((Get-Item "$OUT\$($c.id)_ime.png").Length)"
  L "IME_FOCUSED_BOX re-detected y=$boxBottom runPx=$($m2.boxRun) (this is the search field's row)"
  $ovInput = if($null -ne $imeTop){ [math]::Max(0, ($boxBottom+6) - $imeTop) } else { 'N/A' }
  L "IME_OVERLAP_INPUT px=$ovInput  (focused search field bottom - imeTop; 0 = input not covered)"

  $valid = ($authOk -and $dataOk -and $n401 -eq 0 -and $m.inkPct -ge 1.5)
  L "CELL_VERDICT $($c.id): PRECONDITION_VALID=$valid (auth=$authOk data=$dataOk authFailed=$n401 inkPct=$($m.inkPct))"
  $json += [pscustomobject]@{
    id=$c.id; dp=$c.dp; geom="$($c.w)x$($c.h)@$($c.d)"; auth=$authOk; data=$dataOk; authFailed=$n401
    apiGets=$nGet; inkPct=$m.inkPct; leftBand=$m.leftInkPct; rightBand=$m.rightInkPct; bottomBand=$m.botInkPct
    imeShown=$shown; imeTop=$imeTop; contentBottom=$contentBottom; ovWin=$ovWin; ovInput=$ovInput
    valid=$valid; png="$($c.id).png"; pngIme="$($c.id)_ime.png"; bytes=$bytes
  }
  # dismiss IME before next cell
  A @('shell','input','keyevent','111') | Out-Null
}
$json | ConvertTo-Json -Depth 4 | Set-Content -Path "$OUT\formfactor_v11.json" -Encoding utf8
L ""
L "================ SWEEP DONE $(Get-Date -Format 'HH:mm:ss') ================"
$json | ForEach-Object { L "SUMMARY $($_.id): valid=$($_.valid) auth=$($_.auth) data=$($_.data) 401=$($_.authFailed) ink=$($_.inkPct) left=$($_.leftBand) bottom=$($_.bottomBand) ime=$($_.imeShown) ovWin=$($_.ovWin) ovInput=$($_.ovInput)" }
