# common.ps1 — shared harness for the OpenCode Android Shell native performance baseline.
# Sourced by every measure_*.ps1. Read-only w.r.t. the device except the
# gfxinfo/batterystats statistics resets (documented in BASELINE_NATIVE.md).

$ErrorActionPreference = 'Continue'

$Adb       = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Dev       = if ($env:MEASURE_DEV) { $env:MEASURE_DEV } else { 'emulator-5604' }
$Pkg       = 'com.xiufeigo.opencodeshell'
$Act       = "$Pkg/.OcActivity"
$OutDir    = if ($env:MEASURE_OUT) { $env:MEASURE_OUT } else { Join-Path $PSScriptRoot 'out' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function A { & $Adb -s $Dev @args }

# ---- UI coordinates measured on 1366x768 / density 160 -------------
# (dumped via uiautomator, see research/measure/_m0.xml)
$C = @{
  HomeBtn      = @(32, 70)     # action-bar "Home"
  SideNewSess  = @(140, 89)    # sidebar "New session"
  SidePlugins  = @(140, 138)   # sidebar "Plugins"
  SideSettings = @(75, 737)    # bottom-left "Settings"
  SideHelp     = @(140, 780)   # off-screen at 768 -> unused
  SearchSess   = @(190, 184)   # "Search sessions" chip
  ViewOptions  = @(222, 184)   # "View options" chip
  ListTop      = @(680, 400)   # workspace-list swipe start (scroll down)
  ListBot      = @(680, 200)   # workspace-list swipe end
  HomeListTop  = @(300, 450)
  HomeListBot  = @(300, 250)
}

function Tap   { param([int]$x,[int]$y) ; A shell input tap $x $y ; Start-Sleep -Milliseconds 450 }
function Swipe { param([int]$x1,[int]$y1,[int]$x2,[int]$y2,[int]$ms=300)
                 A shell input swipe $x1 $y1 $x2 $y2 $ms ; Start-Sleep -Milliseconds 350 }
function Back  { A shell input keyevent KEYCODE_BACK ; Start-Sleep -Milliseconds 700 }

# ---- SCRIPT-A: the fixed, offline-capable interaction --------------
# No backend v2 needed: every target below is a local/Compose screen.
function ScriptA {
  param([int]$NewSessionScrolls = 8, [int]$HomeScrolls = 5, [int]$UIOpenClose = 3)

  # 1. cold start
  A shell am force-stop $Pkg
  Start-Sleep -Milliseconds 800
  A shell am start -n $Act | Out-Null
  Start-Sleep -Seconds 3

  # 2. scroll the long workspace list on the New Session screen
  Tap $C.SideNewSess[0] $C.SideNewSess[1]
  Start-Sleep -Seconds 1
  for ($i=0; $i -lt $NewSessionScrolls; $i++) { Swipe $C.ListTop[0] $C.ListTop[1] $C.ListBot[0] $C.ListBot[1] }
  for ($i=0; $i -lt $NewSessionScrolls; $i++) { Swipe $C.ListBot[0] $C.ListBot[1] $C.ListTop[0] $C.ListTop[1] }
  Back

  # 3. open/close UI round trips. BACK is used to close the overlay, but we
  #    immediately re-focus via `am start` so the process is never cached
  #    (a cached/frozen process makes `dumpsys gfxinfo` report
  #     "Skipped: process is frozen" and loses the counters).
  for ($i=0; $i -lt $UIOpenClose; $i++) {
    Tap $C.SideNewSess[0]  $C.SideNewSess[1]  ; Start-Sleep -Milliseconds 800 ; Back
    A shell am start -n $Act | Out-Null ; Start-Sleep -Milliseconds 800
    Tap $C.SidePlugins[0]  $C.SidePlugins[1]  ; Start-Sleep -Milliseconds 800 ; Back
    A shell am start -n $Act | Out-Null ; Start-Sleep -Milliseconds 800
    Tap $C.SideSettings[0] $C.SideSettings[1] ; Start-Sleep -Milliseconds 800 ; Back
    A shell am start -n $Act | Out-Null ; Start-Sleep -Milliseconds 800
  }

  # 4. home-list scroll gestures (content may not move: only 4 rows fit,
  #    and the list is in the "Couldn't load sessions" error state offline)
  for ($i=0; $i -lt $HomeScrolls; $i++) {
    Swipe $C.HomeListTop[0] $C.HomeListTop[1] $C.HomeListBot[0] $C.HomeListBot[1]
    Swipe $C.HomeListBot[0] $C.HomeListBot[1] $C.HomeListTop[0] $C.HomeListTop[1]
  }

  # 5. command palette open/close
  Tap $C.SearchSess[0] $C.SearchSess[1]; Start-Sleep -Milliseconds 600; Back
  A shell am start -n $Act | Out-Null
}

# `dumpsys gfxinfo` returns "Skipped: process is frozen" when the app is not
# holding a window. Force it back to the foreground, then confirm it is alive.
function EnsureForeground {
  A shell am start -n $Act | Out-Null
  Start-Sleep -Seconds 2
  $pid_ = (A shell pidof $Pkg | Select-Object -First 1)
  if (-not $pid_) { A shell am start -n $Act | Out-Null ; Start-Sleep -Seconds 2 ; $pid_ = (A shell pidof $Pkg | Select-Object -First 1) }
  return ($pid_ -as [string])
}

function GetGfx { A shell dumpsys gfxinfo $Pkg }
function ResetGfx { A shell dumpsys gfxinfo $Pkg reset | Out-Null }
