# E2ECHK5560_cleanup.ps1 - delete one SSH server entry by row title, robustly.
# Re-locates the row's Server actions (the x-position disambiguates rows), then the
# Delete menu item, then the confirm button, and verifies the title is gone.
param(
    [Parameter(Mandatory = $true)][string]$Title,
    [string]$Tag = 'CL'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5560_run.ps1" -Dev emulator-5560

function Rows($x) {
    # return "titleY<TAB>actionsY" for every SSH tunnel row on screen
    $xml = [xml](Get-Content $x -Raw)
    $out = @()
    foreach ($n in $xml.SelectNodes('//node')) {
        $t = $n.GetAttribute('text')
        $b = $n.GetAttribute('bounds')
        if ($t -eq $Title -and $b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $out += [pscustomobject]@{ TitleY = [int]$Matches[2]; TitleX = [int]$Matches[1] }
        }
    }
    return $out
}
function ActionY($x, $titleY) {
    $xml = [xml](Get-Content $x -Raw)
    foreach ($n in $xml.SelectNodes('//node')) {
        if ($n.GetAttribute('content-desc') -eq 'Server actions') {
            $b = $n.GetAttribute('bounds')
            if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                $y = [int](($Matches[2] + $Matches[4]) / 2)
                if ($y -gt $titleY -and $y -lt ($titleY + 300)) { return $y }
            }
        }
    }
    return -1
}

for ($attempt = 1; $attempt -le 4; $attempt++) {
    Ui "E2ECHK5560_${Tag}_$attempt" | Out-Null
    $x = Join-Path $global:DIR "ui_E2ECHK5560_${Tag}_$attempt.xml"
    $r = Rows $x
    if (-not $r) { Write-Host "  '$Title' not on screen - delete complete"; break }
    $titleY = ($r | Select-Object -First 1).TitleY
    $ay = ActionY $x $titleY
    if ($ay -lt 0) { Write-Host '  Server actions not found; aborting'; break }
    Write-Host "  tap Server actions @$ay for '$Title'"
    A shell "input tap 843 $ay" | Out-Null
    Start-Sleep -Seconds 2

    Ui "E2ECHK5560_${Tag}_menu$attempt" | Out-Null
    $xm = Join-Path $global:DIR "ui_E2ECHK5560_${Tag}_menu$attempt.xml"
    $m = Select-String -Path $xm -Pattern 'text="Delete" bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' | Select-Object -First 1
    if (-not $m) { Write-Host '  Delete menu item not found'; break }
    $null = $m.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'
    $dx = [int](($Matches[1] + $Matches[3]) / 2); $dy = [int]($Matches[2])
    Write-Host "  tap Delete @$dx,$dy"
    A shell "input tap $dx $dy" | Out-Null
    Start-Sleep -Seconds 2

    Ui "E2ECHK5560_${Tag}_conf$attempt" | Out-Null
    $xc = Join-Path $global:DIR "ui_E2ECHK5560_${Tag}_conf$attempt.xml"
    $c = Select-String -Path $xc -Pattern 'Delete SSH server\?' -Quiet
    if (-not $c) { Write-Host '  no delete confirmation - menu tap missed'; continue }
    $c2 = Select-String -Path $xc -Pattern 'text="Delete" bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' | Select-Object -First 1
    if (-not $c2) { Write-Host '  confirm Delete button not found'; continue }
    $null = $c2.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'
    $cx = [int](($Matches[1] + $Matches[3]) / 2); $cy = [int](($Matches[2] + $Matches[4]) / 2)
    Write-Host "  confirm Delete @$cx,$cy"
    A shell "input tap $cx $cy" | Out-Null
    Start-Sleep -Seconds 2
}
Shot "E2ECHK5560_${Tag}_after_$Title"
Nodes "E2ECHK5560_${Tag}_after_$Title" | Select-String -Pattern 'E2EC5560|10.0.2.2|Add SSH server'
