$ErrorActionPreference='Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\_tools\u11_lib.ps1"
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
$log=New-Object System.Collections.Generic.List[string]
function L([string]$s){ $log.Add($s); Write-Host $s }

# Band between the Files tab row (y~194) and the list container top (y=290).
# Tap-to-dismiss is the only IME close used; no BACK(4), no ESC inside any dialog.
function CloseIme([string]$tag){
  $n="$tag`_ci"
  if(-not (Dump $n)){ L "  CloseIme[$tag] dump failed"; return (ImeShown) }
  $x=Get-Content $n -Raw
  $busy=$false
  foreach($m in [regex]::Matches($x,'<node\b[^>]*>')){
    $b=[regex]::Match($m.Value,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if(-not $b.Success){ continue }
    $y1=[int]$b.Groups[2].Value; $y2=[int]$b.Groups[4].Value
    $x1=[int]$b.Groups[1].Value
    if($x1 -ge 1100 -and $y1 -lt 275 -and $y2 -gt 212){ $busy=$true }
  }
  $s=ImeShown
  if($s -eq 'false'){ L "  IME[$tag]=false (already closed)"; return $s }
  if(-not $busy){
    & $adb -s $S shell input tap 1250 245
    Start-Sleep -Milliseconds 900
    $s=ImeShown
  }
  if($s -ne 'false'){
    L "  IME[$tag]: tap-dismiss ineffective, still $s; using keyevent 111 (no dialog on screen)"
    & $adb -s $S shell input keyevent 111
    Start-Sleep -Milliseconds 900
    $s=ImeShown
  }
  L "  IME[$tag]=$s"
  return $s
}

# Runtime coordinate resolution from a fresh dump. No hardcoded taps.
function TapFind([string]$tag,[string]$pat){
  $n="$tag`_tf"
  if(-not (Dump $n)){ L "  TAP '$pat' -> DUMP FAILED"; return $false }
  $h = Find $n $pat | Select-Object -First 1
  if($h -match 'c=(\d+),(\d+)'){
    & $adb -s $S shell input tap $Matches[1] $Matches[2]
    L ("  TAP '$pat' -> " + $Matches[1] + "," + $Matches[2])
    return $true
  }
  L "  TAP '$pat' -> NOTFOUND"
  return $false
}
function SetEq($a,$b){
  $x=@($a | Sort-Object); $y=@($b | Sort-Object)
  $df = Compare-Object $x $y
  return ($df.Count -eq 0)
}

L "=== U11 RE-VERIFY / PHASE 1-4 ==="
L ("device=" + (& $adb -s $S shell getprop ro.product.model))
L ("avd=" + (& $adb -s $S shell "getprop ro.kernel.qemu.avd_name"))
L ("wm_size=" + ((& $adb -s $S shell wm size) -join ' '))
L ("wm_density=" + ((& $adb -s $S shell wm density) -join ' '))
L ("IME_ENTRY=" + (ImeShown))

# ---------- BASELINE ----------
L "--- STEP A: baseline (no filter) -> ALL ---"
L ("  container=" + (Container "u11_10_files"))
CloseIme "A" | Out-Null
L ("  ToTop=" + (ToTop "u11_30"))
$b=Traverse "u11_31" 18
$ALL=$b.rows
L ("  viewport_max=" + $b.viewport + " steps=" + $b.steps)
L ("  |ALL|=" + $ALL.Count)
L ("  ALL=" + (Names $ALL))

# ---------- FILTER ----------
L "--- STEP B: filter 'gradle' -> F ---"
TapFind "u11_40" 'Filter files' | Out-Null
Start-Sleep -Seconds 2
& $adb -s $S shell input text "gradle"
Start-Sleep -Seconds 2
L ("  IME_after_typing=" + (ImeShown))
CloseIme "B" | Out-Null
L ("  ToTop=" + (ToTop "u11_41"))
$fr=Traverse "u11_42" 8
$F=$fr.rows
L ("  |F|=" + $F.Count)
L ("  F=" + (Names $F))

# ---------- CLEAR ----------
L "--- STEP C: Clear filter (x) -> C ---"
TapFind "u11_50" 'Clear filter' | Out-Null
Start-Sleep -Seconds 2
CloseIme "C" | Out-Null
L ("  ToTop=" + (ToTop "u11_51"))
$cr=Traverse "u11_52" 18
$C=$cr.rows
L ("  |C|=" + $C.Count)
L ("  C=" + (Names $C))
L ("  container_after_clear=" + (Container "u11_52_tr0"))

# ---------- REFRESH ----------
L "--- STEP D: Refresh -> R ---"
CloseIme "D" | Out-Null
TapFind "u11_60" 'Refresh' | Out-Null
Start-Sleep -Seconds 5
CloseIme "D2" | Out-Null
L ("  ToTop=" + (ToTop "u11_61"))
$rr=Traverse "u11_62" 18
$R=$rr.rows
L ("  |R|=" + $R.Count)
L ("  R=" + (Names $R))

# ---------- ASSERTIONS ----------
L "--- ASSERTIONS ---"
$ca=SetEq $C $ALL
$ra=SetEq $R $ALL
$rf=SetEq $R $F
L ("  C_setEquals_ALL = " + $ca)
L ("  R_setEquals_ALL = " + $ra)
L ("  R_setEquals_F    = " + $rf)
if(-not $ca){ L ("  DIFF(C vs ALL): " + ((Compare-Object @($C|Sort-Object) @($ALL|Sort-Object) | ForEach-Object { "$($_.SideIndicator)$($_.InputObject)" }) -join ' ')) }
if(-not $ra){ L ("  DIFF(R vs ALL): " + ((Compare-Object @($R|Sort-Object) @($ALL|Sort-Object) | ForEach-Object { "$($_.SideIndicator)$($_.InputObject)" }) -join ' ')) }
L ("  FIRST_ALL=" + $(if($ALL.Count -gt 0){$ALL[0]}else{'n/a'}))
L ("  FIRST_F="   + $(if($F.Count -gt 0){$F[0]}else{'n/a'}))
L ("  FIRST_C="   + $(if($C.Count -gt 0){$C[0]}else{'n/a'}))
L ("  FIRST_R="   + $(if($R.Count -gt 0){$R[0]}else{'n/a'}))
L ("  VERDICT=" + $(if($ca -and $ra){"PASS"}else{"FAIL"}))

$log | Out-File -FilePath "$d\_u11_sets.txt" -Encoding utf8
L "WROTE $d\_u11_sets.txt"
