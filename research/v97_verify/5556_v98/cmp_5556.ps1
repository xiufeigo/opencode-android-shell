# cmp_5556.ps1 —— 缩窗往返后几何是否确定（并集/子节点/pane 逐位比对）
param(
  [Parameter(Mandatory=$true)][string]$Files,   # 分号分隔
  [int]$imeTop768 = 294,
  [int]$imeTop600 = 213
)
$FileList = $Files -split ';' | Where-Object { $_.Trim() -ne '' }
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8

function Nodes($path){
  $c = Get-Content -LiteralPath $path -Raw -Encoding UTF8
  $out=@()
  foreach($chunk in ($c -split '(?=<node )')){
    if($chunk -notmatch 'bounds="'){continue}
    $b=[regex]::Match($chunk,'bounds="\[(?<l>-?\d+),(?<t>-?\d+)\]\[(?<r>-?\d+),(?<b>-?\d+)\]"')
    if(-not $b.Success){continue}
    $cls=''; if($chunk -match 'class="(?<v>[^"]*)"'){$cls=$Matches['v']}
    $L=[int]$b.Groups['l'].Value;$T=[int]$b.Groups['t'].Value;$R=[int]$b.Groups['r'].Value;$B=[int]$b.Groups['b'].Value
    $out+=[pscustomobject]@{Cls=($cls -replace '^.*\.','');L=$L;T=$T;R=$R;B=$B;W=$R-$L;H=$B-$T}
  }
  $out
}

foreach($f in $FileList){
  $n = Nodes $f
  $ime = if($f -match '600'){$imeTop600}else{$imeTop768}
  $pane = $n | Where-Object { $_.W -ge 1300 -and $_.L -eq 0 -and $_.H -lt 200 } | Sort-Object -Property H | Select-Object -First 1
  $ed  = $n | Where-Object { $_.Cls -eq 'EditText' } | Select-Object -First 1
  $btns= $n | Where-Object { $_.Cls -eq 'Button' -or $_.Cls -eq 'View' } | Where-Object { $_.H -eq 48 -and $_.T -ge ($ime-140) -and $_.T -lt $ime }
  # Open terminal: the Button whose bounds extend to R>=1300 and T < editor top
  $openTerm = $n | Where-Object { $_.Cls -eq 'Button' -and $_.R -ge 1360 -and $_.T -lt $ed.T }
  $union = $btns + $ed
  $uL=($union|Measure-Object L -Minimum).Minimum; $uT=($union|Measure-Object T -Minimum).Minimum
  $uR=($union|Measure-Object R -Maximum).Maximum; $uB=($union|Measure-Object B -Maximum).Maximum
  $edH=$ed.H
  $unionPx = if($edH -ge 48){48}else{$edH}
  $band = if($f -match '768'){44}else{48}   # 768=stacked(TOOL_ROW_HEIGHT=44) / 600=one-row(BAND_HEIGHT=48)
  Write-Output "=== $(Split-Path $f -Leaf) ==="
  Write-Output "  pane          : [$($pane.L),$($pane.T)][$($pane.R),$($pane.B)] h=$($pane.H)"
  Write-Output "  imeTop(hostB) : $ime"
  Write-Output "  editor        : [$($ed.L),$($ed.T)][$($ed.R),$($ed.B)] h=$edH"
  Write-Output "  toolRow btns  : $($btns.Count) (all h=48? $((($btns|Where-Object{$_.H -ne 48}).Count) -eq 0))"
  Write-Output "  Open terminal (EXCLUDED): $(if($openTerm){"[$($openTerm.L),$($openTerm.T)][$($openTerm.R),$($openTerm.B)] h=$($openTerm.H)"}else{'not found'})"
  Write-Output "  union         : [$uL,$uT][$uR,$uB]  unionPx=$unionPx  band=$band"
  $d1 = $uB - $ime
  $d2 = $unionPx - $band
  Write-Output "  (1) unionBottom-hostBottom = $uB - $ime = $d1  => $(if($d1 -le 0){'TRUE'}else{'FALSE'})"
  Write-Output "  (2) unionPx-band           = $unionPx - $band = $d2  => $(if($d2 -le 0){'TRUE'}else{'FALSE'})"
  Write-Output ""
}

