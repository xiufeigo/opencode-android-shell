# v140 role-based geometry sampler — v139 structural role criteria (PIN_DEVICE_HARDLINK.md 2.1)
param([string]$Xml, [string]$Label)

$x = Get-Content $Xml -Raw
$nodes = [regex]::Matches($x, '<node\s[^>]*>') | ForEach-Object {
    $a = $_
    [pscustomobject]@{
        cls   = if($a.Value -match 'class="([^"]*)"'){$Matches[1]}else{''}
        txt   = if($a.Value -match 'text="([^"]*)"'){$Matches[1]}else{''}
        desc  = if($a.Value -match 'content-desc="([^"]*)"'){$Matches[1]}else{''}
        clk   = if($a.Value -match 'clickable="([^"]*)"'){$Matches[1]}else{'false'}
        b     = if($a.Value -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){
                    [pscustomobject]@{x1=[int]$Matches[1];y1=[int]$Matches[2];x2=[int]$Matches[3];y2=[int]$Matches[4]}
                }
    }
}
$nodes = $nodes | Where-Object { $_.b -ne $null }
"### $Label : total nodes = $($nodes.Count)"

# ROLE 1: editor = the unique android.widget.EditText
$edit = $nodes | Where-Object { $_.cls -eq 'android.widget.EditText' }
"ROLE editor        : count=$($edit.Count)  $(if($edit.Count -eq 1){'b=['+$edit[0].b.x1+','+$edit[0].b.y1+']['+$edit[0].b.x2+','+$edit[0].b.y2+'] h='+($edit[0].b.y2-$edit[0].b.y1)+" text='"+$edit[0].txt+"'"}else{'AMBIGUOUS/NONE'})"

$bandTop = $edit[0].b.y1
$bandPx  = $edit[0].b.y2 - $edit[0].b.y1
"ROLE band span     : $bandTop .. $($bandTop+$bandPx)  (bandPx=$bandPx)"

# ROLE 3: controls = clickable && top within band span && not editor
$ctrl = $nodes | Where-Object { $_.clk -eq 'true' -and $_.b.y1 -ge $bandTop -and $_.b.y1 -lt ($bandTop+$bandPx) -and $_.cls -ne 'android.widget.EditText' }
"ROLE controls(48dp semantic) : count=$($ctrl.Count)"
$ctrl | ForEach-Object { "    " + $_.cls + " desc='" + $_.desc + "' [" + $_.b.x1 + "," + $_.b.y1 + "][" + $_.b.x2 + "," + $_.b.y2 + "] h=" + ($_.b.y2-$_.b.y1) + " topRel=" + ($_.b.y1-$bandTop) }
if($ctrl.Count -gt 0){
  $tops = $ctrl | ForEach-Object { $_.b.y1 }
  $bots = $ctrl | ForEach-Object { $_.b.y2 }
  "    controlTop (min) = $($tops | Measure-Object -Minimum | Select-Object -ExpandProperty Minimum)  topRel=$(($tops | Measure-Object -Minimum).Minimum - $bandTop)"
  "    controlBottom(max)= $($bots | Measure-Object -Maximum | Select-Object -ExpandProperty Maximum)  botRel=$(($bots | Measure-Object -Maximum).Maximum - $bandTop)"
  "    topDelta vs editor top = $((($tops | Measure-Object -Minimum).Minimum) - $bandTop)"
}

# ROLE 4: control's own LAYOUT box = HorizontalScrollView with top in band span
$lay = $nodes | Where-Object { $_.cls -eq 'android.widget.HorizontalScrollView' -and $_.b.y1 -ge $bandTop -and $_.b.y1 -lt ($bandTop+$bandPx) }
"ROLE layoutBox(HSV) : count=$($lay.Count)  $(if($lay.Count -ge 1){($lay | ForEach-Object { '['+$_.b.x1+','+$_.b.y1+']['+$_.b.x2+','+$_.b.y2+'] h='+($_.b.y2-$_.b.y1)+' topRel='+($_.b.y1-$bandTop) }) -join ' | '})"

# ROLE 5: host/pane = full-width android.view.View spanning band top, SMALLEST bottom (ties = ambiguous)
$paneCand = $nodes | Where-Object { $_.cls -eq 'android.view.View' -and ($_.b.x2-$_.b.x1) -eq 1366 -and $_.b.y1 -le $bandTop -and $_.b.y2 -gt $bandTop }
$minBot = ($paneCand | ForEach-Object { $_.b.y2 } | Measure-Object -Minimum).Minimum
$paneAt  = $paneCand | Where-Object { $_.b.y2 -eq $minBot }
"ROLE pane           : candidates=$($paneCand.Count) minBottom=$minBot hits=$($paneAt.Count) $(if($paneAt.Count -eq 1){'UNIQUE b=['+$paneAt[0].b.x1+','+$paneAt[0].b.y1+']['+$paneAt[0].b.x2+','+$paneAt[0].b.y2+'] paneH='+($paneAt[0].b.y2-$paneAt[0].b.y1)}else{'AMBIGUOUS/TIE'})"

# Derived criteria
$unionTop = $bandTop; $unionBot = $bandTop + $bandPx
if($ctrl.Count -gt 0){
  $cmin = ($ctrl | ForEach-Object { $_.b.y1 } | Measure-Object -Minimum).Minimum
  $cmax = ($ctrl | ForEach-Object { $_.b.y2 } | Measure-Object -Maximum).Maximum
  $unionTop = [Math]::Min($unionTop,$cmin); $unionBot = [Math]::Max($unionBot,$cmax)
}
$layBot = if($lay.Count -ge 1){ ($lay[0].b.y2) } else { $null }
if($layBot -ne $null){ $unionBot = [Math]::Max($unionBot,$layBot) }
$unionPx = $unionBot - $unionTop
"UNION              : [$unionTop,$unionBot]  unionPx=$unionPx"
if($paneAt.Count -eq 1){
  $paneBottom = $paneAt[0].b.y2; $paneH = $paneAt[0].b.y2 - $paneAt[0].b.y1
  "CRIT 1(i)          : unionBottom $unionBot - paneBottom $paneBottom = " + ($unionBot - $paneBottom)
  "CRIT 1(ii)         : " + ($unionBot - 48 + 12) + " vs " + ($paneBottom - 48) + " = " + (($unionBot-48+12) - ($paneBottom-48))
  "CRIT 2             : unionPx $unionPx <= band $bandPx = " + ($unionPx -le $bandPx)
  "readable           : paneH=$paneH  readable=" + $(if($paneH -lt 150){$paneH-48}else{$paneH-96}) + "  timelineBox=" + ($paneH-48)
}
# state oracles
"ORACLE Stop        : $(@($nodes | Where-Object { $_.desc -eq 'Stop' }).Count)"
"ORACLE Working     : $(@($nodes | Where-Object { $_.desc -eq 'Working' -or $_.txt -match 'Working' }).Count)"
"ORACLE Queue chip  : $(@($nodes | Where-Object { $_.desc -eq 'Queue' }).Count)"
"ORACLE Steer       : $(@($nodes | Where-Object { $_.desc -eq 'Steer' }).Count)"
"ORACLE Remove      : $(@($nodes | Where-Object { $_.desc -eq 'Remove' }).Count)"
"ORACLE EditText txt: '$($edit[0].txt)'"
