$ErrorActionPreference='Stop'
function Strip($s){ ($s -split "`n" | ForEach-Object { ($_ -split "//")[0] }) -join "`n" }
$f="app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelForget.kt"
$h="app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt"
$forgetRaw=(Get-Content $f -Raw).Replace("`r`n","`n")
$hostRaw=(Get-Content $h -Raw).Replace("`r`n","`n")

$bar=(Strip $forgetRaw)
$bar=$bar.Substring($bar.IndexOf("fun SshTunnelForgetConfirm("))
$card=$hostRaw.Substring($hostRaw.IndexOf("fun SshTunnelCard("))

$armLine="forget = forget.ask(tunnel.config.id)"
$after=$card -split [regex]::Escape($armLine),2
$tail=$after[1] -split "SshTunnelForgetConfirm\(",2
$armToConfirm=Strip $tail[0]

$fb=($hostRaw -split "fun forget\(id: String\) \{",2)[1] -split "`n    \}",2
$body=$fb[0]
$helper=($forgetRaw -split "fun confirmSshForget\(",2)[1]

function Res($name,$actual,$want){ $ok = ($actual -eq $want); if($ok){"OK   $name = $actual"}else{"FAIL $name = $actual (want $want)"} }

Res "NEG bar verticalScroll      " $bar.Contains("verticalScroll") $false
Res "NEG bar rememberScrollState " $bar.Contains("rememberScrollState") $false
Res "NEG arm forget(             " $armToConfirm.Contains("forget(") $false
Res "NEG arm abort(              " $armToConfirm.Contains("abort(") $false
Res "NEG arm disconnect(         " $armToConfirm.Contains("disconnect(") $false
Res "NEG arm host.forget         " $armToConfirm.Contains("host.forget") $false
Res "NEG arm confirmSshForget    " $armToConfirm.Contains("confirmSshForget") $false
Res "POS bar bringIntoViewReq    " $bar.Contains("bringIntoViewRequester(reveal)") $true
Res "POS bar reveal.bringIntoView" $bar.Contains("reveal.bringIntoView()") $true
Res "POS bar remember req        " $bar.Contains("remember { BringIntoViewRequester() }") $true
Res "POS bar LaunchedEffect(Unit)" $bar.Contains("LaunchedEffect(Unit)") $true
Res "POS card armed gate         " $card.Contains("if (forget.armed == tunnel.config.id)") $true
Res "POS card releaseFirst       " $card.Contains("releaseFirst = sshForgetReleasesTunnel(tunnel.status.stage)") $true
Res "POS card confirmSshForget   " $card.Contains("confirmSshForget(forget, tunnel.config.id, host, removePersisted)") $true
Res "POS ask() count == 2        " ([regex]::Matches($card,"forget = forget\.ask\(tunnel\.config\.id\)")).Count 2
Res "POS forgetBody abort-first  " ($body.IndexOf("abort()") -lt $body.IndexOf("disconnect(id)")) $true
Res "POS forgetBody watcher      " $body.Contains("watchJobs.remove(id)?.cancel()") $true
Res "POS forgetBody drops row    " $body.Contains("sshTunnelsWithout") $true
Res "POS helper removePersisted  " $helper.Contains("removePersisted(target)") $true
Res "POS helper host.forget      " $helper.Contains("host.forget(target)") $true
Res "POS helper order            " ($helper.IndexOf("removePersisted(target)") -lt $helper.IndexOf("host.forget(target)")) $true
Res "POS helper null-guard       " $helper.Contains("armed.confirm(id) ?: return null") $true
Res "POS card reconnect btn      " $card.Contains("onClick = { host.reconnect(tunnel.config.id) }") $true
Res "POS card disconnect btn     " $card.Contains("onClick = { host.disconnect(tunnel.config.id) }") $true
