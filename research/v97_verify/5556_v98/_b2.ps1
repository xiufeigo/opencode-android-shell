# b2 helper v2 — 真机收口测试员 sweepB2 (emulator-5556)
# 所有 adb 参数一律用 @() 数组传，避免 -W 之类被 PowerShell 当参数名
$env:PATH = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools;$env:PATH"
$global:DEV = "emulator-5556"
$global:EV  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"

function A { param([string[]]$r) & adb -s $global:DEV @r }

function Sh { param([string]$c) & adb -s $global:DEV shell $c }        # 单字符串 shell 命令
function Tap { param([int]$x,[int]$y) & adb -s $global:DEV shell input tap $x $y }
function Swa { param([int]$x1,[int]$y1,[int]$x2,[int]$y2,[int]$ms=800) & adb -s $global:DEV shell input swipe $x1 $y1 $x2 $y2 $ms }
function Ke  { param([int]$k) & adb -s $global:DEV shell input keyevent $k }

function Shot { param($n)
  & adb -s $global:DEV shell screencap -p "/sdcard/$n.png" | Out-Null
  & adb -s $global:DEV pull "/sdcard/$n.png" "$global:EV\$n.png" 2>&1 | Out-Null
  "$global:EV\$n.png"
}

function Dump { param($n)
  for ($i=1; $i -le 4; $i++) {
    & adb -s $global:DEV shell uiautomator dump "/sdcard/$n.xml" 2>&1 | Out-Null
    $sz = (& adb -s $global:DEV shell stat -c %s "/sdcard/$n.xml" 2>&1) -join ''
    if ("$sz" -match '^\d{4,}$') {
      & adb -s $global:DEV pull "/sdcard/$n.xml" "$global:EV\$n.xml" 2>&1 | Out-Null
      return "$global:EV\$n.xml"
    }
    Start-Sleep -Milliseconds 800
  }
  "DUMPFAIL:$n"
}

# 同时匹配 text/content-desc 的双引号与单引号形式
function Nodes { param($f, $pat = '.')
  if ($f -notlike "*.xml") { return @("NOFILE:$f") }
  $t = Get-Content $f -Raw
  $o = New-Object System.Collections.ArrayList
  foreach ($m in [regex]::Matches($t, '(?:text|content-desc)="([^"]*)"')) { if ($m.Groups[1].Value -match $pat) { [void]$o.Add($m.Groups[1].Value) } }
  foreach ($m in [regex]::Matches($t, "(?:text|content-desc)='([^']*)'")) { if ($m.Groups[1].Value -match $pat) { [void]$o.Add($m.Groups[1].Value) } }
  $o | Select-Object -Unique
}

function Find1 { param($f, $pat)
  if ($f -notlike "*.xml") { return $null }
  $t = Get-Content $f -Raw
  foreach ($node in ($t -split '<node ')) {
    $lab = $null
    foreach ($q in @('"',"'")) {
      foreach ($tag in @('content-desc','text')) {
        $m = [regex]::Match($node, "$tag=$q([^$q]*)$q")
        if ($m.Success -and $m.Groups[1].Value.Trim() -ne '') { $lab = $m.Groups[1].Value; break }
      }
      if ($lab) { break }
    }
    if (-not $lab -or $lab -notmatch $pat) { continue }
    $b = [regex]::Match($node, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $b.Success) { continue }
    $x1=[int]$b.Groups[1].Value; $y1=[int]$b.Groups[2].Value; $x2=[int]$b.Groups[3].Value; $y2=[int]$b.Groups[4].Value
    return [pscustomobject]@{Label=$lab; X=[int](($x1+$x2)/2); Y=[int](($y1+$y2)/2); Raw="[$x1,$y1][$x2,$y2]"}
  }
  $null
}

function Txt { param([string]$s)
  foreach ($ch in $s.ToCharArray()) {
    if ($ch -eq ' ') { Ke 62 | Out-Null } else { & adb -s $global:DEV shell input text "$ch" | Out-Null }
    Start-Sleep -Milliseconds 35
  }
}

function Focus { & adb -s $global:DEV shell dumpsys window | Select-String "mCurrentFocus" }
function Api { param([string]$p)
  try { (Invoke-WebRequest -Uri "http://127.0.0.1:4095$p" -Headers @{Authorization="Basic "+[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("opencode:opencode"))} -TimeoutSec 15).Content } catch { "ERR:$_" }
}
function L { param($s="") Write-Output ("### " + $s) }
