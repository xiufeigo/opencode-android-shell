param([string]$Tag = "d")
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md"
$pkg = "com.xiufeigo.opencodeshell"

& $adb -s emulator-5554 shell uiautomator dump --compressed /sdcard/u_$Tag.xml 2>&1 | Out-Null
& $adb -s emulator-5554 pull /sdcard/u_$Tag.xml "$dir\u_$Tag.xml" 2>&1 | Out-Null

$xml = Get-Content "$dir\u_$Tag.xml" -Raw
# match both text="..." and text='...' styles
$pat = '<node[^>]*>'
$nodes = [regex]::Matches($xml, $pat)
$rows = @()
foreach ($n in $nodes) {
    $s = $n.Value
    $cls = ''
    if ($s -match 'class="([^"]*)"') { $cls = $Matches[1] }
    $tx = ''
    if ($s -match 'text="([^"]*)"') { $tx = $Matches[1] }
    if ($tx -eq '' -and $s -match "text='([^']*)'") { $tx = $Matches[1] }
    $ds = ''
    if ($s -match 'content-desc="([^"]*)"') { $ds = $Matches[1] }
    if ($ds -eq '' -and $s -match "content-desc='([^']*)'") { $ds = $Matches[1] }
    $b = ''
    if ($s -match 'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"') { $b = $Matches[1] + "," + $Matches[2] + "," + $Matches[3] + "," + $Matches[4] }
    elseif ($s -match "bounds='\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]'") { $b = $Matches[1] + "," + $Matches[2] + "," + $Matches[3] + "," + $Matches[4] }
    $clk = ''
    if ($s -match 'clickable="([^"]*)"') { $clk = $Matches[1] }
    if ($b -eq '') { continue }
    $lbl = $tx
    if ($lbl -eq '' -or $lbl -eq $null) { $lbl = $ds }
    if ($null -eq $lbl) { $lbl = '' }
    $rows += [pscustomobject]@{
        Label = $lbl; Desc = $ds; Bounds = $b
        Short = ($cls -split '\.')[-1]; Clickable = $clk
    }
}
"=== NODES ($($rows.Count)) ==="
$rows | ForEach-Object { "{0,-30} | {1,-24} | {2,-24} | clk={3}" -f $_.Label, $_.Short, $_.Bounds, $_.Clickable }
