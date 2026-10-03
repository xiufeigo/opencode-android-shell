$ErrorActionPreference='Continue'
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
$OutputEncoding=[System.Text.Encoding]::UTF8
$script:ADB="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$script:D="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5564_v98"
$script:DEV="emulator-5564"

function Adb { param([Parameter(ValueFromRemainingArguments=$true)]$a) & $script:ADB -s $script:DEV @a }

function DumpUI { param([string]$tag)
  $remote="/sdcard/$tag.xml"
  $p="$script:D\$tag.xml"
  Adb shell uiautomator dump --compressed $remote | Out-Null
  Adb pull $remote $p 2>&1 | Out-Null
  Adb shell rm -f $remote | Out-Null
  return $p
}

# Get node text attr, matching both " and ' quoting
function GetAttr { param([string]$xml,[string]$attr,[string]$pattern)
  $enc = if($pattern){ [regex]::Escape($pattern) } else { $null }
  $rx = $attr + '="([^"]*)"'
  if($enc){ $rx = $attr + "='([^']*)'" }
  $out=@()
  $m=[regex]::Matches($xml,$rx)
  foreach($x in $m){ $out += $x.Groups[1].Value }
  return $out
}

# find a node whose text or content-desc matches, return its center bounds + clickable ancestors
function FindNode { param([string]$tag,[string]$needle)
  $p="$script:D\$tag.xml"
  $xml=[System.IO.File]::ReadAllText($p)
  $out=New-Object System.Collections.ArrayList
  $rx='<node[^>]*>'
  foreach($m in [regex]::Matches($xml,$rx)){
    $s=$m.Value
    $t=""; $d=""; $b=""; $clk="false"
    if($s -match 'text="([^"]*)"'){$t=$Matches[1]}
    if($s -match "text='([^']*)'"){$t=$Matches[1]}
    if($s -match 'content-desc="([^"]*)"'){$d=$Matches[1]}
    if($s -match "content-desc='([^']*)'"){$d=$Matches[1]}
    if($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$b=$Matches[1]+","+$Matches[2]+","+$Matches[3]+","+$Matches[4]}
    if($s -match 'clickable="true"'){$clk="true"}
    $hay = "$t $d"
    if(-not $needle -or ($hay -like "*$needle*")){
      [void]$out.Add([pscustomobject]@{Text=$t;Desc=$d;Bounds=$b;Clickable=$clk;Raw=$s})
    }
  }
  return $out
}

function CenterOf { param([string]$bounds)
  $p=$bounds.Split(',') | ForEach-Object {[int]$_}
  return @{X=[int](($p[0]+$p[2])/2); Y=[int](($p[1]+$p[3])/2)}
}

function Focus { (Adb shell dumpsys window | Select-String -Pattern 'mCurrentFocus=' | Select-Object -First 1).ToString().Trim() }
function ImeShown { (Adb shell dumpsys input_method | Select-String -Pattern 'mInputShown=' | Select-Object -First 1).ToString().Trim() }

function Shot { param([string]$tag)
  $remote="/sdcard/$tag.png"
  Adb shell "screencap -p $remote" | Out-Null
  Adb pull $remote "$script:D\$tag.png" 2>&1 | Out-Null
  Adb shell rm -f $remote | Out-Null
  return "$script:D\$tag.png"
}

function NotifCount { (Adb shell dumpsys notification --noredact | Select-String -Pattern 'NotificationRecord.*opencodeshell' | Measure-Object).Count }
