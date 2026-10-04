$ErrorActionPreference = 'Stop'
$root = 'C:\sensorsdata\main\program\opencode-android-shell'
$out  = Join-Path $root 'research\web\_g2_dex_methods.txt'
function W($t){ $t | Tee-Object -FilePath $out -Append }
Remove-Item $out -EA SilentlyContinue

W "############ GAP#1 (cont.)  METHOD-LEVEL equivalence of NotifierHost / WebHostBridge ############"
W "dexdump -h output, filtered to the two f989907 host classes, parsed into method sets."
W ""

# Parse the already-generated class dumps into {class -> set(method signatures)}
function Parse-Classes($path) {
  $map = @{}
  $cur = $null
  foreach ($line in (Get-Content $path)) {
    if ($line -match "^### from (\S+) :: (.+)$") { $cur = $Matches[2]; if(-not $map.ContainsKey($cur)){$map[$cur]=@{dex=$Matches[1]; methods=New-Object System.Collections.Generic.HashSet[string]}} ; continue }
    if ($cur -and $line -match '^\s+name\s+:\s+(.+)$') {
      $map[$cur].methods.Add($Matches[1].Trim()) | Out-Null
    }
  }
  return $map
}

$rel = Parse-Classes (Join-Path $root 'research\web\_g2_classdump_release.txt')
$dbg = Parse-Classes (Join-Path $root 'research\web\_g2_classdump_debug.txt')

$targets = @('Lcom/xiufeigo/opencodeshell/web/host/NotifierHost;','Lcom/xiufeigo/opencodeshell/web/host/WebHostBridge;')
$totalDiff = 0
foreach ($t in $targets) {
  W "================ $t ================"
  W ("RELEASE: dex={0}  methodCount={1}" -f $(if($rel.ContainsKey($t)){$rel[$t].dex}else{'ABSENT'}), $(if($rel.ContainsKey($t)){$rel[$t].methods.Count}else{0}))
  W ("DEBUG  : dex={0}  methodCount={1}" -f $(if($dbg.ContainsKey($t)){$dbg[$t].dex}else{'ABSENT'}), $(if($dbg.ContainsKey($t)){$dbg[$t].methods.Count}else{0}))
  $r = if($rel.ContainsKey($t)){$rel[$t].methods}else{New-Object System.Collections.Generic.HashSet[string]}
  $d = if($dbg.ContainsKey($t)){$dbg[$t].methods}else{New-Object System.Collections.Generic.HashSet[string]}
  $onlyR = @($r | Where-Object { -not $d.Contains($_) } | Sort-Object)
  $onlyD = @($d | Where-Object { -not $r.Contains($_) } | Sort-Object)
  $common = @($r | Where-Object { $d.Contains($_) } | Sort-Object)
  W ("common={0}  releaseOnly={1}  debugOnly={2}" -f $common.Count, $onlyR.Count, $onlyD.Count)
  if ($onlyR.Count) { W ("-- ONLY IN RELEASE ({0}) --" -f $onlyR.Count); $onlyR | ForEach-Object { W ("   $_") } }
  if ($onlyD.Count) { W ("-- ONLY IN DEBUG ({0}) --" -f $onlyD.Count); $onlyD | ForEach-Object { W ("   $_") } }
  $totalDiff += ($onlyR.Count + $onlyD.Count)
  W ""
}
W ("TOTAL METHOD-SIGNATURE DIFFERENCES across the 2 classes = {0}" -f $totalDiff)
