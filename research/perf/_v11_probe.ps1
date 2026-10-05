$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6230"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }

"=== uiautomator dump on the LOADED official UI ==="
A @('shell','uiautomator','dump','--compressed','/sdcard/p1.xml') | Out-Null
$d = A @('shell','cat','/sdcard/p1.xml')
"XML_LEN=$($d.Length)"
"NODE_COUNT=$(([regex]::Matches($d,'<node ')).Count)"
$texts = [regex]::Matches($d,'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne '' }
"NONEMPTY_TEXT_COUNT=$($texts.Count)"
"--- first 30 texts ---"
$texts | Select-Object -First 30 | ForEach-Object { "  TXT: $_" }
"--- key markers ---"
foreach ($k in 'Projects','Add project','Settings','Help','Search sessions','Counting from 1 to 4000','DRAFT_OK') {
  "  MARK[$k]=$($d -match [regex]::Escape($k))"
}
"=== any EditText / focusable input? ==="
"EDITTEXT=$(([regex]::Matches($d,'android.widget.EditText')).Count)  INPUT=$(([regex]::Matches($d,'class="android.view.inputmethod')).Count)"
[regex]::Matches($d,'class="(android\.widget\.EditText|android\.webkit\.WebView)"[^>]*bounds="(\[[0-9,]+\]\[[0-9,]+\])"') | ForEach-Object { "  CLS=$($_.Groups[1].Value) BOUNDS=$($_.Groups[2].Value)" }
"=== IME / insets channel check ==="
"IME_SHOWN: $((A @('shell','dumpsys','input_method') | Select-String -Pattern 'mInputShown').ToString())"
"INSETS_IME_LINES:"
A @('shell','dumpsys','window','insets') | Select-String -Pattern 'ime' | Select-Object -First 8 | ForEach-Object { "  $_" }
