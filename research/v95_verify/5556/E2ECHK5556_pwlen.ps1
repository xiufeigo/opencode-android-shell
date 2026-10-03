param([string]$Xml = 'C:\sensorsdata\main\program\opencode-android-shell\research\v95_verify\5556\ui_A07_pw.xml')
$c = Get-Content $Xml -Raw -Encoding UTF8
foreach ($m in [regex]::Matches($c, '<node[^>]*>')) {
  $s = $m.Value
  if ($s -notmatch 'class="android\.widget\.EditText"') { continue }
  $t = ''; if ($s -match 'text="([^"]*)"') { $t = $Matches[1] }
  $codes = ($t.ToCharArray() | ForEach-Object { 'U+{0:X4}' -f [int]$_ }) -join ' '
  "len={0}  text='{1}'" -f $t.Length, $t
  "codes: $codes"
}

