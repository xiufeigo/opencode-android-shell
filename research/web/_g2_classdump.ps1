$ErrorActionPreference = 'Stop'
$root = 'C:\sensorsdata\main\program\opencode-android-shell'
$work = Join-Path $root 'research\web\_g2_work'
$dexdump = "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\dexdump.exe"
Write-Output "dexdump exists = $(Test-Path $dexdump)"

$targets = @(
  'Lcom/xiufeigo/opencodeshell/web/host/NotifierHost;',
  'Lcom/xiufeigo/opencodeshell/web/host/WebHostBridge;'
)

foreach ($variant in 'release','debug') {
  $dir = Join-Path $work "dex_$variant"
  $out = Join-Path $root "research\web\_g2_classdump_$variant.txt"
  $all = New-Object System.Text.StringBuilder
  foreach ($f in (Get-ChildItem $dir -Filter '*.dex' | Sort-Object Name)) {
    $txt = & $dexdump -h $f.FullName 2>&1
    $capture = $false
    foreach ($line in $txt) {
      $l = [string]$line
      if ($l -match "Class descriptor\s+:\s*'(.+)'") {
        $desc = $Matches[1]
        $capture = ($targets -contains $desc)
        if ($capture) { [void]$all.AppendLine("### from $($f.Name) :: $desc") }
      }
      if ($capture) { [void]$all.AppendLine($l) }
    }
  }
  Set-Content $out $all.ToString() -Encoding UTF8
  Write-Output ("{0}: captured lines = {1}" -f $variant, ($all.ToString() -split "`n").Count)
}

Write-Output ""
Write-Output "=== presence ==="
foreach ($variant in 'release','debug') {
  $c = Get-Content (Join-Path $root "research\web\_g2_classdump_$variant.txt") -Raw
  Write-Output ("{0}: NotifierHost={1}  WebHostBridge={2}" -f $variant,
    ($c -match 'host/NotifierHost'), ($c -match 'host/WebHostBridge'))
}
