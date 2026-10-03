# u1h measure: top-level (no function) so nothing can swallow output
. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1h\helper.ps1

$tag = $env:U1H_TAG
if (-not $tag) { $tag = "M" }

Write-Output "##### $tag #####"
$sz = (A shell wm size) -join ' | '
$dn = (A shell wm density) -join ' | '
Write-Output "wm size : $sz"
Write-Output "wm dens : $dn"
Write-Output "mInputShown: $(Get-InputShown)"
Write-Output "imeFrame   : $(Get-ImeFrame)"
Write-Output "focus : $(Get-Focus)"
Write-Output "pidof : $(Get-Pidof)"

$p = Dump $tag
if (-not $p) { Write-Output "DUMP-FAILED"; return }
$n = Get-Nodes $p
Write-Output "nodes: $($n.Count)"

$pane = $null
foreach ($x in $n) {
  $bb = Bnd $x.bounds
  if ($bb -and $bb.T -gt 100 -and $bb.W -gt 1000) { $pane = [pscustomobject]@{ n = $x; b = $bb }; break }
}

$et = $n | Where-Object { $_.class -match 'EditText' } | Select-Object -First 1
$add = $n | Where-Object { $_.'content-desc' -eq 'Add' } | Select-Object -First 1
$send = $n | Where-Object { $_.'content-desc' -eq 'Send' -or $_.'content-desc' -eq 'Stop' } | Select-Object -First 1

$ub = @()
if ($et) { $c = Bnd $et.bounds; $ub += $c; Write-Output ("  child EditText {0} h={1}" -f $et.bounds, $c.H) }
if ($add) { $c = Bnd $add.bounds; $ub += $c; Write-Output ("  child Add {0} h={1}" -f $add.bounds, $c.H) }
if ($send) { $c = Bnd $send.bounds; $ub += $c; Write-Output ("  child $($send.'content-desc') {0} h={1}" -f $send.bounds, $c.H) }

if ($ub.Count -eq 0) { Write-Output "  NO-COMPOSER-CHILDREN"; return }

$uT = ($ub | Measure-Object T -Minimum).Minimum
$uB = ($ub | Measure-Object B -Maximum).Maximum
$uL = ($ub | Measure-Object L -Minimum).Minimum
$uR = ($ub | Measure-Object R -Maximum).Maximum
$uH = $uB - $uT
Write-Output "  UNION  : y=$uT..$uB h=$uH x=$uL..$uR"

if ($pane) {
  $paneH = $pane.b.H
  $etb = Bnd $et.bounds
  $etH = $etb.H
  Write-Output "  PANE   : $($pane.b.L),$($pane.b.T)-$($pane.b.R),$($pane.b.B) h=$paneH class=$($pane.n.class)"
  Write-Output "  KEYunionBottom($uB)-paneBottom($($pane.b.B))=$($uB - $pane.b.B)"
  Write-Output "  KEYhostBoxBottom($($etb.B))-paneBottom($($pane.b.B))=$($etb.B - $pane.b.B)"
  Write-Output "  hostPaid(ET h)=$etH readable=$($paneH - $etH)"

  $imeH = 0
  $mf = A shell dumpsys input_method | Select-String 'mFrame=' | Select-Object -First 1
  if ($mf) { $mm = [regex]::Match($mf.Line, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'); if ($mm.Success) { $imeH = [int]$mm.Groups[2].Value } }
  $declared = [Math]::Min(64, $paneH)
  $measured = if ($paneH -ge $declared) { $declared } else { $paneH }
  $floorPx = [Math]::Max($declared, $measured)
  $ceiling = [Math]::Max(0, $paneH - $floorPx)
  $hostCan = [Math]::Max(0, [Math]::Min($ceiling, $paneH))
  Write-Output "  MODEL paneH=$paneH floorPx=$floorPx ceiling=$ceiling hostCanHeight=$hostCan actualHostPaid(ET)=$etH imeTopPx=$imeH"
  if ($ceiling -eq 0) { Write-Output "  CEILING0 hostShouldBe0=$($hostCan -eq 0) actualHostPaid=$etH hostIs0=$($etH -eq 0)" }
  Write-Output "  MODEL hostBox(TOOL_ROW 44dp=44px at 160dpi) top=composerTop=$($pane.b.T + ($paneH - [Math]::Min(44,$hostCan))) h=$([Math]::Min(44,$hostCan)) bottom=$($pane.b.T + ($paneH - [Math]::Min(44,$hostCan)) + [Math]::Min(44,$hostCan))"
} else { Write-Output "  NO-PANE" }
Write-Output ""
