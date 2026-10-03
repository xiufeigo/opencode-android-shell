$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$patR='Remo'+'ve'
$rows=@()
Get-ChildItem $d -Filter "u1c_u1c_i*_f*.xml" | Sort-Object Name | ForEach-Object {
    $f=$_.FullName
    $m = Measure-Frame $f
    $n = Get-Nodes $f
    $sN = ($n | Where-Object { $_.Text -eq 'Steer' -or $_.Desc -eq 'Steer' }).Count
    $xN = ($n | Where-Object { $_.Text -eq $patR -or $_.Desc -eq $patR }).Count
    $rows += [pscustomobject]@{
        file=$_.Name; steerN=$sN; xmarkN=$xN
        tlBox=$m.timeline; tlH=$m.tlH; qbY=$m.queueBar; ov=$m.overlap; readable=$m.readable
        steerB=$(if($m.steer){$m.steer.b}); steerH=$(if($m.steer){$m.steer.h}); steerClk=$(if($m.steer){$m.steer.clk})
        xB=$(if($m.xmark){$m.xmark.b}); xH=$(if($m.xmark){$m.xmark.h}); xClk=$(if($m.xmark){$m.xmark.clk})
        qH=$m.qH; etH=$m.etH
    }
}
$rows | Format-Table -AutoSize | Out-String -Width 300
"=== SUMMARY ==="
$valid = $rows | Where-Object { $_.steerN -gt 0 -and $_.readable -ne $null }
"total frame dumps: $($rows.Count)"
"frames WITH queue bar (Steer present): $($valid.Count)"
if($valid.Count -gt 0){
  "readable MIN: $(($valid | Measure-Object -Property readable -Minimum).Minimum)"
  "readable MAX: $(($valid | Measure-Object -Property readable -Maximum).Maximum)"
  "timeline MIN: $(($valid | Measure-Object -Property tlH -Minimum).Minimum)"
  "steerH MIN: $(($valid | Measure-Object -Property steerH -Minimum).Minimum)"
  "xH MIN: $(($valid | Measure-Object -Property xH -Minimum).Minimum)"
  "frames with readable < 64: $(($valid | Where-Object {$_.readable -lt 64}).Count)"
  "frames with readable < 32 (16px magnitude): $(($valid | Where-Object {$_.readable -lt 32}).Count)"
}
$rows | Export-Csv -NoTypeInformation -Encoding UTF8 "$d\u1c_allframes.csv"
