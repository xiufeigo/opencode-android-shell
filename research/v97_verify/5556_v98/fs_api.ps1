$ErrorActionPreference = 'Continue'
$base = "http://127.0.0.1:4095"
$auth = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("opencode:opencode")) }

function Api([string]$path, [string]$method = "GET", $body = $null) {
    try {
        $p = $args[0]
    } catch {}
    $u = "$base$path"
    try {
        if ($method -eq "GET") { return Invoke-RestMethod -Uri $u -Headers $auth -TimeoutSec 15 }
        elseif ($method -eq "DELETE") { Invoke-RestMethod -Uri $u -Headers $auth -TimeoutSec 15 -Method Delete; return $null }
        else {
            $j = $body | ConvertTo-Json -Depth 6
            return Invoke-RestMethod -Uri $u -Headers $auth -TimeoutSec 15 -Method $method -Body $j -ContentType "application/json"
        }
    } catch {
        Write-Output "APIERR $method $u : $($_.Exception.Message)"
        return $null
    }
}

function Unwrap($x) {
    if ($null -eq $x) { return $null }
    if ($x.PSObject.Properties.Name -contains 'data') { return $x.data }
    return $x
}

function Sessions([string]$dir = "") {
    $q = "/api/session?limit=100&order=desc"
    if ($dir -ne "") { $q += "&directory=" + [uri]::EscapeDataString($dir) }
    return (Unwrap (Api $q))
}

function SessTable($s) {
    if ($null -eq $s) { Write-Output "(null)"; return }
    $arr = @(Unwrap $s)
    $i = 0
    foreach ($x in $arr) { $i++; Write-Output ("{0,2}. {1} | {2}" -f $i, $x.id, $x.title) }
}

function Msgs([string]$sid) {
    $m = Api "/api/session/$sid/message?order=asc&limit=80"
    if ($null -eq $m) { return @() }
    return @(Unwrap $m)
}

function MsgTable([string]$sid) {
    $m = Msgs $sid
    Write-Output "TOTAL=$($m.Count)"
    $i = 0
    foreach ($x in $m) {
        $i++
        $t = $x.type
        $extra = ""
        if ($t -eq "user") {
            $parts = @()
            foreach ($p in $x.parts) {
                if ($p.type -eq "text") { $parts += ("TEXT:" + ($p.text -replace "\s+"," ")) }
                elseif ($p.type -eq "file") { $parts += ("FILE:" + $p.filename + "|" + $p.mime + "|" + $p.url) }
                else { $parts += ("$($p.type)") }
            }
            $extra = ($parts -join " || ")
        } elseif ($t -eq "assistant") { $extra = "outcome=$($x.outcome)" }
        elseif ($t -eq "idle") { $extra = "outcome=$($x.outcome)" }
        Write-Output ("{0,3}. [{1}] {2}" -f $i, $t, $extra)
    }
}

function UserMsgs([string]$sid) {
    $m = Msgs $sid
    return @($m | Where-Object { $_.type -eq "user" })
}

function Worktrees() { return (Api "/api/worktree") }
