# helper.ps1 — 5562 drawer6 test helpers (sourced each call)
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$DEV = "emulator-5562"
$PKG = "com.xiufeigo.opencodeshell"
$EV  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\5562_v98_drawer6"

function A { param([Parameter(ValueFromRemainingArguments=$true)]$a) & $adb -s $DEV @a }

# ---- dump: prefer --compressed, screencap -p + pull only (never exec-out) ----
function Dump {
    param([string]$tag)
    A shell "uiautomator dump --compressed /sdcard/d.xml" | Out-Null
    A pull "/sdcard/d.xml" "$EV\$tag.dump.xml" | Out-Null
    A shell "rm -f /sdcard/d.xml" | Out-Null
    "$EV\$tag.dump.xml"
}
function Shot {
    param([string]$tag)
    A shell "screencap -p /sdcard/s.png" | Out-Null
    A pull "/sdcard/s.png" "$EV\$tag.png" | Out-Null
    A shell "rm -f /sdcard/s.png" | Out-Null
    "$EV\$tag.png"
}
# ---- node parser: matches BOTH text="..." and text='...' ----
function Nodes { param([string]$file) Get-Content -LiteralPath $file -Raw -Encoding UTF8 }
function Attr {
    param([string]$line,[string]$name)
    # match name="v" or name='v'
    if($line -match ($name + '="([^"]*)"')) { return $Matches[1] }
    if($line -match ($name + "='([^']*)'")) { return $Matches[1] }
    return ""
}
function Bnd {
    param([string]$b)
    if($b -match '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') {
        return @([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4])
    }
    return $null
}
function Center {
    param([string]$b)
    $r = Bnd $b; if(-not $r){ return $null }
    return @([int](($r[0]+$r[2])/2), [int](($r[1]+$r[3])/2))
}
# list all nodes with index/class/bounds/clickable/desc/text
function ListNodes {
    param([string]$file)
    $x = Nodes $file
    $ms = [regex]::Matches($x,'<node [^>]*>')
    $i=0
    foreach($m in $ms){
        $i++
        $l = $m.Value
        $c = Attr $l 'class'; $b = Attr $l 'bounds'
        $clk = (Attr $l 'clickable') -eq 'true'
        $d = Attr $l 'content-desc'; $t = Attr $l 'text'
        $rid = Attr $l 'resource-id'
        "{0,3} | {1,-22} | {2,-26} | clk={3,-5} | desc='{4}' | txt='{5}' | rid='{6}'" -f $i,$c,$b,$clk,$d,$t,$rid
    }
}
# find first node whose desc or text matches regex; returns "idx|class|bounds|clk"
function FindNode {
    param([string]$file,[string]$pat)
    $x = Nodes $file
    $ms = [regex]::Matches($x,'<node [^>]*>')
    $i=0
    foreach($m in $ms){
        $i++
        $l=$m.Value
        $d = Attr $l 'content-desc'; $t = Attr $l 'text'
        if(($d -match $pat) -or ($t -match $pat)){
            "{0}|{1}|{2}|{3}" -f $i,(Attr $l 'class'),(Attr $l 'bounds'),((Attr $l 'clickable') -eq 'true')
        }
    }
}
function NodeCount { param([string]$file) ([regex]::Matches((Nodes $file),'<node ')).Count }
# signature lines for Compare-Object (geometry+semantics, no volatile)
function Sig {
    param([string]$file)
    $x = Nodes $file
    $ms = [regex]::Matches($x,'<node [^>]*>')
    foreach($m in $ms){
        $l=$m.Value
        "{0}|{1}|{2}|{3}" -f (Attr $l 'class'),(Attr $l 'bounds'),(Attr $l 'content-desc'),(Attr $l 'text')
    }
}
function DiffCount {
    param([string]$a,[string]$b)
    $d = Compare-Object (Sig $a) (Sig $b)
    if($null -eq $d){ 0 } else { $d.Count }
}
function ImeShown {
    $o = A shell "dumpsys input_method | grep mInputShown"
    ($o -join ' ')
}
function Focus { param([string]$f) (A shell "dumpsys window | grep mCurrentFocus") -join ' ' }
function Launch {
    A shell "am force-stop $PKG" | Out-Null
    Start-Sleep -Seconds 2
    A shell "monkey -p $PKG -c android.intent.category.LAUNCHER 1" | Out-Null
}
function Prefs { A shell "run-as $PKG cat shared_prefs/oc_settings.xml" }
function PrefsWrite {
    param([string]$key,[string]$val)
    A shell "run-as $PKG sh -c 'sed -i s|<string name=`"$key`\">.*</string>|<string name=`"$key`\">$val</string>| shared_prefs/oc_settings.xml'" | Out-Null
    Start-Sleep -Milliseconds 400
    Prefs
}
