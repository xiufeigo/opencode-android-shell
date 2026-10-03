$ErrorActionPreference='Continue'
. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1_lib.ps1

function Ctr([string]$p,[string]$k){ $n=FindTxt $p $k; if($n){ return @([int](($n.X1+$n.X2)/2),[int](($n.Y1+$n.Y2)/2)) }; return $null }
function TapSend { $p=DumpIt $args[0]; $c=Ctr $p 'Send'; if($c){ Ado "input tap $($c[0]) $($c[1])" | Out-Null; Start-Sleep -Milliseconds 500; return $true }; Write-Output "SENDFAIL"; return $false }

Write-Output "=== REGRESSION: plain message send ==="
$e = Ctr (DumpIt "79_pre") 'Ask anything'
if($e){ Ado "input tap $($e[0]) $($e[1])" | Out-Null; Start-Sleep -Milliseconds 400 }
TypeTxt "plain regression message for U1"
Start-Sleep -Milliseconds 400
$f0 = DumpIt "80_plaintyped"
$n0 = FindTxt $f0 'plain regression'
Write-Output "TYPED_OK=$($n0 -ne $null)"
$ok = TapSend "81_plainsend"
Start-Sleep -Seconds 3
$f1 = DumpIt "82_plainsent"
$sc = GetNodes $f1 | Where-Object { $_.Scroll -eq 'true' -and $_.W -gt 1000 } | Select-Object -First 1
$hits = @(GetNodes $f1 | Where-Object { $_.Txt -and $_.Txt.Contains('plain regression') })
Write-Output "SEND_TAPPED=$ok  IN_TREE=$($hits.Count)"
foreach($h in $hits){ Write-Output ("  [$($h.X1),$($h.Y1)][$($h.X2),$($h.Y2)] '$($h.Txt)'") }
Write-Output "TL=[$($sc.X1),$($sc.Y1)][$($sc.X2),$($sc.Y2)] h=$($sc.H) IME=$(ImeState) IMETOP=$(ImeTopPx)"
ShotIt "82_plainsent" | Out-Null

Write-Output "=== SESSION TITLE (for cleanup) ==="
$ns = GetNodes (DumpIt "83_title")
foreach($n in $ns){ if($n.X1 -lt 200 -and $n.Y1 -ge 110 -and $n.Y1 -le 160 -and $n.Txt){ Write-Output ("HDR [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] '$($n.Txt)'") } }
