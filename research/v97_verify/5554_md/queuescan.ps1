param([string]$File)
$ErrorActionPreference = 'Continue'
$x = Get-Content $File -Raw
$hit = $false
"=== QUEUE-BAR CANDIDATES (desc/text matching Queue|Steer|dismiss|close|x) ==="
foreach ($m in [regex]::Matches($x, '<node[^>]*>')) {
    $s = $m.Value
    $b = @()
    if ($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') { $b = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4]) }
    $c = ''; if ($s -match 'clickable="([^"]*)"') { $c = $Matches[1] }
    $ds = ''; if ($s -match 'content-desc="([^"]*)"') { $ds = $Matches[1] }
    $t = ''; if ($s -match 'text="([^"]*)"') { $t = $Matches[1] }
    $cl = ''; if ($s -match 'class="([^"]*)"') { $cl = ($Matches[1] -split '\.')[-1] }
    $lab = "$t $ds"
    if ($lab -match 'Queue' -or $lab -match 'Steer') {
        $hit = $true
        if ($b.Count -eq 4) {
            "t=[$t] desc=[$ds] $cl ($($b[0]),$($b[1]))-($($b[2]),$($b[3])) w=$($b[2]-$b[0]) h=$($b[3]-$b[1]) clk=$c"
        } else { "t=[$t] desc=[$ds] $cl (no bounds)" }
    }
}
if (-not $hit) { "NO node with Queue/Steer label" }
""
"=== ALL clickable nodes with a content-desc, whole screen ==="
foreach ($m in [regex]::Matches($x, '<node[^>]*>')) {
    $s = $m.Value
    $b = @()
    if ($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') { $b = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4]) }
    $c = ''; if ($s -match 'clickable="([^"]*)"') { $c = $Matches[1] }
    $ds = ''; if ($s -match 'content-desc="([^"]*)"') { $ds = $Matches[1] }
    $cl = ''; if ($s -match 'class="([^"]*)"') { $cl = ($Matches[1] -split '\.')[-1] }
    if ($b.Count -eq 4 -and $c -eq 'true' -and $ds -ne '' -and $b[1] -gt 100) {
        "desc=[$ds] $cl ($($b[0]),$($b[1]))-($($b[2]),$($b[3])) w=$($b[2]-$b[0]) h=$($b[3]-$b[1])"
    }
}
