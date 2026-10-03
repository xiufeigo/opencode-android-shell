# v134 工具链 v2 —— 绝对路径 + 空帧硬拦截
$ErrorActionPreference = 'Continue'
$Adb    = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$Serial = "emulator-5554"
$Pkg    = "com.xiufeigo.opencodeshell"
$Ev     = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v134"

function Adbx { param([Parameter(ValueFromRemainingArguments=$true)]$a) & $Adb -s $Serial @a }

# 空帧硬拦截：dump 为空或节点数不足即重试，仍失败则抛错
function DumpUI {
  param([string]$Name, [int]$Tries = 4)
  $out = Join-Path $Ev "$Name.xml"
  for($t=1; $t -le $Tries; $t++){
    $tmp = & $Adb -s $Serial exec-out uiautomator dump /dev/tty 2>$null
    $txt = ($tmp -join "`n")
    $i = $txt.IndexOf('<?xml')
    if($i -ge 0){ $txt = $txt.Substring($i) }
    $txt = $txt -replace '\s*UI hierchary dumped to:.*$',''
    $nodes = ([regex]'<node').Matches($txt).Count
    if($nodes -ge 20){
      Set-Content -Path $out -Value $txt -Encoding UTF8
      return @{ Path = $out; Nodes = $nodes }
    }
    Start-Sleep -Milliseconds 700
  }
  throw "DumpUI FAILED (all $Tries attempts empty) for $Name"
}

function ShotV2 { param([string]$Name)
  $out = Join-Path $Ev "$Name.png"
  & $Adb -s $Serial exec-out screencap -p > $out
  return $out
}

function ImeShownV2 {
  $r = (& $Adb -s $Serial shell "dumpsys input_method | grep -E 'mInputShown'" 2>$null) -join ''
  return ($r -match 'mInputShown=true')
}

function AppPidV2 { return ((& $Adb -s $Serial shell pidof $Pkg 2>$null) -join '').Trim() }

function NodeList { param([string]$File)
  $raw = Get-Content $File -Raw
  $list = New-Object System.Collections.Generic.List[object]
  foreach($m in ([regex]'<node[^>]*>').Matches($raw)){
    $n = $m.Value
    $o = [ordered]@{ desc=''; text=''; class=''; bounds=''; clickable='' }
    if($n -match 'content-desc="([^"]*)"'){ $o.desc = $Matches[1] }
    if($n -match 'text="([^"]*)"'){ $o.text = $Matches[1] }
    if($n -match 'class="([^"]*)"'){ $o.class = $Matches[1] }
    if($n -match 'clickable="([^"]*)"'){ $o.clickable = $Matches[1] }
    if($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ $o.bounds = "["+$Matches[1]+","+$Matches[2]+"]["+$Matches[3]+","+$Matches[4]+"]" }
    $list.Add([pscustomobject]$o)
  }
  return $list
}

function DescCount { param([string]$File,[string]$Text)
  $c = 0; foreach($n in (NodeList $File)){ if($n.desc -eq $Text){ $c++ } }; return $c
}
function TextCount { param([string]$File,[string]$Text)
  $c = 0; foreach($n in (NodeList $File)){ if($n.text -eq $Text){ $c++ } }; return $c
}
function DescCenter { param([string]$File,[string]$Text)
  foreach($n in (NodeList $File)){
    if($n.desc -eq $Text){
      if($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){
        return @([int](([int]$Matches[1]+[int]$Matches[3])/2), [int](([int]$Matches[2]+[int]$Matches[4])/2), $n.bounds)
      }
    }
  }
  return $null
}
function TextCenter { param([string]$File,[string]$Text)
  foreach($n in (NodeList $File)){
    if($n.text -eq $Text){
      if($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){
        return @([int](([int]$Matches[1]+[int]$Matches[3])/2), [int](([int]$Matches[2]+[int]$Matches[4])/2), $n.bounds)
      }
    }
  }
  return $null
}
function EditCenter { param([string]$File)
  foreach($n in (NodeList $File)){
    if($n.class -eq 'android.widget.EditText'){
      if($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){
        return @([int](([int]$Matches[1]+[int]$Matches[3])/2), [int](([int]$Matches[2]+[int]$Matches[4])/2), $n.bounds, $n.text)
      }
    }
  }
  return $null
}
function ComposerText { param([string]$File)
  $e = EditCenter $File; if($e){ return $e[3] }; return $null
}
function ShowQueue { param([string]$File,[string]$tag)
  Write-Output "  -- $tag"
  foreach($n in (NodeList $File)){
    if($n.desc -match 'E2ECHK5554u1v134|Steer|Rem[o]ve' -or $n.text -match 'E2ECHK5554u1v134|Steer|Rem[o]ve'){
      Write-Output ("     desc='" + $n.desc + "' text='" + $n.text + "' " + $n.bounds + " " + $n.class + " click=" + $n.clickable)
    }
  }
}
