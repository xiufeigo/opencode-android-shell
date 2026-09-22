<#
.SYNOPSIS
    Expand the vendored opencode v2 i18n dictionaries (research/_i18n/*.json) into
    Android string resources for the native Compose/View frontend.

.DESCRIPTION
    Source of truth:
      * research/_i18n/en.json   -> res/values/            (default / English)
      * research/_i18n/zh.json   -> res/values-zh-rCN/     (Simplified Chinese)
      * research/_i18n/zht.json  -> res/values-zh-rTW/     (Traditional Chinese)

    Authority: PLAN-android-native-refactor.md §5 ("保留所有 key ->
    res/values/strings.xml(en)、values-zh-rCN(zh)、values-zh-rTW(zht)").

    Key expansion rule (§5): dotted key -> underscore-separated resource name,
    case preserved (aapt2 accepts upper-case letters in values names; verified):
        ui.promptInput.placeholder.normal -> ui_promptInput_placeholder_normal

    Placeholders: `{{var}}` -> positional specifier `%1$s`, `%2$s`, ... assigned
    in order of first appearance (repeats reuse the index, which
    java.util.Formatter allows). `{{slash}}`/`{{at}}` are NOT arguments - PLAN §5
    says they render as the literal characters `/` and `@`. Specifiers are always
    `%s` (never `%d`) so any argument type stringifies safely.

    Scope: only the high-value UI namespaces in -Prefixes are expanded
    (default list below); the web-only tail (desktop updater / WSL / debug bar /
    file-type labels ...) is listed in the summary and left for later.
    `-Prefixes '*'` expands every key.

    Conflicts: a generated name that already exists as string/plurals/
    string-array anywhere under res/values*/ is never overwritten - it is
    skipped and reported ("existing wins").

    Idempotency / safety: generated output goes to its own file per locale
    (strings_oc_i18n.xml), so re-running cannot clobber the hand-written
    values/strings.xml that other agents edit concurrently. `-Mode merge`
    splices a delimited block into values/strings.xml instead.

.EXAMPLE
    pwsh -File scripts/gen_i18n.ps1
    pwsh -File scripts/gen_i18n.ps1 -Mode merge
    pwsh -File scripts/gen_i18n.ps1 -Prefixes '*'
#>
[CmdletBinding()]
param(
    [string] $RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string] $ResDir = 'app/src/main/res',
    [ValidateSet('separate', 'merge')] [string] $Mode = 'separate',
    # High-value UI namespaces (PLAN §5 scope). '*' = every top-level namespace.
    [string[]] $Prefixes = @(
        'app', 'command', 'common', 'context', 'dialog', 'directory', 'drag',
        'error', 'help', 'home', 'language', 'lsp', 'mcp', 'mode', 'model',
        'notification', 'palette', 'prompt', 'provider', 'server', 'session',
        'settings', 'sidebar', 'status', 'terminal', 'theme', 'titlebar',
        'toast', 'ui', 'workspace'
    ),
    [switch] $Quiet,
    # v2 dict source dir (wave 2.3c): research/_i18n_v2/{en,zh,zht}.json.
    # The default stays research/_i18n for backward compatibility.
    [string] $I18nDir = 'research/_i18n',
    # CompatShimJson: optional legacy dict (v1 research/_i18n/en.json + zh/zht)
    # whose keys are re-emitted when absent from the primary dict, so Kotlin
    # code referencing since-removed upstream keys keeps compiling. Each shim
    # entry is wrapped in an XML comment naming it as a legacy carry-over, and
    # the shim NEVER overrides a primary key or an existing hand-written name.
    # Empty = no shim (pure v2 output).
    [string] $CompatShimJson = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$GeneratedFileName = 'strings_oc_i18n.xml'
$BlockBegin = '<!-- BEGIN GENERATED i18n (scripts/gen_i18n.ps1) - do not edit by hand -->'
$BlockEnd = '<!-- END GENERATED i18n -->'

$Locales = @(
    [pscustomobject]@{ Id = 'en'; Json = 'en.json'; ValuesDir = 'values' },
    [pscustomobject]@{ Id = 'zh'; Json = 'zh.json'; ValuesDir = 'values-zh-rCN' },
    [pscustomobject]@{ Id = 'zht'; Json = 'zht.json'; ValuesDir = 'values-zh-rTW' }
)

$script:Warnings = [System.Collections.Generic.List[string]]::new()
# NOTE: named ConflictLog, not Conflicts - PowerShell is case-insensitive and a
# local $conflicts counter would silently overwrite a script-scope $Conflicts.
$script:ConflictLog = [System.Collections.Generic.HashSet[string]]::new()

function Say { param([string] $Message) if (-not $Quiet) { Write-Host $Message } }

function Read-Dict {
    param([string] $Path)
    $json = Get-Content -Raw -Encoding utf8 -LiteralPath $Path | ConvertFrom-Json
    $pairs = [System.Collections.Generic.List[object]]::new()
    foreach ($p in $json.PSObject.Properties) {
        if ($p.MemberType -eq 'NoteProperty') {
            $pairs.Add([pscustomobject]@{ Key = $p.Name; Value = [string]$p.Value })
        }
    }
    # returned as a single object so PowerShell does not unroll it
    return , $pairs
}

function Get-Namespace { param([string] $Key) return ($Key -split '\.')[0] }

function Convert-KeyToName {
    param([string] $Key)
    # Dashes are illegal in Android resource names; upstream keys use them
    # (ssh.error.host-key, settings.timeline.{preset,description}.text-only).
    # Underscore substitution is collision-free against the current v2 key
    # set (verified in wave 2.3c notes).
    return $Key.Replace('.', '_').Replace('-', '_')
}

function ConvertTo-AndroidValue {
    param([string] $Value, [string] $Key)

    # 1. literal-character placeholders (PLAN §5)
    $s = $Value.Replace('{{slash}}', '/').Replace('{{at}}', '@')

    # 2. remaining {{var}} -> positional %N$s, stable first-appearance order
    $rx = [regex]'\{\{([^{}]+)\}\}'
    $index = [ordered]@{}
    $sb = [System.Text.StringBuilder]::new()
    $last = 0
    foreach ($m in $rx.Matches($s)) {
        [void]$sb.Append($s.Substring($last, $m.Index - $last))
        $name = $m.Groups[1].Value
        if (-not $index.Contains($name)) { $index[$name] = $index.Count + 1 }
        [void]$sb.Append('%').Append($index[$name]).Append('$s')
        $last = $m.Index + $m.Length
    }
    [void]$sb.Append($s.Substring($last))
    $out = $sb.ToString()

    # 3. stray '%' (not part of a positional specifier) triggers aapt2's
    #    "multiple substitutions in non-positional format" warning -> double it.
    #    Today's dicts contain no '%' at all; this is a safety net only.
    if ($out.Contains('%')) {
        $stray = [regex]'%(?![0-9]+\$[a-zA-Z])'
        if ($stray.IsMatch($out)) {
            $out = $stray.Replace($out, '%%')
            $script:Warnings.Add("stray '%' doubled in $Key (renders '%%' unless the caller formats)")
        }
    }

    # 4. escaping; backslash first so later escapes are not re-escaped
    $out = $out.Replace('\', '\\')
    $out = $out.Replace('&', '&amp;').Replace('<', '&lt;').Replace('>', '&gt;')
    $out = $out.Replace("'", "\'").Replace('"', '\"')
    $out = $out.Replace("`r`n", '\n').Replace("`n", '\n').Replace("`r", '\n')

    # 5. a leading @ or ? is a resource-reference sigil
    if ($out.Length -gt 0 -and ($out[0] -eq '@' -or $out[0] -eq '?')) { $out = '\' + $out }

    # 6. aapt2 trims leading/trailing whitespace unless the value is quoted
    if ($out.Length -gt 0 -and $out -ne $out.Trim()) {
        $out = '"' + $out + '"'
        $script:Warnings.Add("quoted to preserve surrounding whitespace: $Key")
    }
    return $out
}

function Get-ExistingNames {
    param([string] $ResDirFull, [string] $SkipFileName)
    $map = @{}
    foreach ($d in (Get-ChildItem -LiteralPath $ResDirFull -Directory | Where-Object { $_.Name -like 'values*' })) {
        foreach ($f in (Get-ChildItem -LiteralPath $d.FullName -Filter '*.xml' -File)) {
            if ($f.Name -eq $SkipFileName) { continue }
            $text = Get-Content -Raw -Encoding utf8 -LiteralPath $f.FullName
            $body = [regex]::Replace($text, '(?s)<!-- BEGIN GENERATED i18n.*?END GENERATED i18n -->', '')
            foreach ($m in [regex]::Matches($body, '<(?:string|plurals|string-array)\s+name="([^"]+)"')) {
                $n = $m.Groups[1].Value
                if (-not $map.ContainsKey($n)) { $map[$n] = "$($d.Name)/$($f.Name)" }
            }
        }
    }
    return $map
}

# ---------------------------------------------------------------- main -------
$resFull = Join-Path $RepoRoot $ResDir
if (-not (Test-Path -LiteralPath $resFull)) { throw "res dir not found: $resFull" }
$i18nDir = Join-Path $RepoRoot $I18nDir
$expandAll = ($Prefixes.Count -eq 1 -and $Prefixes[0] -eq '*')

$dicts = @{}
$en = Read-Dict (Join-Path $i18nDir 'en.json')
$dicts['en'] = $en
foreach ($loc in $Locales) {
    if ($loc.Id -ne 'en') { $dicts[$loc.Id] = Read-Dict (Join-Path $i18nDir $loc.Json) }
}
$master = @{}   # every en key: a name must exist in the default locale to be a resource
foreach ($p in $en) { $master[$p.Key] = $true }

$nsCounts = @{}
foreach ($p in $en) {
    $n = Get-Namespace $p.Key
    if ($nsCounts.ContainsKey($n)) { $nsCounts[$n]++ } else { $nsCounts[$n] = 1 }
}
$inScope = @{}
foreach ($n in $nsCounts.Keys) {
    if ($expandAll -or ($Prefixes -contains $n)) { $inScope[$n] = $true }
}
$scopeKeys = @($en | Where-Object { $inScope.ContainsKey((Get-Namespace $_.Key)) })

Say "dict: en=$($en.Count) zh=$($dicts['zh'].Count) zht=$($dicts['zht'].Count) keys"
Say "in scope: $($scopeKeys.Count) keys over $($inScope.Count) namespaces (mode=$Mode)"

$existing = Get-ExistingNames -ResDirFull $resFull -SkipFileName $GeneratedFileName
Say "existing string/plurals/string-array names under res/values*/: $($existing.Count)"

# Optional legacy compat shim (wave 2.3c): keys present in a previous dict
# generation but removed upstream, kept so existing Kotlin references compile
# until the owning wave migrates them. The shim NEVER wins over a primary key
# or an existing hand-written name; every shim entry is comment-flagged.
$shimByLocale = @{}
$shimKeys = @()
if ($CompatShimJson -ne '') {
    $shimRoot = Join-Path $RepoRoot $CompatShimJson
    $shimEn = Read-Dict (Join-Path $shimRoot 'en.json')
    $primaryNames = @{}
    foreach ($p in $en) { $primaryNames[(Convert-KeyToName $p.Key)] = $true }
    foreach ($p in $shimEn) {
        if ($master.ContainsKey($p.Key)) { continue }          # still upstream: primary emits it
        $name = Convert-KeyToName $p.Key
        if ($primaryNames.ContainsKey($name)) { continue }     # name taken by a v2 key
        if ($existing.ContainsKey($name)) { continue }         # hand-written wins
        $shimKeys += $p
    }
    foreach ($loc in $Locales) {
        $shimDict = Read-Dict (Join-Path $shimRoot $loc.Json)
        $map = @{}
        foreach ($p in $shimDict) { $map[$p.Key] = $p.Value }
        $shimByLocale[$loc.Id] = $map
    }
    Say "compat shim: $($shimKeys.Count) legacy keys from $CompatShimJson (flagged legacy-carryover)"
}

$summary = [System.Collections.Generic.List[string]]::new()
$results = @()

foreach ($loc in $Locales) {
    $sb = [System.Text.StringBuilder]::new()
    [void]$sb.AppendLine('<?xml version="1.0" encoding="utf-8"?>')
    [void]$sb.AppendLine('<!--')
    [void]$sb.AppendLine('  GENERATED FILE - DO NOT EDIT BY HAND.')
    [void]$sb.AppendLine("  Produced by scripts/gen_i18n.ps1 from $I18nDir/$($loc.Json).")
    [void]$sb.AppendLine('  key expansion: dots/dashes -> underscores (ui.foo.bar -> ui_foo_bar); {{var}} -> %N$s.')
    [void]$sb.AppendLine('  Hand-written strings stay in values/strings.xml; re-run the script to refresh.')
    [void]$sb.AppendLine('-->')
    [void]$sb.AppendLine('<resources>')

    $present = @{}
    $valueByKey = @{}
    foreach ($p in $dicts[$loc.Id]) {
        $present[$p.Key] = $true
        $valueByKey[$p.Key] = $p.Value
    }

    $emitted = 0; $conflictCount = 0; $missing = 0; $ns = $null
    $seenLocal = @{}
    foreach ($p in $scopeKeys) {
        $key = $p.Key
        $name = Convert-KeyToName $key
        if ($existing.ContainsKey($name)) {
            $conflictCount++
            [void]$script:ConflictLog.Add("$name <- $key  (kept existing: $($existing[$name]))")
            continue
        }
        if ($seenLocal.ContainsKey($name)) { continue }   # same name twice in one dict (should not happen)
        if (-not $present.ContainsKey($key)) { $missing++; continue }
        $seenLocal[$name] = $key
        $localValue = $valueByKey[$key]

        $keyNs = Get-Namespace $key
        if ($keyNs -ne $ns) {
            [void]$sb.AppendLine()
            [void]$sb.AppendLine("    <!-- $keyNs.* -->")
            $ns = $keyNs
        }
        $value = ConvertTo-AndroidValue -Value $localValue -Key $key
        # 12 en values in the vendored dict are themselves dotted keys (upstream
        # extraction artifacts: the web runtime resolves them through the dict).
        # Flag them in place so the en copy can be fixed by hand.
        if ($master.ContainsKey($localValue.Trim())) {
            [void]$sb.AppendLine("    <!-- upstream alias: en value is the key '$($localValue.Trim())' (untranslated) -->")
        }
        if ($value.Length -eq 0) {
            [void]$sb.AppendLine("    <string name=""$name"" />")
        } else {
            [void]$sb.AppendLine("    <string name=""$name"">$value</string>")
        }
        $emitted++
    }
    # Compat shim tail: legacy keys (comment-flagged, never overriding).
    if ($shimKeys.Count -gt 0) {
        $shimMap = $shimByLocale[$loc.Id]
        $shimEmitted = 0
        [void]$sb.AppendLine()
        [void]$sb.AppendLine("    <!-- legacy-carryover (removed upstream; kept for existing Kotlin refs) -->")
        foreach ($p in $shimKeys) {
            $key = $p.Key
            $name = Convert-KeyToName $key
            if ($seenLocal.ContainsKey($name)) { continue }
            if (-not $shimMap.ContainsKey($key)) {
                # Locale gaps fall back to English at runtime, but a resource
                # must exist in every locale to be referenceable: use en.
                if ($loc.Id -eq 'en') { continue }  # en always has it (shim source)
                $localValue = $p.Value
            } else {
                $localValue = $shimMap[$key]
            }
            $seenLocal[$name] = $key
            $value = ConvertTo-AndroidValue -Value $localValue -Key $key
            [void]$sb.AppendLine("    <!-- legacy-carryover: upstream key '$key' no longer exists -->")
            if ($value.Length -eq 0) {
                [void]$sb.AppendLine("    <string name=""$name"" />")
            } else {
                [void]$sb.AppendLine("    <string name=""$name"">$value</string>")
            }
            $shimEmitted++
        }
        $summary.Add("[$($loc.Id)] shim=$shimEmitted legacy-carryover strings appended")
    }
    [void]$sb.AppendLine('</resources>')

    $dir = Join-Path $resFull $loc.ValuesDir
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }

    if ($loc.Id -eq 'en' -and $Mode -eq 'merge') {
        $target = Join-Path $dir 'strings.xml'
        $text = Get-Content -Raw -Encoding utf8 -LiteralPath $target
        $body = $sb.ToString()
        $body = [regex]::Replace($body, '(?s)^<\?xml[^>]*\?>\r?\n', '')
        $body = [regex]::Replace($body, '(?s)\r?\n</resources>\s*$', '')
        $block = "$BlockBegin`r`n$body`r`n$BlockEnd"
        # pure string splicing (no regex replacement) so '$' in %N$s stays literal
        $beginAt = $text.IndexOf($BlockBegin)
        if ($beginAt -ge 0) {
            $endAt = $text.IndexOf($BlockEnd, $beginAt)
            if ($endAt -lt 0) { throw "unterminated generated block in $target" }
            $endAt += $BlockEnd.Length
            $text = $text.Substring(0, $beginAt) + $block + $text.Substring($endAt)
        } else {
            $closeAt = $text.LastIndexOf('</resources>')
            if ($closeAt -lt 0) { throw "no </resources> in $target" }
            $text = $text.Substring(0, $closeAt) + $block + "`r`n" + $text.Substring($closeAt)
        }
        [System.IO.File]::WriteAllText($target, $text, [System.Text.UTF8Encoding]::new($false))
    } else {
        $target = Join-Path $dir $GeneratedFileName
        [System.IO.File]::WriteAllText($target, $sb.ToString(), [System.Text.UTF8Encoding]::new($false))
    }

    $summary.Add("[$($loc.Id)] emitted=$emitted conflicts=$conflictCount keys-missing-in-$($loc.Id)=$missing -> $target")
    $results += [pscustomobject]@{ Locale = $loc.Id; Emitted = $emitted; Conflicts = $conflictCount; Missing = $missing; Target = $target }
}
foreach ($line in $summary) { Say $line }

$skipped = @($nsCounts.Keys | Where-Object { -not $inScope.ContainsKey($_) } | Sort-Object)
$skippedKeys = 0
foreach ($n in $skipped) { $skippedKeys += $nsCounts[$n] }
Say ''
Say "NOT expanded: $($skipped.Count) namespaces / $skippedKeys keys"
Say ('  ' + (($skipped | ForEach-Object { "$_($($nsCounts[$_]))" }) -join ' '))
Say ''
$uniqWarnings = @($script:Warnings | Select-Object -Unique)
Say "warnings: $($uniqWarnings.Count)"
foreach ($w in $uniqWarnings) { Say "  - $w" }
Say "conflicts: $($script:ConflictLog.Count)"
foreach ($c in $script:ConflictLog) { Say "  - $c" }
