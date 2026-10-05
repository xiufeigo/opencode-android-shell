$ErrorActionPreference = 'Stop'
$avdRoot = "$env:USERPROFILE\.android\avd"
$rows = New-Object System.Collections.Generic.List[object]

$iniFiles = Get-ChildItem -Path $avdRoot -Filter '*.ini' -File -Force
foreach ($ini in $iniFiles) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($ini.Name)
    $dirPath = Join-Path $avdRoot "$name.avd"
    $bytes = 0L
    $fileCount = 0
    $err = ''
    if (Test-Path -LiteralPath $dirPath) {
        try {
            $items = Get-ChildItem -LiteralPath $dirPath -Recurse -File -Force -ErrorAction Stop
            foreach ($f in $items) { $bytes += $f.Length; $fileCount++ }
        } catch {
            $err = $_.Exception.Message
        }
    } else {
        $err = 'DIR_MISSING'
    }
    # .ini itself
    $bytes += $ini.Length
    $fileCount++
    $rows.Add([pscustomobject]@{
        Name       = $name
        Dir        = $dirPath
        Bytes      = $bytes
        Files      = $fileCount
        IniBytes   = $ini.Length
        DirMTime   = if (Test-Path -LiteralPath $dirPath) { (Get-Item -LiteralPath $dirPath).LastWriteTime } else { $null }
        Error      = $err
    })
}

$rows | Sort-Object Bytes -Descending | ConvertTo-Json -Depth 4
