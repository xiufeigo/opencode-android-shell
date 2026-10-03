param([string]$Xml = 'C:\sensorsdata\main\program\opencode-android-shell\research\v94_verify\5556\ui_A04_form.xml', [string]$Only = '')
$ErrorActionPreference = 'Continue'
$c = Get-Content $Xml -Raw
foreach ($m in [regex]::Matches($c, '<node[^>]*>')) {
  $s = $m.Value
  $cls = ''; if ($s -match 'class="([^"]*)"') { $cls = $Matches[1] }
  if ($Only -ne '' -and $cls -notmatch $Only) { continue }
  $t = ''; if ($s -match 'text="([^"]*)"') { $t = $Matches[1] }
  $cd = ''; if ($s -match 'content-desc="([^"]*)"') { $cd = $Matches[1] }
  $pw = ''; if ($s -match 'password="([^"]*)"') { $pw = $Matches[1] }
  $lb = if ($t.Trim().Length -gt 0) { $t } else { $cd }
  $b = ''; if ($s -match 'bounds="([^"]*)"') { $b = $Matches[1] }
  "{0,-30} pw={1,-5} text='{2}' desc='{3}' {4}" -f ($cls -replace 'android\.widget\.',''), $pw, $t, $cd, $b
}
