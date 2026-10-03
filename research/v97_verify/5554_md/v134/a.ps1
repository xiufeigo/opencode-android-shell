$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$s = "emulator-5554"
$ev = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134"

function A { param([Parameter(ValueFromRemainingArguments=$true)]$a) & $adb -s $s @a }
function DumpUI { param([string]$Name)
  $out = Join-Path $ev "$Name.xml"
  $tmp = & $adb -s $s exec-out uiautomator dump /dev/tty 2>$null
  $txt = ($tmp -join "`n")
  $i = $txt.IndexOf('<?xml'); if($i -ge 0){ $txt = $txt.Substring($i) }
  $txt = $txt -replace '\s*UI hierchary dumped to:.*$',''
  Set-Content -Path $out -Value $txt -Encoding UTF8
  return $out
}
function Shot { param([string]$Name)
  $out = Join-Path $ev "$Name.png"
  & $adb -s $s exec-out screencap -p > $out
  return $out
}
function ImeShown {
  $r = & $adb -s $s shell "dumpsys input_method | grep -E 'mInputShown'"
  if(($r -join '') -match 'mInputShown=true'){ return $true } else { return $false }
}
# 返回某 desc 节点的 bounds 中心
function DescCenter { param([string]$File,[string]$Text)
  $raw = Get-Content $File -Raw
  foreach($m in ([regex]'<node[^>]*>').Matches($raw)){
    $n = $m.Value
    if($n -match ('content-desc="'+[regex]::Escape($Text)+'"')){
      if($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){
        $x1=[int]$Matches[1];$y1=[int]$Matches[2];$x2=[int]$Matches[3];$y2=[int]$Matches[4]
        return @([int](($x1+$x2)/2), [int](($y1+$y2)/2), "[$x1,$y1][$x2,$y2]")
      }
    }
  }
  return $null
}
function DescCount { param([string]$File,[string]$Text)
  $raw = Get-Content $File -Raw
  $c = 0
  foreach($m in ([regex]'<node[^>]*>').Matches($raw)){
    if($m.Value -match ('content-desc="'+[regex]::Escape($Text)+'"')){ $c++ }
  }
  return $c
}
function ComposerText { param([string]$File)
  $raw = Get-Content $File -Raw
  foreach($m in ([regex]'<node[^>]*>').Matches($raw)){
    $n=$m.Value
    if($n -match 'class="android\.widget\.EditText"'){
      if($n -match 'text="([^"]*)"'){ return $Matches[1] }
      return ""
    }
  }
  return $null
}
function ClearComposer {
  A shell input keyevent 123 | Out-Null
  A shell "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50 51 52 53 54 55 56 57 58 59 60; do input keyevent 67; done" | Out-Null
}
