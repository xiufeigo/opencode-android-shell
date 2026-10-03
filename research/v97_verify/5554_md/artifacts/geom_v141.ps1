# v141 FINAL role sampler — semantics-aware, form-agnostic discriminators
param([string]$Xml, [string]$Label)

$ErrorActionPreference = 'Stop'
$x = Get-Content $Xml -Raw
function A([string]$tag, [string]$name) {
    $m = [regex]::Match($tag, ($name + '="([^"]*)"'))
    if ($m.Success) { return $m.Groups[1].Value } else { return '' }
}
$nodes = @()
foreach ($m in [regex]::Matches($x, '<node\s[^>]*>')) {
    $tag = $m.Value
    $b = [regex]::Match($tag, 'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"')
    if (-not $b.Success) { continue }
    $g = $b.Groups
    $nodes += [pscustomobject]@{
        cls = (A $tag 'class'); desc = (A $tag 'content-desc'); txt = (A $tag 'text')
        clk = (A $tag 'clickable')
        x1 = [int]$g[1].Value; y1 = [int]$g[2].Value
        x2 = [int]$g[3].Value; y2 = [int]$g[4].Value
        h  = [int]$g[4].Value - [int]$g[2].Value
        w  = [int]$g[3].Value - [int]$g[1].Value
    }
}
"### $Label : nodes=$($nodes.Count)"
$screenW = ($nodes | ForEach-Object { $_.w } | Measure-Object -Maximum).Maximum

# ROLE A -- editor: the unique EditText
$ed = @($nodes | Where-Object { $_.cls -eq 'android.widget.EditText' })
"ROLE A editor            : count=$($ed.Count)"
if ($ed.Count -ne 1) { return }
$e = $ed[0]; $bandTop = $e.y1; $bandPx = $e.h
"   [$($e.x1),$($e.y1)][$($e.x2),$($e.y2)] h=$bandPx"

# ROLE B -- the tool row's OWN LAYOUT box (one HSV). Valid for BOTH forms:
#   stacked : its top is at/below the editor's bottom
#   one-row : it sits to the right of the editor, centre within one band height
$hs = @($nodes | Where-Object {
    $_.cls -eq 'android.widget.HorizontalScrollView' -and
    ((($_.y1 -ge ($e.y2 - 1)) -and ($_.y1 -le ($e.y2 + 2 * $bandPx))) -or
     (($_.x1 -ge $e.x2) -and ([Math]::Abs((($_.y1 + $_.y2) / 2) - (($e.y1 + $e.y2) / 2)) -le $bandPx)))
})
"ROLE B toolRowLayout(HSV): count=$($hs.Count)"
if ($hs.Count -ne 1) { $hs | ForEach-Object { "   candidate [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] h=$($_.h)" }; return }
$b = $hs[0]
"   [$($b.x1),$($b.y1)][$($b.x2),$($b.y2)] h=$($b.h) topRel=$($b.y1 - $bandTop) botRel=$($b.y2 - $bandTop)"

# FORM -- one-row iff the tool row's LAYOUT top coincides with the editor's top
$d = $b.y1 - $bandTop
$form = if ($d -lt [int]($bandPx / 4)) { "ONE-ROW" } else { "STACKED" }
"FORM                     : $form (layoutTopDelta=$d)"

# SEMANTIC INFLATION -- the 48dp touch target centred about the layout box
$infl = 48 - $b.h; $up = [Math]::Floor($infl / 2); $down = $infl - $up
$semTop = $b.y1 - $up; $semBot = $b.y2 + $down
"SEMANTIC INFLATION       : innerH=$($b.h) expansion=$infl split=$up up / $down down  =>  semantics [$semTop,$semBot]"

# ROLE C -- the 48dp SEMANTICS boxes fully INSIDE the derived box (not "top in band span")
$ctrl = @($nodes | Where-Object {
    $_.clk -eq 'true' -and $_.cls -ne 'android.widget.EditText' -and $_.h -eq 48 -and
    $_.y1 -ge $semTop -and $_.y2 -le $semBot
})
"ROLE C controls(48dp semantics): count=$($ctrl.Count)"
$ctrl | ForEach-Object { "   $($_.cls) desc='$($_.desc)' [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] topRel=$($_.y1 - $bandTop) botRel=$($_.y2 - $bandTop)" }
"   EXCLUDED 48dp clickables (proof the rule is a discriminator, not a filter-by-luck):"
@($nodes | Where-Object { $_.clk -eq 'true' -and $_.cls -ne 'android.widget.EditText' -and $_.h -eq 48 -and -not ($_.y1 -ge $semTop -and $_.y2 -le $semBot) }) | ForEach-Object {
    "   X $($_.cls) desc='$($_.desc)' [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] -- outside [$semTop,$semBot] by " +
        "top$(if($_.y1 -lt $semTop){'-' + ($semTop - $_.y1)}else{'+ok'})/bot$(if($_.y2 -gt $semBot){'-' + ($_.y2 - $semBot)}else{'+ok'})"
}

# ROLE D -- pane: tightest full-width View containing the band
$pane = @($nodes | Where-Object { $_.cls -eq 'android.view.View' -and $_.w -eq $screenW -and $_.y1 -lt $bandTop -and $_.y2 -gt $bandTop })
$minBot = ($pane | ForEach-Object { $_.y2 } | Measure-Object -Minimum).Minimum
$paneAt = @($pane | Where-Object { $_.y2 -eq $minBot })
"ROLE D pane              : cands=$($pane.Count) minBottom=$minBot hits=$($paneAt.Count) paneH=$(if($paneAt.Count -eq 1){$paneAt[0].y2 - $paneAt[0].y1}else{'TIE'})"

# DERIVED: the union bottom is now PREDICTED from the layout box, then cross-checked
$predUnion = [Math]::Max($e.y2, $semBot)
$actUnion = [Math]::Max($e.y2, ($ctrl | ForEach-Object { $_.y2 } | Measure-Object -Maximum).Maximum)
"UNION predicted (model)  : bottom=$predUnion  unionPx=$($predUnion - $bandTop)"
"UNION measured  (dump)   : bottom=$actUnion  unionPx=$($actUnion - $bandTop)  match=$($predUnion -eq $actUnion)"
"topDelta predicted       : $semTop - $e.y1 = $($semTop - $e.y1)"
if ($paneAt.Count -eq 1) {
    "CRIT1 predicted (PROD)  : $predUnion - $($paneAt[0].y2) = $($predUnion - $paneAt[0].y2)"
    "CRIT1 measured  (DUMP)  : $actUnion - $($paneAt[0].y2) = $($actUnion - $paneAt[0].y2)"
}
