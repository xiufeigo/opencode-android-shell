# v140 baseline-criterion sampler: the criterion the baselines (140/92, -10/-12, 51) were taken with.
# unionBottom = composer child nodes from the editor top edge downward, EXCLUDING "Open terminal".
param([string]$Xml, [string]$Label)
$x = Get-Content $Xml -Raw
$N = [regex]::Matches($x, '<node\s[^>]*>') | ForEach-Object {
  $v=$_.Value
  [pscustomobject]@{
    cls =if($v -match 'class="([^"]*)"'){$Matches[1]}else{''}
    desc=if($v -match 'content-desc="([^"]*)"'){$Matches[1]}else{''}
    txt =if($v -match 'text="([^"]*)"'){$Matches[1]}else{''}
    clk =if($v -match 'clickable="([^"]*)"'){$Matches[1]}else{'false'}
    x1=if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){[int]$Matches[1]}else{0}
    y1=if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){[int]$Matches[2]}else{0}
    x2=if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){[int]$Matches[3]}else{0}
    y2=if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){[int]$Matches[4]}else{0}
  }
}
"### $Label"
$edit = $N | Where-Object { $_.cls -eq 'android.widget.EditText' } | Select-Object -First 1
$paneC = $N | Where-Object { $_.cls -eq 'android.view.View' -and ($_.x2-$_.x1) -eq 1366 -and $_.y1 -le $edit.y1 -and $_.y2 -gt $edit.y1 }
$pb = ($paneC | ForEach-Object { $_.y2 } | Measure-Object -Minimum).Minimum
$pane = $paneC | Where-Object { $_.y2 -eq $pb } | Select-Object -First 1
$paneH = $pane.y2 - $pane.y1
# composer child set: everything from the editor top down to the pane bottom, minus the terminal button
$set = $N | Where-Object {
  $_.y1 -ge $edit.y1 -and $_.y2 -le $pane.y2 -and $_.desc -ne 'Open terminal' -and -not ($_.cls -eq 'android.view.View' -and ($_.x2-$_.x1) -eq 1366 -and $_.y1 -eq $pane.y1 -and $_.y2 -eq $pane.y2)
}
$u1 = ($set | ForEach-Object { $_.y1 } | Measure-Object -Minimum).Minimum
$u2 = ($set | ForEach-Object { $_.y2 } | Measure-Object -Maximum).Maximum
"pane            : [$($pane.x1),$($pane.y1)][$($pane.x2),$($pane.y2)]  paneH=$paneH  paneBottom=$pb"
"composer set    : $($set.Count) nodes, union=[$u1,$u2]  unionBottom=$u2  unionPx=$($u2-$u1)"
"CRIT 1(i)        : unionBottom $u2 - paneBottom $pb = " + ($u2 - $pb)
"CRIT 1(ii)       : " + ($u2-48+12) + " vs " + ($pb-48) + " = " + (($u2-48+12)-($pb-48))
"CRIT 2           : unionPx $($u2-$u1) <= band 48 = " + (($u2-$u1) -le 48)
"readable         : paneH=$paneH -> readable=" + $(if($paneH -lt 150){$paneH-48}else{$paneH-96}) + "  timelineBox=" + ($paneH-48)
$set | Sort-Object -Property @{e={$_.y1}},@{e={$_.x1}} | ForEach-Object { "    {0,-26} desc='{1}' [{2},{3}][{4},{5}]" -f $_.cls,$_.desc,$_.x1,$_.y1,$_.x2,$_.y2 }
