# v134 真机测试辅助脚本 —— 统一口径取证
# 用法: . .\helper.ps1  后调用 DumpUI / Shot / ImeShown / AppPid
$ErrorActionPreference = 'Continue'
$script:Adb    = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$script:Serial = "emulator-5554"
$script:Pkg    = "com.xiufeigo.opencodeshell"
$script:Ev     = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134"

function Adb { param([Parameter(ValueFromRemainingArguments=$true)]$a) & $script:Adb -s $script:Serial @a }

# dump UI 到本地文件 (不落 /sdcard)，用 /dev/tty
function DumpUI {
  param([string]$Name)
  $out = Join-Path $script:Ev "$Name.xml"
  $tmp = & $script:Adb -s $script:Serial exec-out uiautomator dump /dev/tty 2>$null
  $txt = ($tmp -join "`n")
  $i = $txt.IndexOf('<?xml')
  if($i -ge 0){ $txt = $txt.Substring($i) }
  $txt = $txt -replace '\s*UI hierchary dumped to:.*$',''
  Set-Content -Path $out -Value $txt -Encoding UTF8
  return $out
}

function Shot {
  param([string]$Name)
  $out = Join-Path $script:Ev "$Name.png"
  & $script:Adb -s $script:Serial exec-out screencap -p > $out
  return $out
}

function ImeShown {
  $r = & $script:Adb -s $script:Serial shell "dumpsys input_method | grep -E 'mInputShown'"
  if($r -match 'mInputShown=true'){ return $true } else { return $false }
}

function AppPid {
  $r = (& $script:Adb -s $script:Serial shell pidof $script:Pkg).Trim()
  return $r
}

# 从 dump 文件解析 content-desc 节点
function FindByDesc {
  param([string]$File,[string]$Text)
  $raw = Get-Content $File -Raw
  $rx = [regex]'<node[^>]*>'
  foreach($m in $rx.Matches($raw)){
    $n = $m.Value
    if($n -match ('content-desc="'+[regex]::Escape($Text)+'"')){ return $n }
  }
  return $null
}
