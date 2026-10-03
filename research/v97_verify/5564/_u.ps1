param([string]$Tag="dump")
$ErrorActionPreference='Stop'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dir="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5564"
& $adb -s emulator-5564 shell uiautomator dump /sdcard/d.xml | Out-Null
& $adb -s emulator-5564 pull /sdcard/d.xml "$dir\_ui_$Tag.xml" | Out-Null
$raw=[IO.File]::ReadAllText("$dir\_ui_$Tag.xml",[Text.Encoding]::UTF8)
[Console]::OutputEncoding=[Text.Encoding]::UTF8
[regex]::Matches($raw,'<node[^>]*>') | ForEach-Object {
  $s=$_.Value
  if($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){
    $x1=[int]$Matches[1];$y1=[int]$Matches[2];$x2=[int]$Matches[3];$y2=[int]$Matches[4]
    $cd = if($s -match 'clickable="([^"]*)"'){$Matches[1]}else{'?'}
    $ds = if($s -match 'content-desc="([^"]*)"'){$Matches[1]}else{''}
    $tx = if($s -match 'text="([^"]*)"'){$Matches[1]}else{''}
    if($cd -eq 'true' -or $tx.Trim() -ne '' -or $ds.Trim() -ne ''){
      "{0,-8} c={1,-5} @({2},{3}) desc='{4}' text='{5}'" -f $Tag,$cd,[int](($x1+$x2)/2),[int](($y1+$y2)/2),$ds,$tx
    }
  }
}
