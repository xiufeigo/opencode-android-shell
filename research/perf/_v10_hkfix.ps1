$ErrorActionPreference='Stop'
$p = "C:\sensorsdata\main\program\opencode-android-shell\research\HOUSEKEEPING.md"
$origLen = 28685
$fs = [System.IO.File]::Open($p, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Write)
$fs.SetLength($origLen)
$fs.Close()
"truncated_to=$((Get-Item $p).Length)"

# entry authored in a UTF-8 file, read back as UTF-8 (no codepage guessing)
$src = "C:\sensorsdata\main\program\opencode-android-shell\research\perf\_v10_entry.md"
$entry = [System.IO.File]::ReadAllText($src, [System.Text.Encoding]::UTF8)
$bytes = [System.Text.Encoding]::UTF8.GetBytes($entry)
$fs2 = [System.IO.File]::Open($p, [System.IO.FileMode]::Append, [System.IO.FileAccess]::Write)
$fs2.Write($bytes, 0, $bytes.Length)
$fs2.Close()
"appended_utf8_bytes=$($bytes.Length) new_size=$((Get-Item $p).Length)"

# integrity: whole file must now be clean UTF-8 roundtrip
$all = [System.IO.File]::ReadAllBytes($p)
$txt = [System.Text.Encoding]::UTF8.GetString($all)
"UTF8_roundtrip_identical=$([System.Linq.Enumerable]::SequenceEqual([byte[]][System.Text.Encoding]::UTF8.GetBytes($txt),[byte[]]$all))"
"replacement_chars=$(([regex]::Matches($txt,[string][char]0xFFFD)).Count)"
"has_V10=$($txt.Contains('V10')) has_6210=$($txt.Contains('emulator-6210')) has_report=$($txt.Contains('PHONE360_INVESTIGATION.md'))"
$idx = $txt.IndexOf('## V10')
"v10_section_at=$idx"
