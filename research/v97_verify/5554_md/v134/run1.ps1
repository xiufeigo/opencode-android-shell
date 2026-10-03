$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134\a.ps1"
$ev = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134"
$log = New-Object System.Collections.Generic.List[string]
function L { param($m) $log.Add("$(Get-Date -Format 'HH:mm:ss') $m"); Write-Output "$(Get-Date -Format 'HH:mm:ss') $m" }

# 1) 聚焦 composer
A shell input tap 683 638 | Out-Null
Start-Sleep -Milliseconds 900
$f = DumpUI "a02_focus"
L "focus: composer='$(ComposerText $f)' imeShown=$(ImeShown)"

# 2) 清空
ClearComposer
Start-Sleep -Milliseconds 700
$f = DumpUI "a03_cleared"
L "cleared: composer='$(ComposerText $f)' imeShown=$(ImeShown)"

# 3) 发长回合
A shell "input text 'E2ECHK5554u1v134%sdeliver%scount%sfrom%s1%sto%s40000,%sone%snumber%sper%sline'" | Out-Null
Start-Sleep -Milliseconds 900
$f = DumpUI "a04_typed"
L "typed: composer='$(ComposerText $f)'"
$sc = DescCenter $f "Send"
L "sendBtn=$($sc -join ',')"
if($sc){ A shell input tap $sc[0] $sc[1] | Out-Null }
Start-Sleep -Seconds 4
$f = DumpUI "a05_sent"
$stopN = DescCount $f "Stop"; $workN = DescCount $f "Working"
L "afterSend: stopDesc=$stopN workingDesc=$workN composer='$(ComposerText $f)' imeShown=$(ImeShown)"
Shot "a05_sent" | Out-Null
$log -join "`n" | Set-Content "$ev\_a_log1.txt" -Encoding UTF8
