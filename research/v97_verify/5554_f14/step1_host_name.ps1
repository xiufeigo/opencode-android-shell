# F14 step 1: fill HOST + NAME with sentinel round-trip proof.
. "$PSScriptRoot\f14lib.ps1"

$HOST_CMD = 'ssh -p 2222 sshtest@127.0.0.1'
$NAME     = 'E2ECHK5554f14-b'

Write-Host '=== RESET faillock before any attempt ==='
ResetFaillock

function Fill-Field {
    param([string]$DumpTag, [string]$Label, [string]$Sentinel, [string]$Final, [int]$Try = 1)
    Write-Host ""
    Write-Host "=== [$Label] try $Try : sentinel '$Sentinel' ==="
    $xml = Dump "$DumpTag`_t$Try"
    ShowEdits $xml 'before tap'
    $f = FieldBelow $xml $Label 0
    if (-not $f) { Write-Host "  FAIL: no EditText below label '$Label'"; return $false }
    Write-Host "  target bounds=$($f.Raw) centre=($($f.CX),$($f.CY))"
    TapXY $f.CX $f.CY

    # focus proof
    $xml2 = Dump "$DumpTag`_t${Try}_focus"
    $foc = (EditTexts $xml2 | Where-Object { $_.Focused -eq 'true' })
    if (-not $foc) { Write-Host '  FAIL: no focused EditText after tap'; ShowEdits $xml2 'after tap'; return $false }
    Write-Host "  focus OK -> bounds=$($foc[0].Raw)"

    # sentinel proof
    TypeOne $Sentinel
    $xml3 = Dump "$DumpTag`_t${Try}_sent"
    $hit = (EditTexts $xml3 | Where-Object { $_.Text -and $_.Text -like "*$Sentinel*" })
    if (-not $hit) {
        Write-Host "  FAIL: sentinel '$Sentinel' did NOT land in any EditText"
        ShowEdits $xml3 'after sentinel'
        return $false
    }
    Write-Host "  SENTINEL READBACK OK -> bounds=$($hit[0].Raw) text='$($hit[0].Text)'"

    # replace sentinel with the real value
    DelN $Sentinel.Length
    TypeText $Final
    $xml4 = Dump "$DumpTag`_t${Try}_final"
    $fin = (EditTexts $xml4 | Where-Object { $_.Text -and $_.Text -like "*$($Final.Split(' ')[0])*" })
    if ($fin) { Write-Host "  FINAL OK -> bounds=$($fin[0].Raw) text='$($fin[0].Text)'" }
    else { Write-Host '  WARN: final value not found on readback'; ShowEdits $xml4 'after final' }
    CollapseIme
    return $true
}

$okH = Fill-Field -DumpTag 'F14_host' -Label 'Host or SSH command' -Sentinel 'HX1' -Final $HOST_CMD
if (-not $okH) { Write-Host 'HOST FAILED -> abort' ; exit 1 }

$okN = Fill-Field -DumpTag 'F14_name' -Label 'Server name (optional)' -Sentinel 'NM1' -Final $NAME
if (-not $okN) { Write-Host 'NAME FAILED (non-fatal, name is cosmetic)' }

Write-Host ''
Write-Host '=== final form state ==='
$xf = Dump 'F14_after_hn'
ShowEdits $xf 'after host+name'
